package com.gdk.git;

public interface IUserManager extends IUserService {

	/**
	 * Returns true if the username represents an internal account
	 *
	 * @param username
	 * @return true if the specified username represents an internal account
 	 * @since 1.4.0
	 */
	boolean isInternalAccount(String username);

}