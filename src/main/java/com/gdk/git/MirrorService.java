package com.gdk.git;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.ReceiveCommand.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Mirror service handles periodic fetching of mirrored repositories.
 *
 * @author James Moger
 *
 */
public class MirrorService implements Runnable {

	private final Logger logger = LoggerFactory.getLogger(MirrorService.class);

	private final Set<String> repairAttempted = Collections.synchronizedSet(new HashSet<String>());

	private final GitStoreSettings settings;

	private final IRepositoryManager repositoryManager;

	private AtomicBoolean running = new AtomicBoolean(false);

	private AtomicBoolean forceClose = new AtomicBoolean(false);

	private final UserModel gitblitUser;

	public MirrorService(GitStoreSettings settings, IRepositoryManager repositoryManager) {
		this.settings = settings;
		this.repositoryManager = repositoryManager;
		this.gitblitUser = new UserModel("gdkgit");
		this.gitblitUser.displayName = "GDKGit";
	}

	public boolean isReady() {
		return settings.isEnableMirroring();
	}

	public boolean isRunning() {
		return running.get();
	}

	public void close() {
		forceClose.set(true);
	}

	@Override
	public void run() {
		if (!isReady()) {
			return;
		}

		running.set(true);

		for (String repositoryName : repositoryManager.getRepositoryList()) {
			if (forceClose.get()) {
				break;
			}
			if (repositoryManager.isCollectingGarbage(repositoryName)) {
				logger.debug("mirror is skipping {} garbagecollection", repositoryName);
				continue;
			}
			RepositoryModel model = null;
			Repository repository = null;
			try {
				model = repositoryManager.getRepositoryModel(repositoryName);
				if (!model.isMirror && !model.isBare) {
					// repository must be a valid bare git mirror
					logger.debug("mirror is skipping {} !mirror !bare", repositoryName);
					continue;
				}

				repository = repositoryManager.getRepository(repositoryName);
				if (repository == null) {
					logger.warn("MirrorExecutor is missing repository {}?!?", repositoryName);
					continue;
				}

				// automatically repair (some) invalid fetch ref specs
				if (!repairAttempted.contains(repositoryName)) {
					repairAttempted.add(repositoryName);
					JGitUtils.repairFetchSpecs(repository);
				}

				// find the first mirror remote - there should only be one
				StoredConfig rc = repository.getConfig();
				RemoteConfig mirror = null;
				List<RemoteConfig> configs = RemoteConfig.getAllRemoteConfigs(rc);
				for (RemoteConfig config : configs) {
					if (config.isMirror()) {
						mirror = config;
						break;
					}
				}

				if (mirror == null) {
					// repository does not have a mirror remote
					logger.debug("mirror is skipping {} no mirror remote found", repositoryName);
					continue;
				}

				logger.debug("checking {} remote {} for ref updates", repositoryName, mirror.getName());
				final boolean testing = false;
				Git git = new Git(repository);
				CredentialsProvider creds = null;
				URIish fetchUri = mirror.getURIs().get(0);
				if (fetchUri.getUser() != null && fetchUri.getPass() != null) {
				    creds = new UsernamePasswordCredentialsProvider(fetchUri.getUser(), fetchUri.getPass());
				}
				FetchResult result = git.fetch().setCredentialsProvider(creds).setRemote(mirror.getName()).setDryRun(testing).call();
				Collection<TrackingRefUpdate> refUpdates = result.getTrackingRefUpdates();
				if (refUpdates.size() > 0) {
					ReceiveCommand ticketBranchCmd = null;
					for (TrackingRefUpdate ru : refUpdates) {
						StringBuilder sb = new StringBuilder();
						sb.append("updated mirror ");
						sb.append(repositoryName);
						sb.append(" ");
						sb.append(ru.getRemoteName());
						sb.append(" -> ");
						sb.append(ru.getLocalName());
						if (ru.getResult() == Result.FORCED) {
							sb.append(" (forced)");
						}
						sb.append(" ");
						sb.append(ru.getOldObjectId() == null ? "" : ru.getOldObjectId().abbreviate(7).name());
						sb.append("..");
						sb.append(ru.getNewObjectId() == null ? "" : ru.getNewObjectId().abbreviate(7).name());
						logger.info(sb.toString());

						if ("refs/meta/gitblit/tickets".equals(ru.getLocalName())) {
							Type type = null;
							switch (ru.getResult()) {
							case NEW:
								type = Type.CREATE;
								break;
							case FAST_FORWARD:
								type = Type.UPDATE;
								break;
							case FORCED:
								type = Type.UPDATE_NONFASTFORWARD;
								break;
							default:
								type = null;
								break;
							}

							if (type != null) {
								ticketBranchCmd = new ReceiveCommand(ru.getOldObjectId(),
									ru.getNewObjectId(), ru.getLocalName(), type);
							}
						}
					}

					if (ticketBranchCmd != null) {
						repository.fireEvent(new ReceiveCommandEvent(model, ticketBranchCmd));
					}
				}
			} catch (Exception e) {
				logger.error("Error updating mirror {}", repositoryName, e);
			} finally {
				// cleanup
				if (repository != null) {
					repository.close();
				}
			}
		}

		running.set(false);
	}
}
