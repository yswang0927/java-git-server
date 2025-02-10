package com.gdk.git;

import java.io.Serializable;

/**
 * User repository preferences.
 *
 * @author James Moger
 */
public class UserRepositoryPreferences implements Serializable {
	private static final long serialVersionUID = 7630976363839538114L;

	public String username;
	public String repositoryName;
	public boolean starred;

	@Override
	public String toString() {
		return username + ":" + repositoryName;
	}

}
