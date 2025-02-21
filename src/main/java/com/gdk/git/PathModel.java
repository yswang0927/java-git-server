package com.gdk.git;

import java.io.Serializable;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * PathModel is a serializable model class that represents a file or a folder,
 * including all its metadata and associated commit id.
 *
 * @author James Moger
 */
public class PathModel implements Serializable, Comparable<PathModel> {
	private static final long serialVersionUID = 1658305873905432768L;

	public final String name;
	public final String path;
	private final FilestoreModel filestoreItem;
	public final long size;
	public final int mode;
	public final String objectId;
	public final String commitId;
	public boolean isParentPath;
	
	public PathModel(String name, String path, FilestoreModel filestoreItem, long size, int mode, String objectId, String commitId) {
		this.name = name;
		this.path = path;
		this.filestoreItem = filestoreItem;
		this.size = (filestoreItem == null) ? size : filestoreItem.getSize();
		this.mode = mode;
		this.objectId = objectId;
		this.commitId = commitId;
	}

	public boolean isSymlink() {
		return FileMode.SYMLINK.equals(mode);
	}

	public boolean isSubmodule() {
		return FileMode.GITLINK.equals(mode);
	}

	public boolean isTree() {
		return FileMode.TREE.equals(mode);
	}

	public boolean isFile() {
		return FileMode.REGULAR_FILE.equals(mode)
				|| FileMode.EXECUTABLE_FILE.equals(mode)
				|| (FileMode.MISSING.equals(mode) && !isSymlink() && !isSubmodule() && !isTree());
	}
	
	public boolean isFilestoreItem() {
		return filestoreItem != null;
	}
	
	public String getFilestoreOid() {
		if (filestoreItem != null) {
			return filestoreItem.oid;
		}
		return null;
	}

	@Override
	public int hashCode() {
		return commitId.hashCode() + path.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof PathModel) {
			PathModel other = (PathModel) o;
			return this.path.equals(other.path);
		}
		return super.equals(o);
	}

	@Override
	public int compareTo(PathModel o) {
		boolean isTree = isTree();
		boolean otherTree = o.isTree();
		if (isTree && otherTree) {
			return path.compareTo(o.path);
		} else if (!isTree && !otherTree) {
			if (isSubmodule() && o.isSubmodule()) {
				return path.compareTo(o.path);
			} else if (isSubmodule()) {
				return -1;
			} else if (o.isSubmodule()) {
				return 1;
			}
			return path.compareTo(o.path);
		} else if (isTree && !otherTree) {
			return -1;
		}
		return 1;
	}

	/**
	 * PathChangeModel is a serializable class that represents a file changed in
	 * a commit.
	 *
	 * @author James Moger
	 */
	public static class PathChangeModel extends PathModel {
		private static final long serialVersionUID = -6546464087907282265L;

		public ChangeType changeType;
		public int insertions;
		public int deletions;

		public PathChangeModel(String name, String path, FilestoreModel filestoreItem, long size, int mode, String objectId,
				String commitId, ChangeType type) {
			super(name, path, filestoreItem, size, mode, objectId, commitId);
			this.changeType = type;
		}

		public void update(char op) {
			switch (op) {
			case '+':
				insertions++;
				break;
			case '-':
				deletions++;
				break;
			default:
				break;
			}
		}

		@Override
		public int hashCode() {
			return super.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return super.equals(o);
		}

		public static PathChangeModel from(DiffEntry diff, String commitId, Repository repository) {
			PathChangeModel pcm;
			FilestoreModel filestoreItem = null;
			long size = 0;

			if (repository != null) {
				try (RevWalk revWalk = new RevWalk(repository)) {
					size = revWalk.getObjectReader().getObjectSize(diff.getNewId().toObjectId(), Constants.OBJ_BLOB);
					if (JGitUtils.isPossibleFilestoreItem(size)) {
						filestoreItem = JGitUtils.getFilestoreItem(revWalk.getObjectReader().open(diff.getNewId().toObjectId()));
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			
			if (diff.getChangeType().equals(ChangeType.DELETE)) {
				pcm = new PathChangeModel(diff.getOldPath(), diff.getOldPath(), filestoreItem, size, diff
						.getNewMode().getBits(), diff.getOldId().name(), commitId, diff
						.getChangeType());
			} else if (diff.getChangeType().equals(ChangeType.RENAME)) {
				pcm = new PathChangeModel(diff.getOldPath(), diff.getNewPath(), filestoreItem, size, diff
						.getNewMode().getBits(), diff.getNewId().name(), commitId, diff
						.getChangeType());
			} else {
				pcm = new PathChangeModel(diff.getNewPath(), diff.getNewPath(), filestoreItem, size, diff
						.getNewMode().getBits(), diff.getNewId().name(), commitId, diff
						.getChangeType());
			}
			return pcm;
		}
	}
}
