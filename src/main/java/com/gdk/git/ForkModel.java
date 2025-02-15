package com.gdk.git;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * A ForkModel represents a repository, its direct descendants, and its origin.
 *
 * @author James Moger
 */
public class ForkModel implements Serializable {
	private static final long serialVersionUID = -4405472103556613629L;

	public final RepositoryModel repository;
	public final List<ForkModel> forks;

	public ForkModel(RepositoryModel repository) {
		this.repository = repository;
		this.forks = new ArrayList<ForkModel>();
	}

	public boolean isRoot() {
		return StringUtils.isEmpty(repository.originRepository);
	}

	public boolean isNode() {
		return forks != null && !forks.isEmpty();
	}

	public boolean isLeaf() {
		return forks == null || forks.isEmpty();
	}

	public boolean isPersonalRepository() {
		return repository.isPersonalRepository();
	}

	@Override
	public int hashCode() {
		return repository.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof ForkModel) {
			return repository.equals(((ForkModel) o).repository);
		}
		return false;
	}

	@Override
	public String toString() {
		return repository.toString();
	}
}
