package com.gdk.git;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gdk.git.Constants.AccessPermission;
import com.gdk.git.Constants.AccessRestrictionType;
import com.gdk.git.Constants.AuthorizationControl;
import com.gdk.git.Constants.CommitMessageRenderer;
import com.gdk.git.Constants.FederationStrategy;
import com.gdk.git.Constants.MergeType;
import com.gdk.git.Constants.PermissionType;
import com.gdk.git.Constants.RegistrantType;
import com.gdk.git.Keys;
import com.gdk.git.ForkModel;
import com.gdk.git.Metric;
import com.gdk.git.RefModel;
import com.gdk.git.RegistrantAccessPermission;
import com.gdk.git.RepositoryModel;
import com.gdk.git.TeamModel;
import com.gdk.git.UserModel;
import com.gdk.git.JGitUtils.LastChange;

/**
 * Repository manager creates, updates, deletes and caches git repositories.  It
 * also starts services to mirror, index, and cleanup repositories.
 *
 * @author James Moger
 */
public class RepositoryManager implements IRepositoryManager {

    private static final Logger logger = LoggerFactory.getLogger(RepositoryManager.class);

    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(5);

    private final ObjectCache<Long> repositorySizeCache = new ObjectCache<Long>();

    private final ObjectCache<List<Metric>> repositoryMetricsCache = new ObjectCache<List<Metric>>();

    private final Map<String, RepositoryModel> repositoryListCache = new ConcurrentHashMap<String, RepositoryModel>();

    private final AtomicReference<String> repositoryListSettingsChecksum = new AtomicReference<String>("");

    private File repositoriesFolder;

    private GarbageCollectorService gcExecutor;

    private MirrorService mirrorExecutor;

    private GitStoreSettings settings;
    private final IUserManager userManager;

    public RepositoryManager(GitStoreSettings settings, IUserManager userManager) {
        this.settings = settings;
        this.userManager = userManager;
        this.init();
    }

    private void init() {
        repositoriesFolder = this.settings.getRepositoriesFolder();
        logger.info("Repositories folder : {}", repositoriesFolder.getAbsolutePath());

        ModelUtils.setUserRepoPrefix(settings.getUserRepositoryPrefix());

        // calculate repository list settings checksum for future config changes
        repositoryListSettingsChecksum.set(getRepositoryListSettingsChecksum());

        // build initial repository list
        if (settings.isCacheRepositoryList()) {
            logger.info("Identifying repositories...");
            getRepositoryList();
        }

        configureGarbageCollector();
        configureMirrorExecutor();
        configureJGit();
        configureCommitCache();
        confirmWriteAccess();
    }

    public RepositoryManager stop() {
        scheduledExecutor.shutdownNow();
        gcExecutor.close();
        mirrorExecutor.close();
        closeAll();
        return this;
    }

    /**
     * Returns the most recent change date of any repository served by Gitblit.
     *
     * @return a date
     */
    @Override
    public Date getLastActivityDate() {
        Date date = null;
        for (String name : getRepositoryList()) {
            Repository r = getRepository(name);
            Date lastChange = JGitUtils.getLastChange(r).when;
            r.close();
            if (lastChange != null && (date == null || lastChange.after(date))) {
                date = lastChange;
            }
        }
        return date;
    }

    /**
     * Returns the path of the repositories folder. This method checks to see if
     * Gitblit is running on a cloud service and may return an adjusted path.
     *
     * @return the repositories folder path
     */
    @Override
    public File getRepositoriesFolder() {
        return repositoriesFolder;
    }

    /**
     * @return true if we are running the gc executor
     */
    @Override
    public boolean isCollectingGarbage() {
        return gcExecutor != null && gcExecutor.isRunning();
    }

    /**
     * Returns true if Gitblit is actively collecting garbage in this repository.
     *
     * @param repositoryName
     * @return true if actively collecting garbage
     */
    @Override
    public boolean isCollectingGarbage(String repositoryName) {
        return gcExecutor != null && gcExecutor.isCollectingGarbage(repositoryName);
    }

    /**
     * Returns the effective list of permissions for this user, taking into account
     * team memberships, ownerships.
     *
     * @param user
     * @return the effective list of permissions for the user
     */
    @Override
    public List<RegistrantAccessPermission> getUserAccessPermissions(UserModel user) {
        if (StringUtils.isEmpty(user.username)) {
            // new user
            return new ArrayList<RegistrantAccessPermission>();
        }

        Set<RegistrantAccessPermission> set = new LinkedHashSet<RegistrantAccessPermission>();
        set.addAll(user.getRepositoryPermissions());
        // Flag missing repositories
        for (RegistrantAccessPermission permission : set) {
            if (permission.mutable && PermissionType.EXPLICIT.equals(permission.permissionType)) {
                RepositoryModel rm = getRepositoryModel(permission.registrant);
                if (rm == null) {
                    permission.permissionType = PermissionType.MISSING;
                    permission.mutable = false;
                    continue;
                }
            }
        }

        // TODO reconsider ownership as a user property
        // manually specify personal repository ownerships
        for (RepositoryModel rm : repositoryListCache.values()) {
            if (rm.isUsersPersonalRepository(user.username) || rm.isOwner(user.username)) {
                RegistrantAccessPermission rp = new RegistrantAccessPermission(rm.name, AccessPermission.REWIND,
                        PermissionType.OWNER, RegistrantType.REPOSITORY, null, false);
                // user may be owner of a repository to which they've inherited
                // a team permission, replace any existing perm with owner perm
                set.remove(rp);
                set.add(rp);
            }
        }

        List<RegistrantAccessPermission> list = new ArrayList<RegistrantAccessPermission>(set);
        Collections.sort(list);
        return list;
    }

    /**
     * Returns the list of users and their access permissions for the specified
     * repository including permission source information such as the team or
     * regular expression which sets the permission.
     *
     * @param repository
     * @return a list of RegistrantAccessPermissions
     */
    @Override
    public List<RegistrantAccessPermission> getUserAccessPermissions(RepositoryModel repository) {
        List<RegistrantAccessPermission> list = new ArrayList<RegistrantAccessPermission>();
        if (AccessRestrictionType.NONE.equals(repository.accessRestriction)) {
            // no permissions needed, REWIND for everyone!
            return list;
        }
        if (AuthorizationControl.AUTHENTICATED.equals(repository.authorizationControl)) {
            // no permissions needed, REWIND for authenticated!
            return list;
        }
        // NAMED users and teams
        for (UserModel user : userManager.getAllUsers()) {
            RegistrantAccessPermission ap = user.getRepositoryPermission(repository);
            if (ap.permission.exceeds(AccessPermission.NONE)) {
                list.add(ap);
            }
        }
        return list;
    }

    /**
     * Sets the access permissions to the specified repository for the specified users.
     *
     * @param repository
     * @param permissions
     * @return true if the user models have been updated
     */
    @Override
    public boolean setUserAccessPermissions(RepositoryModel repository, Collection<RegistrantAccessPermission> permissions) {
        List<UserModel> users = new ArrayList<UserModel>();
        for (RegistrantAccessPermission up : permissions) {
            if (up.mutable) {
                // only set editable defined permissions
                UserModel user = userManager.getUserModel(up.registrant);
                user.setRepositoryPermission(repository.name, up.permission);
                users.add(user);
            }
        }
        return userManager.updateUserModels(users);
    }

    /**
     * Returns the list of all users who have an explicit access permission
     * for the specified repository.
     *
     * @param repository
     * @return list of all usernames that have an access permission for the repository
     * @see IUserService.getUsernamesForRepositoryRole(String)
     */
    @Override
    public List<String> getRepositoryUsers(RepositoryModel repository) {
        return userManager.getUsernamesForRepositoryRole(repository.name);
    }

    /**
     * Returns the list of teams and their access permissions for the specified
     * repository including the source of the permission such as the admin flag
     * or a regular expression.
     *
     * @param repository
     * @return a list of RegistrantAccessPermissions
     */
    @Override
    public List<RegistrantAccessPermission> getTeamAccessPermissions(RepositoryModel repository) {
        List<RegistrantAccessPermission> list = new ArrayList<RegistrantAccessPermission>();
        for (TeamModel team : userManager.getAllTeams()) {
            RegistrantAccessPermission ap = team.getRepositoryPermission(repository);
            if (ap.permission.exceeds(AccessPermission.NONE)) {
                list.add(ap);
            }
        }
        Collections.sort(list);
        return list;
    }

    /**
     * Sets the access permissions to the specified repository for the specified teams.
     *
     * @param repository
     * @param permissions
     * @return true if the team models have been updated
     */
    @Override
    public boolean setTeamAccessPermissions(RepositoryModel repository, Collection<RegistrantAccessPermission> permissions) {
        List<TeamModel> teams = new ArrayList<TeamModel>();
        for (RegistrantAccessPermission tp : permissions) {
            if (tp.mutable) {
                // only set explicitly defined access permissions
                TeamModel team = userManager.getTeamModel(tp.registrant);
                team.setRepositoryPermission(repository.name, tp.permission);
                teams.add(team);
            }
        }
        return userManager.updateTeamModels(teams);
    }

    /**
     * Returns the list of all teams who have an explicit access permission for
     * the specified repository.
     *
     * @param repository
     * @return list of all teamnames with explicit access permissions to the repository
     * @see IUserService.getTeamnamesForRepositoryRole(String)
     */
    @Override
    public List<String> getRepositoryTeams(RepositoryModel repository) {
        return userManager.getTeamNamesForRepositoryRole(repository.name);
    }

    /**
     * Adds the repository to the list of cached repositories if Gitblit is
     * configured to cache the repository list.
     *
     * @param model
     */
    @Override
    public void addToCachedRepositoryList(RepositoryModel model) {
        if (settings.isCacheRepositoryList()) {
            String key = getRepositoryKey(model.name);
            repositoryListCache.put(key, model);

            // update the fork origin repository with this repository clone
            if (!StringUtils.isEmpty(model.originRepository)) {
                String originKey = getRepositoryKey(model.originRepository);
                if (repositoryListCache.containsKey(originKey)) {
                    RepositoryModel origin = repositoryListCache.get(originKey);
                    origin.addFork(model.name);
                }
            }
        }
    }

    /**
     * Removes the repository from the list of cached repositories.
     *
     * @param name
     * @return the model being removed
     */
    private RepositoryModel removeFromCachedRepositoryList(String name) {
        if (StringUtils.isEmpty(name)) {
            return null;
        }
        String key = getRepositoryKey(name);
        return repositoryListCache.remove(key);
    }

    /**
     * Clears all the cached metadata for the specified repository.
     *
     * @param repositoryName
     */
    private void clearRepositoryMetadataCache(String repositoryName) {
        repositorySizeCache.remove(repositoryName);
        repositoryMetricsCache.remove(repositoryName);
        CommitCache.instance().clear(repositoryName);
    }

    /**
     * Reset all caches for this repository.
     *
     * @param repositoryName
     * @since 1.5.1
     */
    @Override
    public void resetRepositoryCache(String repositoryName) {
        removeFromCachedRepositoryList(repositoryName);
        clearRepositoryMetadataCache(repositoryName);
        // force a reload of the repository data (ticket-82, issue-433)
        getRepositoryModel(repositoryName);
    }

    /**
     * Resets the repository list cache.
     */
    @Override
    public void resetRepositoryListCache() {
        logger.info("Repository cache manually reset");
        repositoryListCache.clear();
        repositorySizeCache.clear();
        repositoryMetricsCache.clear();
        CommitCache.instance().clear();
    }

    /**
     * Calculate the checksum of settings that affect the repository list cache.
     *
     * @return a checksum
     */
    private String getRepositoryListSettingsChecksum() {
        StringBuilder ns = new StringBuilder();
        ns.append(settings.isCacheRepositoryList()).append('\n');
        ns.append(settings.isOnlyAccessBareRepositories()).append('\n');
        ns.append(settings.isSearchRepositoriesSubfolders()).append('\n');
        ns.append(settings.getSearchRecursionDepth()).append('\n');
        ns.append(settings.getSearchExclusions()).append('\n');
        String checksum = StringUtils.getSHA1(ns.toString());
        return checksum;
    }

    /**
     * Compare the last repository list setting checksum to the current checksum.
     * If different then clear the cache so that it may be rebuilt.
     *
     * @return true if the cached repository list is valid since the last check
     */
    private boolean isValidRepositoryList() {
        String newChecksum = getRepositoryListSettingsChecksum();
        boolean valid = newChecksum.equals(repositoryListSettingsChecksum.get());
        repositoryListSettingsChecksum.set(newChecksum);
        if (!valid && settings.isCacheRepositoryList()) {
            logger.info("Repository list settings have changed. Clearing repository list cache.");
            repositoryListCache.clear();
        }
        return valid;
    }

    /**
     * Returns the list of all repositories available to Gitblit. This method
     * does not consider user access permissions.
     *
     * @return list of all repositories
     */
    @Override
    public List<String> getRepositoryList() {
        if (repositoryListCache.size() == 0 || !isValidRepositoryList()) {
            // we are not caching OR we have not yet cached OR the cached list is invalid
            long startTime = System.currentTimeMillis();
            List<String> repositories = JGitUtils.getRepositoryList(repositoriesFolder,
                    settings.isOnlyAccessBareRepositories(),
                    settings.isSearchRepositoriesSubfolders(),
                    settings.getSearchRecursionDepth(),
                    settings.getSearchExclusions());

            if (!settings.isCacheRepositoryList()) {
                // we are not caching
                StringUtils.sortRepositorynames(repositories);
                return repositories;
            } else {
                // we are caching this list
                String msg = "{0} repositories identified in {1} msecs";
                if (settings.isShowRepositorySizes()) {
                    // optionally (re)calculate repository sizes
                    msg = "{0} repositories identified with calculated folder sizes in {1} msecs";
                }

                for (String repository : repositories) {
                    getRepositoryModel(repository);
                }

                // rebuild fork networks
                for (RepositoryModel model : repositoryListCache.values()) {
                    if (!StringUtils.isEmpty(model.originRepository)) {
                        String originKey = getRepositoryKey(model.originRepository);
                        if (repositoryListCache.containsKey(originKey)) {
                            RepositoryModel origin = repositoryListCache.get(originKey);
                            origin.addFork(model.name);
                        }
                    }
                }

                long duration = System.currentTimeMillis() - startTime;
                logger.info(MessageFormat.format(msg, repositoryListCache.size(), duration));
            }
        }

        // return sorted copy of cached list
        List<String> list = new ArrayList<String>();
        for (RepositoryModel model : repositoryListCache.values()) {
            list.add(model.name);
        }
        StringUtils.sortRepositorynames(list);
        return list;
    }

    /**
     * Returns the JGit repository for the specified name.
     *
     * @param repositoryName
     * @return repository or null
     */
    @Override
    public Repository getRepository(String repositoryName) {
        return getRepository(repositoryName, true);
    }

    /**
     * Returns the JGit repository for the specified name.
     *
     * @param name
     * @param logError
     * @return repository or null
     */
    @Override
    public Repository getRepository(String name, boolean logError) {
        String repositoryName = fixRepositoryName(name);
        if (isCollectingGarbage(repositoryName)) {
            logger.warn(MessageFormat.format("Rejecting request for {0}, busy collecting garbage!", repositoryName));
            return null;
        }

        File dir = FileKey.resolve(new File(repositoriesFolder, repositoryName), FS.DETECTED);
        if (dir == null) {
            return null;
        }

        Repository r = null;
        try {
            FileKey key = FileKey.exact(dir, FS.DETECTED);
            r = RepositoryCache.open(key, true);
        } catch (IOException e) {
            if (logError) {
                logger.error("GitBlit.getRepository(String) failed to find " + new File(repositoriesFolder, repositoryName).getAbsolutePath());
            }
        }
        return r;
    }

    /**
     * Returns the list of all repository models.
     *
     * @return list of all repository models
     */
    @Override
    public List<RepositoryModel> getRepositoryModels() {
        long methodStart = System.currentTimeMillis();
        List<String> list = getRepositoryList();
        List<RepositoryModel> repositories = new ArrayList<RepositoryModel>();
        for (String repo : list) {
            RepositoryModel model = getRepositoryModel(repo);
            if (model != null) {
                repositories.add(model);
            }
        }
        long duration = System.currentTimeMillis() - methodStart;
        logger.info(MessageFormat.format("{0} repository models loaded in {1} msecs", duration));
        return repositories;
    }

    /**
     * Returns the list of repository models that are accessible to the user.
     *
     * @param user
     * @return list of repository models accessible to user
     */
    @Override
    public List<RepositoryModel> getRepositoryModels(UserModel user) {
        long methodStart = System.currentTimeMillis();
        List<String> list = getRepositoryList();
        List<RepositoryModel> repositories = new ArrayList<RepositoryModel>();
        for (String repo : list) {
            RepositoryModel model = getRepositoryModel(user, repo);
            if (model != null) {
                if (!model.hasCommits) {
                    // only add empty repositories that user can push to
                    if (UserModel.ANONYMOUS.canPush(model) || user != null && user.canPush(model)) {
                        repositories.add(model);
                    }
                } else {
                    repositories.add(model);
                }
            }
        }

        long duration = System.currentTimeMillis() - methodStart;
        logger.info(MessageFormat.format("{0} repository models loaded for {1} in {2} msecs",
                repositories.size(), user == null ? "anonymous" : user.username, duration));
        return repositories;
    }

    /**
     * Returns a repository model if the repository exists and the user may
     * access the repository.
     *
     * @param user
     * @param repositoryName
     * @return repository model or null
     */
    @Override
    public RepositoryModel getRepositoryModel(UserModel user, String repositoryName) {
        RepositoryModel model = getRepositoryModel(repositoryName);
        if (model == null) {
            return null;
        }
        if (user == null) {
            user = UserModel.ANONYMOUS;
        }
        if (user.canView(model)) {
            return model;
        }
        return null;
    }

    /**
     * Returns the repository model for the specified repository. This method
     * does not consider user access permissions.
     *
     * @param name
     * @return repository model or null
     */
    @Override
    public RepositoryModel getRepositoryModel(String name) {
        String repositoryName = fixRepositoryName(name);
        String repositoryKey = getRepositoryKey(repositoryName);
        if (!repositoryListCache.containsKey(repositoryKey)) {
            RepositoryModel model = loadRepositoryModel(repositoryName);
            if (model == null) {
                return null;
            }
            addToCachedRepositoryList(model);
            return DeepCopier.copy(model);
        }

        // cached model
        RepositoryModel model = repositoryListCache.get(repositoryKey);
        if (isCollectingGarbage(model.name)) {
            // Gitblit is busy collecting garbage, use our cached model
            RepositoryModel rm = DeepCopier.copy(model);
            rm.isCollectingGarbage = true;
            return rm;
        }

        // check for updates
        Repository r = getRepository(model.name);
        if (r == null) {
            // repository is missing
            removeFromCachedRepositoryList(repositoryName);
            logger.error(MessageFormat.format("Repository \"{0}\" is missing! Removing from cache.", repositoryName));
            return null;
        }

        FileBasedConfig config = (FileBasedConfig) getRepositoryConfig(r);
        if (config.isOutdated()) {
            // reload model
            logger.debug(MessageFormat.format("Config for \"{0}\" has changed. Reloading model and updating cache.", repositoryName));
            model = loadRepositoryModel(model.name);
            removeFromCachedRepositoryList(model.name);
            addToCachedRepositoryList(model);
        } else {
            // update a few repository parameters
            if (!model.hasCommits) {
                // update hasCommits, assume a repository only gains commits :)
                model.hasCommits = JGitUtils.hasCommits(r);
            }

            updateLastChangeFields(r, model);
        }
        r.close();

        // return a copy of the cached model
        return DeepCopier.copy(model);
    }

    /**
     * Returns the star count of the repository.
     *
     * @param repository
     * @return the star count
     */
    @Override
    public long getStarCount(RepositoryModel repository) {
        long count = 0;
        for (UserModel user : userManager.getAllUsers()) {
            if (user.getPreferences().isStarredRepository(repository.name)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Replaces illegal character patterns in a repository name.
     *
     * @param repositoryName
     * @return a corrected name
     */
    private String fixRepositoryName(String repositoryName) {
        if (StringUtils.isEmpty(repositoryName)) {
            return repositoryName;
        }

        // Decode url-encoded repository name (issue-278)
        // http://stackoverflow.com/questions/17183110
        String name = repositoryName.replace("%7E", "~").replace("%7e", "~");
        name = name.replace("%2F", "/").replace("%2f", "/");

        if (name.charAt(name.length() - 1) == '/') {
            name = name.substring(0, name.length() - 1);
        }

        // strip duplicate-slashes from requests for repositoryName (ticket-117, issue-454)
        // specify first char as slash so we strip leading slashes
        char lastChar = '/';
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (c == '/' && lastChar == c) {
                continue;
            }
            sb.append(c);
            lastChar = c;
        }

        return sb.toString();
    }

    /**
     * Returns the cache key for the repository name.
     *
     * @param repositoryName
     * @return the cache key for the repository
     */
    private String getRepositoryKey(String repositoryName) {
        String name = fixRepositoryName(repositoryName);
        return StringUtils.stripDotGit(name).toLowerCase();
    }

    /**
     * Workaround JGit.  I need to access the raw config object directly in order
     * to see if the config is dirty so that I can reload a repository model.
     * If I use the stock JGit method to get the config it already reloads the
     * config.  If the config changes are made within Gitblit this is fine as
     * the returned config will still be flagged as dirty.  BUT... if the config
     * is manipulated outside Gitblit then it fails to recognize this as dirty.
     *
     * @param r
     * @return a config
     */
    private StoredConfig getRepositoryConfig(Repository r) {
        try {
            Field f = r.getClass().getDeclaredField("repoConfig");
            f.setAccessible(true);
            StoredConfig config = (StoredConfig) f.get(r);
            return config;
        } catch (Exception e) {
            logger.error("Failed to retrieve \"repoConfig\" via reflection", e);
        }
        return r.getConfig();
    }

    /**
     * Create a repository model from the configuration and repository data.
     *
     * @param repositoryName
     * @return a repositoryModel or null if the repository does not exist
     */
    private RepositoryModel loadRepositoryModel(String repositoryName) {
        Repository r = getRepository(repositoryName);
        if (r == null) {
            return null;
        }
        RepositoryModel model = new RepositoryModel();
        model.isBare = r.isBare();
        File basePath = getRepositoriesFolder();
        if (model.isBare) {
            model.name = GitFileUtils.getRelativePath(basePath, r.getDirectory());
        } else {
            model.name = GitFileUtils.getRelativePath(basePath, r.getDirectory().getParentFile());
        }
        if (StringUtils.isEmpty(model.name)) {
            // Repository is NOT located relative to the base folder because it
            // is symlinked.  Use the provided repository name.
            model.name = repositoryName;
        }
        model.projectPath = StringUtils.getFirstPathElement(repositoryName);

        StoredConfig config = r.getConfig();
        boolean hasOrigin = false;

        if (config != null) {
            // Initialize description from description file
            hasOrigin = !StringUtils.isEmpty(config.getString("remote", "origin", "url"));
            if (getConfig(config, "description", null) == null) {
                File descFile = new File(r.getDirectory(), "description");
                if (descFile.exists()) {
                    String desc = GitFileUtils.readContent(descFile, System.getProperty("line.separator"));
                    if (!desc.toLowerCase().startsWith("unnamed repository")) {
                        config.setString(Constants.CONFIG_GITBLIT, null, "description", desc);
                    }
                }
            }

            model.description = getConfig(config, "description", "");
            model.originRepository = getConfig(config, "originRepository", null);
            model.addOwners(ArrayUtils.fromString(getConfig(config, "owner", "")));
            model.acceptNewPatchsets = getConfig(config, "acceptNewPatchsets", true);
            model.acceptNewTickets = getConfig(config, "acceptNewTickets", true);
            model.requireApproval = getConfig(config, "requireApproval", settings.getBoolean(Keys.tickets.requireApproval, false));
            model.mergeTo = getConfig(config, "mergeTo", null);
            model.mergeType = MergeType.fromName(getConfig(config, "mergeType", settings.getString(Keys.tickets.mergeType, null)));
            model.useIncrementalPushTags = getConfig(config, "useIncrementalPushTags", false);
            model.incrementalPushTagPrefix = getConfig(config, "incrementalPushTagPrefix", null);
            model.allowForks = getConfig(config, "allowForks", true);
            model.accessRestriction = AccessRestrictionType.fromName(getConfig(config,
                    "accessRestriction", settings.getString(Keys.git.defaultAccessRestriction, "PUSH")));
            model.authorizationControl = AuthorizationControl.fromName(getConfig(config,
                    "authorizationControl", settings.getString(Keys.git.defaultAuthorizationControl, null)));
            model.verifyCommitter = getConfig(config, "verifyCommitter", false);
            model.showRemoteBranches = getConfig(config, "showRemoteBranches", hasOrigin);
            model.isFrozen = getConfig(config, "isFrozen", false);
            model.skipSizeCalculation = getConfig(config, "skipSizeCalculation", false);
            model.skipSummaryMetrics = getConfig(config, "skipSummaryMetrics", false);
            model.commitMessageRenderer = CommitMessageRenderer.fromName(getConfig(config, "commitMessageRenderer",
                    settings.getString(Keys.web.commitMessageRenderer, null)));
            model.federationStrategy = FederationStrategy.fromName(getConfig(config,
                    "federationStrategy", null));
            model.federationSets = new ArrayList<String>(Arrays.asList(config.getStringList(
                    Constants.CONFIG_GITBLIT, null, "federationSets")));
            model.isFederated = getConfig(config, "isFederated", false);
            model.gcThreshold = getConfig(config, "gcThreshold", settings.getString(Keys.git.defaultGarbageCollectionThreshold, "500KB"));
            model.gcPeriod = getConfig(config, "gcPeriod", settings.getInteger(Keys.git.defaultGarbageCollectionPeriod, 7));
            try {
                model.lastGC = new SimpleDateFormat(Constants.ISO8601).parse(getConfig(config, "lastGC", "1970-01-01'T'00:00:00Z"));
            } catch (Exception e) {
                model.lastGC = new Date(0);
            }
            model.maxActivityCommits = getConfig(config, "maxActivityCommits", settings.getInteger(Keys.web.maxActivityCommits, 0));
            model.origin = config.getString("remote", "origin", "url");
            if (model.origin != null) {
                model.origin = model.origin.replace('\\', '/');
                model.isMirror = config.getBoolean("remote", "origin", "mirror", false);
            }
            model.preReceiveScripts = new ArrayList<String>(Arrays.asList(config.getStringList(
                    Constants.CONFIG_GITBLIT, null, "preReceiveScript")));
            model.postReceiveScripts = new ArrayList<String>(Arrays.asList(config.getStringList(
                    Constants.CONFIG_GITBLIT, null, "postReceiveScript")));
            model.mailingLists = new ArrayList<String>(Arrays.asList(config.getStringList(
                    Constants.CONFIG_GITBLIT, null, "mailingList")));
            model.indexedBranches = new ArrayList<String>(Arrays.asList(config.getStringList(
                    Constants.CONFIG_GITBLIT, null, "indexBranch")));
            model.metricAuthorExclusions = new ArrayList<String>(Arrays.asList(config.getStringList(
                    Constants.CONFIG_GITBLIT, null, "metricAuthorExclusions")));

            // Custom defined properties
            model.customFields = new LinkedHashMap<String, String>();
            for (String aProperty : config.getNames(Constants.CONFIG_GITBLIT, Constants.CONFIG_CUSTOM_FIELDS)) {
                model.customFields.put(aProperty, config.getString(Constants.CONFIG_GITBLIT, Constants.CONFIG_CUSTOM_FIELDS, aProperty));
            }
        }
        model.HEAD = JGitUtils.getHEADRef(r);
        if (StringUtils.isEmpty(model.mergeTo)) {
            model.mergeTo = model.HEAD;
        }
        model.availableRefs = JGitUtils.getAvailableHeadTargets(r);
        model.sparkleshareId = JGitUtils.getSparkleshareId(r);
        model.hasCommits = JGitUtils.hasCommits(r);
        updateLastChangeFields(r, model);
        r.close();

        if (StringUtils.isEmpty(model.originRepository) && model.origin != null && model.origin.startsWith("file://")) {
            // repository was cloned locally... perhaps as a fork
            try {
                File folder = new File(new URI(model.origin));
                String originRepo = GitFileUtils.getRelativePath(getRepositoriesFolder(), folder);
                if (!StringUtils.isEmpty(originRepo)) {
                    // ensure origin still exists
                    File repoFolder = new File(getRepositoriesFolder(), originRepo);
                    if (repoFolder.exists()) {
                        model.originRepository = originRepo.toLowerCase();

                        // persist the fork origin
                        updateConfiguration(r, model);
                    }
                }
            } catch (URISyntaxException e) {
                logger.error("Failed to determine fork for " + model, e);
            }
        }
        return model;
    }

    /**
     * Determines if this server has the requested repository.
     *
     * @param n
     * @return true if the repository exists
     */
    @Override
    public boolean hasRepository(String repositoryName) {
        return hasRepository(repositoryName, false);
    }

    /**
     * Determines if this server has the requested repository.
     *
     * @param n
     * @param caseInsensitive
     * @return true if the repository exists
     */
    @Override
    public boolean hasRepository(String repositoryName, boolean caseSensitiveCheck) {
        if (!caseSensitiveCheck && settings.isCacheRepositoryList()) {
            // if we are caching use the cache to determine availability
            // otherwise we end up adding a phantom repository to the cache
            String key = getRepositoryKey(repositoryName);
            return repositoryListCache.containsKey(key);
        }
        Repository r = getRepository(repositoryName, false);
        if (r == null) {
            return false;
        }
        r.close();
        return true;
    }

    /**
     * Determines if the specified user has a fork of the specified origin
     * repository.
     *
     * @param username
     * @param origin
     * @return true the if the user has a fork
     */
    @Override
    public boolean hasFork(String username, String origin) {
        return getFork(username, origin) != null;
    }

    /**
     * Gets the name of a user's fork of the specified origin
     * repository.
     *
     * @param username
     * @param origin
     * @return the name of the user's fork, null otherwise
     */
    @Override
    public String getFork(String username, String origin) {
        if (StringUtils.isEmpty(origin)) {
            return null;
        }

        String userProject = ModelUtils.getPersonalPath(username);
        if (settings.isCacheRepositoryList()) {
            String originKey = getRepositoryKey(origin);
            String userPath = userProject + "/";

            // collect all origin nodes in fork network
            Set<String> roots = new HashSet<String>();
            roots.add(originKey);
            RepositoryModel originModel = repositoryListCache.get(originKey);
            while (originModel != null) {
                if (originModel.forks != null && originModel.forks.size() > 0) {
                    for (String fork : originModel.forks) {
                        if (!fork.startsWith(userPath)) {
                            roots.add(fork.toLowerCase());
                        }
                    }
                }

                if (originModel.originRepository != null) {
                    String ooKey = getRepositoryKey(originModel.originRepository);
                    roots.add(ooKey);
                    originModel = repositoryListCache.get(ooKey);
                } else {
                    // break
                    originModel = null;
                }
            }

            for (String repository : repositoryListCache.keySet()) {
                if (repository.startsWith(userPath)) {
                    RepositoryModel model = repositoryListCache.get(repository);
                    if (!StringUtils.isEmpty(model.originRepository)) {
                        String ooKey = getRepositoryKey(model.originRepository);
                        if (roots.contains(ooKey)) {
                            // user has a fork in this graph
                            return model.name;
                        }
                    }
                }
            }
        } else {
            // not caching
            File subfolder = new File(getRepositoriesFolder(), userProject);
            List<String> repositories = JGitUtils.getRepositoryList(subfolder,
                    settings.isOnlyAccessBareRepositories(),
                    settings.isSearchRepositoriesSubfolders(),
                    settings.getSearchRecursionDepth(),
                    settings.getSearchExclusions());
            for (String repository : repositories) {
                RepositoryModel model = getRepositoryModel(userProject + "/" + repository);
                if (model.originRepository != null && model.originRepository.equalsIgnoreCase(origin)) {
                    // user has a fork
                    return model.name;
                }
            }
        }
        // user does not have a fork
        return null;
    }

    /**
     * Returns the fork network for a repository by traversing up the fork graph
     * to discover the root and then down through all children of the root node.
     *
     * @param repository
     * @return a ForkModel
     */
    @Override
    public ForkModel getForkNetwork(String repository) {
        if (settings.isCacheRepositoryList()) {
            // find the root, cached
            String key = getRepositoryKey(repository);
            RepositoryModel model = repositoryListCache.get(key);
            if (model == null) {
                return null;
            }

            while (model.originRepository != null) {
                String originKey = getRepositoryKey(model.originRepository);
                model = repositoryListCache.get(originKey);
                if (model == null) {
                    return null;
                }
            }
            ForkModel root = getForkModelFromCache(model.name);
            return root;
        } else {
            // find the root, non-cached
            RepositoryModel model = getRepositoryModel(repository.toLowerCase());
            while (model.originRepository != null) {
                model = getRepositoryModel(model.originRepository);
            }
            ForkModel root = getForkModel(model.name);
            return root;
        }
    }

    private ForkModel getForkModelFromCache(String repository) {
        String key = getRepositoryKey(repository);
        RepositoryModel model = repositoryListCache.get(key);
        if (model == null) {
            return null;
        }
        ForkModel fork = new ForkModel(model);
        if (model.forks != null && !model.forks.isEmpty()) {
            for (String aFork : model.forks) {
                ForkModel fm = getForkModelFromCache(aFork);
                if (fm != null) {
                    fork.forks.add(fm);
                }
            }
        }
        return fork;
    }

    private ForkModel getForkModel(String repository) {
        RepositoryModel model = getRepositoryModel(repository.toLowerCase());
        if (model == null) {
            return null;
        }
        ForkModel fork = new ForkModel(model);
        if (model.forks != null && !model.forks.isEmpty()) {
            for (String aFork : model.forks) {
                ForkModel fm = getForkModel(aFork);
                if (fm != null) {
                    fork.forks.add(fm);
                }
            }
        }
        return fork;
    }

    /**
     * Updates the last changed fields and optionally calculates the size of the
     * repository.  Gitblit caches the repository sizes to reduce the performance
     * penalty of recursive calculation. The cache is updated if the repository
     * has been changed since the last calculation.
     *
     * @param model
     * @return size in bytes of the repository
     */
    @Override
    public long updateLastChangeFields(Repository r, RepositoryModel model) {
        LastChange lc = JGitUtils.getLastChange(r);
        model.lastChange = lc.when;
        model.lastChangeAuthor = lc.who;

        if (!settings.isShowRepositorySizes() || model.skipSizeCalculation) {
            model.size = null;
            return 0L;
        }
        if (!repositorySizeCache.hasCurrent(model.name, model.lastChange)) {
            File gitDir = r.getDirectory();
            long sz = GitFileUtils.folderSize(gitDir);
            repositorySizeCache.updateObject(model.name, model.lastChange, sz);
        }
        long size = repositorySizeCache.getObject(model.name);
        ByteFormat byteFormat = new ByteFormat();
        model.size = byteFormat.format(size);
        return size;
    }

    /**
     * Returns true if the repository is idle (not being accessed).
     *
     * @param repository
     * @return true if the repository is idle
     */
    @Override
    public boolean isIdle(Repository repository) {
        try {
            // Read the use count.
            // An idle use count is 2:
            // +1 for being in the cache
            // +1 for the repository parameter in this method
            Field useCnt = Repository.class.getDeclaredField("useCnt");
            useCnt.setAccessible(true);
            int useCount = ((AtomicInteger) useCnt.get(repository)).get();
            return useCount == 2;
        } catch (Exception e) {
            logger.warn(MessageFormat.format("Failed to reflectively determine use count for repository {0}",
                            repository.getDirectory().getPath()), e);
        }
        return false;
    }

    /**
     * Ensures that all cached repository are completely closed and their resources
     * are properly released.
     */
    @Override
    public void closeAll() {
        for (String repository : getRepositoryList()) {
            close(repository);
        }
    }

    /**
     * Ensure that a cached repository is completely closed and its resources
     * are properly released.
     *
     * @param repositoryName
     */
    @Override
    public void close(String repositoryName) {
        Repository repository = getRepository(repositoryName);
        if (repository == null) {
            return;
        }
        RepositoryCache.close(repository);

        // assume 2 uses in case reflection fails
        int uses = 2;
        try {
            // The FileResolver caches repositories which is very useful
            // for performance until you want to delete a repository.
            // I have to use reflection to call close() the correct
            // number of times to ensure that the object and ref databases
            // are properly closed before I can delete the repository from
            // the filesystem.
            Field useCnt = Repository.class.getDeclaredField("useCnt");
            useCnt.setAccessible(true);
            uses = ((AtomicInteger) useCnt.get(repository)).get();
        } catch (Exception e) {
            logger.warn(MessageFormat.format("Failed to reflectively determine use count for repository {0}", repositoryName), e);
        }

        if (uses > 0) {
            logger.debug(MessageFormat.format("{0}.useCnt={1}, calling close() {2} time(s) to close object and ref databases",
                            repositoryName, uses, uses));
            for (int i = 0; i < uses; i++) {
                repository.close();
            }
        }

    }

    /**
     * Returns the metrics for the default branch of the specified repository.
     * This method builds a metrics cache. The cache is updated if the
     * repository is updated. A new copy of the metrics list is returned on each
     * call so that modifications to the list are non-destructive.
     *
     * @param model
     * @param repository
     * @return a new array list of metrics
     */
    @Override
    public List<Metric> getRepositoryDefaultMetrics(RepositoryModel model, Repository repository) {
        if (repositoryMetricsCache.hasCurrent(model.name, model.lastChange)) {
            return new ArrayList<Metric>(repositoryMetricsCache.getObject(model.name));
        }
        List<Metric> metrics = MetricUtils.getDateMetrics(repository, null, true, null, TimeZone.getDefault());
        repositoryMetricsCache.updateObject(model.name, model.lastChange, metrics);
        return new ArrayList<Metric>(metrics);
    }

    /**
     * Returns the gitblit string value for the specified key. If key is not
     * set, returns defaultValue.
     *
     * @param config
     * @param field
     * @param defaultValue
     * @return field value or defaultValue
     */
    private String getConfig(StoredConfig config, String field, String defaultValue) {
        String value = config.getString(Constants.CONFIG_GITBLIT, null, field);
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        }
        return value;
    }

    /**
     * Returns the gitblit boolean value for the specified key. If key is not
     * set, returns defaultValue.
     *
     * @param config
     * @param field
     * @param defaultValue
     * @return field value or defaultValue
     */
    private boolean getConfig(StoredConfig config, String field, boolean defaultValue) {
        return config.getBoolean(Constants.CONFIG_GITBLIT, field, defaultValue);
    }

    /**
     * Returns the gitblit string value for the specified key. If key is not
     * set, returns defaultValue.
     *
     * @param config
     * @param field
     * @param defaultValue
     * @return field value or defaultValue
     */
    private int getConfig(StoredConfig config, String field, int defaultValue) {
        String value = config.getString(Constants.CONFIG_GITBLIT, null, field);
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
        }
        return defaultValue;
    }

    /**
     * Creates/updates the repository model keyed by repositoryName. Saves all
     * repository settings in .git/config. This method allows for renaming
     * repositories and will update user access permissions accordingly.
     * <p>
     * All repositories created by this method are bare and automatically have
     * .git appended to their names, which is the standard convention for bare
     * repositories.
     *
     * @param repositoryName
     * @param repository
     * @param isCreate
     * @throws GitException
     */
    @Override
    public void updateRepositoryModel(String repositoryName, RepositoryModel repository, boolean isCreate) throws GitException {
        if (isCollectingGarbage(repositoryName)) {
            throw new GitException(MessageFormat.format("sorry, Gitblit is busy collecting garbage in {0}", repositoryName));
        }

        Repository r = null;
        String projectPath = StringUtils.getFirstPathElement(repository.name);
        if (!StringUtils.isEmpty(projectPath)) {
            if (projectPath.equalsIgnoreCase(settings.getRepositoryRootGroupName())) {
                // strip leading group name
                repository.name = repository.name.substring(projectPath.length() + 1);
            }
        }

        boolean isRename = false;
        if (isCreate) {
            // ensure created repository name ends with .git
            if (!repository.name.toLowerCase().endsWith(org.eclipse.jgit.lib.Constants.DOT_GIT_EXT)) {
                repository.name += org.eclipse.jgit.lib.Constants.DOT_GIT_EXT;
            }
            if (hasRepository(repository.name)) {
                throw new GitException(MessageFormat.format("Can not create repository ''{0}'' because it already exists.", repository.name));
            }
            // create repository
            logger.info("create repository " + repository.name);
            String shared = settings.isCreateRepositoriesShared() ? "TRUE" : "FALSE";
            r = JGitUtils.createRepository(repositoriesFolder, repository.name, shared);
        } else {
            // rename repository
            isRename = !repositoryName.equalsIgnoreCase(repository.name);
            if (isRename) {
                if (!repository.name.toLowerCase().endsWith(org.eclipse.jgit.lib.Constants.DOT_GIT_EXT)) {
                    repository.name += org.eclipse.jgit.lib.Constants.DOT_GIT_EXT;
                }

                if (new File(repositoriesFolder, repository.name).exists()) {
                    throw new GitException(MessageFormat.format("Failed to rename ''{0}'' because ''{1}'' already exists.",
                            repositoryName, repository.name));
                }

                close(repositoryName);
                File folder = new File(repositoriesFolder, repositoryName);
                File destFolder = new File(repositoriesFolder, repository.name);
                if (destFolder.exists()) {
                    throw new GitException(MessageFormat.format("Can not rename repository ''{0}'' to ''{1}'' because ''{1}'' already exists.",
                            repositoryName, repository.name));
                }
                File parentFile = destFolder.getParentFile();
                if (!parentFile.exists() && !parentFile.mkdirs()) {
                    throw new GitException(MessageFormat.format("Failed to create folder ''{0}''", parentFile.getAbsolutePath()));
                }
                if (!folder.renameTo(destFolder)) {
                    throw new GitException(MessageFormat.format("Failed to rename repository ''{0}'' to ''{1}''.",
                            repositoryName, repository.name));
                }
                // rename the roles
                if (!userManager.renameRepositoryRole(repositoryName, repository.name)) {
                    throw new GitException(MessageFormat.format("Failed to rename repository permissions ''{0}'' to ''{1}''.",
                            repositoryName, repository.name));
                }

                // rename fork origins in their configs
                if (repository.forks != null && repository.forks.size() > 0) {
                    for (String fork : repository.forks) {
                        Repository rf = getRepository(fork);
                        try {
                            StoredConfig config = rf.getConfig();
                            String origin = config.getString("remote", "origin", "url");
                            origin = origin.replace(repositoryName, repository.name);
                            config.setString("remote", "origin", "url", origin);
                            config.setString(Constants.CONFIG_GITBLIT, null, "originRepository", repository.name);
                            config.save();
                        } catch (Exception e) {
                            logger.error("Failed to update repository fork config for " + fork, e);
                        }
                        rf.close();
                    }
                }

                // update this repository's origin's fork list
                if (!StringUtils.isEmpty(repository.originRepository)) {
                    String originKey = getRepositoryKey(repository.originRepository);
                    RepositoryModel origin = repositoryListCache.get(originKey);
                    if (origin != null && origin.forks != null && origin.forks.size() > 0) {
                        origin.forks.remove(repositoryName);
                        origin.forks.add(repository.name);
                    }
                }

                // clear the cache
                clearRepositoryMetadataCache(repositoryName);
                repository.resetDisplayName();
            }

            // load repository
            logger.info("edit repository " + repository.name);
            r = getRepository(repository.name);
        }

        // update settings
        if (r != null) {
            updateConfiguration(r, repository);
            // Update the description file
            File descFile = new File(r.getDirectory(), "description");
            if (repository.description != null) {
                GitFileUtils.writeContent(descFile, repository.description);
            } else if (descFile.exists() && !descFile.isDirectory()) {
                descFile.delete();
            }
            // only update symbolic head if it changes
            String currentRef = JGitUtils.getHEADRef(r);
            if (!StringUtils.isEmpty(repository.HEAD) && !repository.HEAD.equals(currentRef)) {
                logger.info(MessageFormat.format("Relinking {0} HEAD from {1} to {2}", repository.name, currentRef, repository.HEAD));
                if (JGitUtils.setHEADtoRef(r, repository.HEAD)) {
                    // clear the cache
                    clearRepositoryMetadataCache(repository.name);
                }
            }

            // Adjust permissions in case we updated the config files
            String shared = settings.isCreateRepositoriesShared() ? "TRUE" : "FALSE";
            JGitUtils.adjustSharedPerm(new File(r.getDirectory().getAbsolutePath(), "config"), shared);
            JGitUtils.adjustSharedPerm(new File(r.getDirectory().getAbsolutePath(), "HEAD"), shared);

            // close the repository object
            r.close();
        }

        // update repository cache
        removeFromCachedRepositoryList(repositoryName);
        // model will actually be replaced on next load because config is stale
        addToCachedRepositoryList(repository);
    }

    /**
     * Updates the Gitblit configuration for the specified repository.
     *
     * @param r          the Git repository
     * @param repository the Gitblit repository model
     */
    @Override
    public void updateConfiguration(Repository r, RepositoryModel repository) {
        StoredConfig config = r.getConfig();
        config.setString(Constants.CONFIG_GITBLIT, null, "description", repository.description);
        config.setString(Constants.CONFIG_GITBLIT, null, "originRepository", repository.originRepository);
        config.setString(Constants.CONFIG_GITBLIT, null, "owner", ArrayUtils.toString(repository.owners));
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "acceptNewPatchsets", repository.acceptNewPatchsets);
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "acceptNewTickets", repository.acceptNewTickets);

        if (settings.getBoolean(Keys.tickets.requireApproval, false) == repository.requireApproval) {
            // use default
            config.unset(Constants.CONFIG_GITBLIT, null, "requireApproval");
        } else {
            // override default
            config.setBoolean(Constants.CONFIG_GITBLIT, null, "requireApproval", repository.requireApproval);
        }
        if (!StringUtils.isEmpty(repository.mergeTo)) {
            config.setString(Constants.CONFIG_GITBLIT, null, "mergeTo", repository.mergeTo);
        }
        if (repository.mergeType == null || repository.mergeType == MergeType.fromName(settings.getMergeType())) {
            // use default
            config.unset(Constants.CONFIG_GITBLIT, null, "mergeType");
        } else {
            // override default
            config.setString(Constants.CONFIG_GITBLIT, null, "mergeType", repository.mergeType.name());
        }
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "useIncrementalPushTags", repository.useIncrementalPushTags);
        if (StringUtils.isEmpty(repository.incrementalPushTagPrefix) ||
                repository.incrementalPushTagPrefix.equals(settings.getDefaultIncrementalPushTagPrefix())) {
            config.unset(Constants.CONFIG_GITBLIT, null, "incrementalPushTagPrefix");
        } else {
            config.setString(Constants.CONFIG_GITBLIT, null, "incrementalPushTagPrefix", repository.incrementalPushTagPrefix);
        }
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "allowForks", repository.allowForks);
        config.setString(Constants.CONFIG_GITBLIT, null, "accessRestriction", repository.accessRestriction.name());
        config.setString(Constants.CONFIG_GITBLIT, null, "authorizationControl", repository.authorizationControl.name());
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "verifyCommitter", repository.verifyCommitter);
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "showRemoteBranches", repository.showRemoteBranches);
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "isFrozen", repository.isFrozen);
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "skipSizeCalculation", repository.skipSizeCalculation);
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "skipSummaryMetrics", repository.skipSummaryMetrics);
        config.setString(Constants.CONFIG_GITBLIT, null, "federationStrategy", repository.federationStrategy.name());
        config.setBoolean(Constants.CONFIG_GITBLIT, null, "isFederated", repository.isFederated);
        config.setString(Constants.CONFIG_GITBLIT, null, "gcThreshold", repository.gcThreshold);
        if (repository.gcPeriod == settings.getDefaultGarbageCollectionPeriod()) {
            // use default from config
            config.unset(Constants.CONFIG_GITBLIT, null, "gcPeriod");
        } else {
            config.setInt(Constants.CONFIG_GITBLIT, null, "gcPeriod", repository.gcPeriod);
        }
        if (repository.lastGC != null) {
            config.setString(Constants.CONFIG_GITBLIT, null, "lastGC", new SimpleDateFormat(Constants.ISO8601).format(repository.lastGC));
        }
        if (repository.maxActivityCommits == settings.getMaxActivityCommits()) {
            // use default from config
            config.unset(Constants.CONFIG_GITBLIT, null, "maxActivityCommits");
        } else {
            config.setInt(Constants.CONFIG_GITBLIT, null, "maxActivityCommits", repository.maxActivityCommits);
        }

        CommitMessageRenderer defaultRenderer = CommitMessageRenderer.fromName(settings.getCommitMessageRenderer());
        if (repository.commitMessageRenderer == null || repository.commitMessageRenderer == defaultRenderer) {
            // use default from config
            config.unset(Constants.CONFIG_GITBLIT, null, "commitMessageRenderer");
        } else {
            // repository overrides default
            config.setString(Constants.CONFIG_GITBLIT, null, "commitMessageRenderer", repository.commitMessageRenderer.name());
        }

        updateList(config, "federationSets", repository.federationSets);
        updateList(config, "preReceiveScript", repository.preReceiveScripts);
        updateList(config, "postReceiveScript", repository.postReceiveScripts);
        updateList(config, "mailingList", repository.mailingLists);
        updateList(config, "indexBranch", repository.indexedBranches);
        updateList(config, "metricAuthorExclusions", repository.metricAuthorExclusions);

        // User Defined Properties
        if (repository.customFields != null) {
            if (repository.customFields.size() == 0) {
                // clear section
                config.unsetSection(Constants.CONFIG_GITBLIT, Constants.CONFIG_CUSTOM_FIELDS);
            } else {
                for (Entry<String, String> property : repository.customFields.entrySet()) {
                    // set field
                    String key = property.getKey();
                    String value = property.getValue();
                    config.setString(Constants.CONFIG_GITBLIT, Constants.CONFIG_CUSTOM_FIELDS, key, value);
                }
            }
        }

        try {
            config.save();
        } catch (IOException e) {
            logger.error("Failed to save repository config!", e);
        }
    }

    private void updateList(StoredConfig config, String field, List<String> list) {
        // a null list is skipped, not cleared
        // this is for RPC administration where an older manager might be used
        if (list == null) {
            return;
        }
        if (list == null || list.isEmpty()) {
            config.unset(Constants.CONFIG_GITBLIT, null, field);
        } else {
            config.setStringList(Constants.CONFIG_GITBLIT, null, field, list);
        }
    }

    /**
     * Returns true if the repository can be deleted.
     *
     * @return true if the repository can be deleted
     */
    @Override
    public boolean canDelete(RepositoryModel repository) {
        return settings.isAllowDeletingNonEmptyRepositories() || !repository.hasCommits;
    }

    /**
     * Deletes the repository from the file system and removes the repository
     * permission from all repository users.
     *
     * @param model
     * @return true if successful
     */
    @Override
    public boolean deleteRepositoryModel(RepositoryModel model) {
        return deleteRepository(model.name);
    }

    /**
     * Deletes the repository from the file system and removes the repository
     * permission from all repository users.
     *
     * @param repositoryName
     * @return true if successful
     */
    @Override
    public boolean deleteRepository(String repositoryName) {
        RepositoryModel repository = getRepositoryModel(repositoryName);
        if (!canDelete(repository)) {
            logger.warn("Attempt to delete {} rejected!", repositoryName);
            return false;
        }

        try {
            close(repositoryName);
            // clear the repository cache
            clearRepositoryMetadataCache(repositoryName);

            RepositoryModel model = removeFromCachedRepositoryList(repositoryName);
            if (model != null && model.forks != null && model.forks.size() > 0) {
                resetRepositoryListCache();
            }

            File folder = new File(repositoriesFolder, repositoryName);
            if (folder.exists() && folder.isDirectory()) {
                FileUtils.delete(folder, FileUtils.RECURSIVE | FileUtils.RETRY);
                if (userManager.deleteRepositoryRole(repositoryName)) {
                    logger.info(MessageFormat.format("Repository \"{0}\" deleted", repositoryName));
                    return true;
                }
            }
        } catch (Throwable t) {
            logger.error(MessageFormat.format("Failed to delete repository {0}", repositoryName), t);
        }
        return false;
    }

    protected void configureGarbageCollector() {
        // schedule gc engine
        gcExecutor = new GarbageCollectorService(settings, this);
        if (gcExecutor.isReady()) {
            logger.info("Garbage Collector (GC) will scan repositories every 24 hours.");
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, settings.getGarbageCollectionHour());
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            Date cd = c.getTime();
            Date now = new Date();
            int delay = 0;
            if (cd.before(now)) {
                c.add(Calendar.DATE, 1);
                cd = c.getTime();
            }
            delay = (int) ((cd.getTime() - now.getTime()) / TimeUtils.MIN);
            String when = delay + " mins";
            if (delay > 60) {
                when = MessageFormat.format("{0,number,0.0} hours", delay / 60f);
            }
            logger.info(MessageFormat.format("Next scheculed GC scan is in {0}", when));
            scheduledExecutor.scheduleAtFixedRate(gcExecutor, delay, 60 * 24, TimeUnit.MINUTES);
        } else {
            logger.info("Garbage Collector (GC) is disabled.");
        }
    }

    protected void configureMirrorExecutor() {
        mirrorExecutor = new MirrorService(settings, this);
        if (mirrorExecutor.isReady()) {
            int mins = TimeUtils.convertFrequencyToMinutes(settings.getString(Keys.git.mirrorPeriod, "30 mins"), 5);
            int delay = 1;
            scheduledExecutor.scheduleAtFixedRate(mirrorExecutor, delay, mins, TimeUnit.MINUTES);
            logger.info("Mirror service will fetch updates every {} minutes.", mins);
            logger.info("Next scheduled mirror fetch is in {} minutes", delay);
        } else {
            logger.info("Mirror service is disabled.");
        }
    }

    protected void configureJGit() {
        // Configure JGit
        WindowCacheConfig cfg = new WindowCacheConfig();

        cfg.setPackedGitWindowSize(settings.getFilesize(Keys.git.packedGitWindowSize, cfg.getPackedGitWindowSize()));
        cfg.setPackedGitLimit(settings.getFilesize(Keys.git.packedGitLimit, cfg.getPackedGitLimit()));
        cfg.setDeltaBaseCacheLimit(settings.getFilesize(Keys.git.deltaBaseCacheLimit, cfg.getDeltaBaseCacheLimit()));
        cfg.setPackedGitOpenFiles(settings.getPackedGitOpenFiles());
        cfg.setPackedGitMMAP(settings.isPackedGitMmap());

        try {
            cfg.install();
            logger.debug(MessageFormat.format("{0} = {1,number,0}", Keys.git.packedGitWindowSize, cfg.getPackedGitWindowSize()));
            logger.debug(MessageFormat.format("{0} = {1,number,0}", Keys.git.packedGitLimit, cfg.getPackedGitLimit()));
            logger.debug(MessageFormat.format("{0} = {1,number,0}", Keys.git.deltaBaseCacheLimit, cfg.getDeltaBaseCacheLimit()));
            logger.debug(MessageFormat.format("{0} = {1,number,0}", Keys.git.packedGitOpenFiles, cfg.getPackedGitOpenFiles()));
            logger.debug(MessageFormat.format("{0} = {1}", Keys.git.packedGitMmap, cfg.isPackedGitMMAP()));
        } catch (IllegalArgumentException e) {
            logger.error("Failed to configure JGit parameters!", e);
        }
    }

    protected void configureCommitCache() {
        final int daysToCache = settings.getActivityCacheDays();
        if (daysToCache <= 0) {
            logger.info("Commit cache is disabled");
            return;
        }

        logger.info(MessageFormat.format("Preparing {0} day commit cache...", daysToCache));
        CommitCache.instance().setCacheDays(daysToCache);
        Thread loader = new Thread() {
            @Override
            public void run() {
                long start = System.nanoTime();
                long repoCount = 0;
                long commitCount = 0;
                Date cutoff = CommitCache.instance().getCutoffDate();
                for (String repositoryName : getRepositoryList()) {
                    RepositoryModel model = getRepositoryModel(repositoryName);
                    if (model != null && model.hasCommits && model.lastChange.after(cutoff)) {
                        repoCount++;
                        Repository repository = getRepository(repositoryName);
                        for (RefModel ref : JGitUtils.getLocalBranches(repository, true, -1)) {
                            if (!ref.getDate().after(cutoff)) {
                                // branch not recently updated
                                continue;
                            }
                            List<?> commits = CommitCache.instance().getCommits(repositoryName, repository, ref.getName());
                            if (commits.size() > 0) {
                                logger.info(MessageFormat.format("  cached {0} commits for {1}:{2}", commits.size(), repositoryName, ref.getName()));
                                commitCount += commits.size();
                            }
                        }
                        repository.close();
                    }
                }
                logger.info(MessageFormat.format("built {0} day commit cache of {1} commits across {2} repositories in {3} msecs",
                        daysToCache, commitCount, repoCount, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            }
        };
        loader.setName("CommitCacheLoader");
        loader.setDaemon(true);
        loader.start();
    }

    protected void confirmWriteAccess() {
        try {
            if (!getRepositoriesFolder().exists()) {
                getRepositoriesFolder().mkdirs();
            }
            File file = File.createTempFile(".test-", ".txt", getRepositoriesFolder());
            file.delete();
        } catch (Exception e) {
            logger.error("");
            logger.error(Constants.BORDER2);
            logger.error("Please check filesystem permissions!");
            logger.error("FAILED TO WRITE TO REPOSITORIES FOLDER!!", e);
            logger.error(Constants.BORDER2);
            logger.error("");
        }
    }
}
