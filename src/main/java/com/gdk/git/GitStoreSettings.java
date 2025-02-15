package com.gdk.git;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GitStoreSettings {
    private File repositoriesFolder;
    private boolean createRepositoriesShared = false;
    private boolean cacheRepositoryList = true;
    private boolean searchRepositoriesSubfolders = true;
    private int searchRecursionDepth = -1;
    private List<String> searchExclusions = new ArrayList<>();

    private boolean allowCreateOnPush = true;
    private boolean allowAnonymousPushes = false;

    private String userRepositoryPrefix = "~";
    private String defaultIncrementalPushTagPrefix = "r";

    private boolean enableGarbageCollection = false;
    private int garbageCollectionHour = 0;
    private String defaultGarbageCollectionThreshold = "500k";
    private int defaultGarbageCollectionPeriod = 7;

    private boolean enableMirroring = false;

    private boolean onlyAccessBareRepositories = false;
    private boolean allowDeletingNonEmptyRepositories = true;

    private boolean showRepositorySizes = true;

    private String repositoryRootGroupName = "main";

    private int activityCacheDays = 14;

    private String packedGitWindowSize = "8k";
    private String packedGitLimit = "10m";
    private String deltaBaseCacheLimit = "10m";
    private int packedGitOpenFiles = 128;
    private boolean packedGitMmap = false;
    private boolean checkReceivedObjects = true;
    private boolean checkReferencedObjectsAreReachable = true;
    private int maxObjectSizeLimit = 0;
    private int maxPackSizeLimit = -1;

    private boolean requireTicketsApproval = false;
    private String mergeType = "MERGE_ALWAYS";

    private int maxActivityCommits = 0;

    private String commitMessageRenderer = "plain";

    public File getRepositoriesFolder() {
        return repositoriesFolder;
    }

    public void setRepositoriesFolder(File repositoriesFolder) {
        this.repositoriesFolder = repositoriesFolder;
    }

    public boolean isCreateRepositoriesShared() {
        return createRepositoriesShared;
    }

    public void setCreateRepositoriesShared(boolean createRepositoriesShared) {
        this.createRepositoriesShared = createRepositoriesShared;
    }

    public boolean isCacheRepositoryList() {
        return cacheRepositoryList;
    }

    public void setCacheRepositoryList(boolean cacheRepositoryList) {
        this.cacheRepositoryList = cacheRepositoryList;
    }

    public boolean isSearchRepositoriesSubfolders() {
        return searchRepositoriesSubfolders;
    }

    public void setSearchRepositoriesSubfolders(boolean searchRepositoriesSubfolders) {
        this.searchRepositoriesSubfolders = searchRepositoriesSubfolders;
    }

    public int getSearchRecursionDepth() {
        return searchRecursionDepth;
    }

    public void setSearchRecursionDepth(int searchRecursionDepth) {
        this.searchRecursionDepth = searchRecursionDepth;
    }

    public List<String> getSearchExclusions() {
        return searchExclusions;
    }

    public void addSearchExclusions(String... searchExclusions) {
        if (searchExclusions != null && searchExclusions.length > 0) {
            for (String searchExclusion : searchExclusions) {
                this.searchExclusions.add(searchExclusion);
            }
        }
    }

    public boolean isAllowCreateOnPush() {
        return allowCreateOnPush;
    }

    public void setAllowCreateOnPush(boolean allowCreateOnPush) {
        this.allowCreateOnPush = allowCreateOnPush;
    }

    public boolean isAllowAnonymousPushes() {
        return allowAnonymousPushes;
    }

    public void setAllowAnonymousPushes(boolean allowAnonymousPushes) {
        this.allowAnonymousPushes = allowAnonymousPushes;
    }

    public String getUserRepositoryPrefix() {
        return userRepositoryPrefix;
    }

    public void setUserRepositoryPrefix(String userRepositoryPrefix) {
        this.userRepositoryPrefix = userRepositoryPrefix;
    }

    public String getDefaultIncrementalPushTagPrefix() {
        return defaultIncrementalPushTagPrefix;
    }

    public void setDefaultIncrementalPushTagPrefix(String defaultIncrementalPushTagPrefix) {
        this.defaultIncrementalPushTagPrefix = defaultIncrementalPushTagPrefix;
    }

    public boolean isEnableGarbageCollection() {
        return enableGarbageCollection;
    }

    public void setEnableGarbageCollection(boolean enableGarbageCollection) {
        this.enableGarbageCollection = enableGarbageCollection;
    }

    public int getGarbageCollectionHour() {
        return garbageCollectionHour;
    }

    public void setGarbageCollectionHour(int garbageCollectionHour) {
        this.garbageCollectionHour = garbageCollectionHour;
    }

    public String getDefaultGarbageCollectionThreshold() {
        return defaultGarbageCollectionThreshold;
    }

    public void setDefaultGarbageCollectionThreshold(String defaultGarbageCollectionThreshold) {
        this.defaultGarbageCollectionThreshold = defaultGarbageCollectionThreshold;
    }

    public int getDefaultGarbageCollectionPeriod() {
        return defaultGarbageCollectionPeriod;
    }

    public void setDefaultGarbageCollectionPeriod(int defaultGarbageCollectionPeriod) {
        this.defaultGarbageCollectionPeriod = defaultGarbageCollectionPeriod;
    }

    public boolean isEnableMirroring() {
        return enableMirroring;
    }

    public void setEnableMirroring(boolean enableMirroring) {
        this.enableMirroring = enableMirroring;
    }

    public boolean isOnlyAccessBareRepositories() {
        return onlyAccessBareRepositories;
    }

    public void setOnlyAccessBareRepositories(boolean onlyAccessBareRepositories) {
        this.onlyAccessBareRepositories = onlyAccessBareRepositories;
    }

    public boolean isShowRepositorySizes() {
        return showRepositorySizes;
    }

    public void setShowRepositorySizes(boolean showRepositorySizes) {
        this.showRepositorySizes = showRepositorySizes;
    }

    public String getRepositoryRootGroupName() {
        return repositoryRootGroupName;
    }

    public void setRepositoryRootGroupName(String repositoryRootGroupName) {
        this.repositoryRootGroupName = repositoryRootGroupName;
    }

    public boolean isAllowDeletingNonEmptyRepositories() {
        return allowDeletingNonEmptyRepositories;
    }

    public void setAllowDeletingNonEmptyRepositories(boolean allowDeletingNonEmptyRepositories) {
        this.allowDeletingNonEmptyRepositories = allowDeletingNonEmptyRepositories;
    }

    public int getActivityCacheDays() {
        return activityCacheDays;
    }

    public void setActivityCacheDays(int activityCacheDays) {
        this.activityCacheDays = activityCacheDays;
    }

    public String getPackedGitWindowSize() {
        return packedGitWindowSize;
    }

    public void setPackedGitWindowSize(String packedGitWindowSize) {
        this.packedGitWindowSize = packedGitWindowSize;
    }

    public String getPackedGitLimit() {
        return packedGitLimit;
    }

    public void setPackedGitLimit(String packedGitLimit) {
        this.packedGitLimit = packedGitLimit;
    }

    public String getDeltaBaseCacheLimit() {
        return deltaBaseCacheLimit;
    }

    public void setDeltaBaseCacheLimit(String deltaBaseCacheLimit) {
        this.deltaBaseCacheLimit = deltaBaseCacheLimit;
    }

    public int getPackedGitOpenFiles() {
        return packedGitOpenFiles;
    }

    public void setPackedGitOpenFiles(int packedGitOpenFiles) {
        this.packedGitOpenFiles = packedGitOpenFiles;
    }

    public boolean isPackedGitMmap() {
        return packedGitMmap;
    }

    public void setPackedGitMmap(boolean packedGitMmap) {
        this.packedGitMmap = packedGitMmap;
    }

    public boolean isCheckReceivedObjects() {
        return checkReceivedObjects;
    }

    public void setCheckReceivedObjects(boolean checkReceivedObjects) {
        this.checkReceivedObjects = checkReceivedObjects;
    }

    public boolean isCheckReferencedObjectsAreReachable() {
        return checkReferencedObjectsAreReachable;
    }

    public void setCheckReferencedObjectsAreReachable(boolean checkReferencedObjectsAreReachable) {
        this.checkReferencedObjectsAreReachable = checkReferencedObjectsAreReachable;
    }

    public int getMaxObjectSizeLimit() {
        return maxObjectSizeLimit;
    }

    public void setMaxObjectSizeLimit(int maxObjectSizeLimit) {
        this.maxObjectSizeLimit = maxObjectSizeLimit;
    }

    public int getMaxPackSizeLimit() {
        return maxPackSizeLimit;
    }

    public void setMaxPackSizeLimit(int maxPackSizeLimit) {
        this.maxPackSizeLimit = maxPackSizeLimit;
    }

    public boolean isRequireTicketsApproval() {
        return requireTicketsApproval;
    }

    public void setRequireTicketsApproval(boolean requireTicketsApproval) {
        this.requireTicketsApproval = requireTicketsApproval;
    }

    public String getMergeType() {
        return mergeType;
    }

    public void setMergeType(String mergeType) {
        this.mergeType = mergeType;
    }

    public int getMaxActivityCommits() {
        return maxActivityCommits;
    }

    public void setMaxActivityCommits(int maxActivityCommits) {
        this.maxActivityCommits = maxActivityCommits;
    }

    public String getCommitMessageRenderer() {
        return commitMessageRenderer;
    }

    public void setCommitMessageRenderer(String commitMessageRenderer) {
        this.commitMessageRenderer = commitMessageRenderer;
    }

}
