package com.gdk.git;

import java.io.Serializable;

import com.gdk.git.Constants.AccessPermission;
import com.gdk.git.Constants.PermissionType;
import com.gdk.git.Constants.RegistrantType;

/**
 * Represents a Registrant-AccessPermission tuple.
 *
 * @author James Moger
 */
public class RegistrantAccessPermission implements Serializable, Comparable<RegistrantAccessPermission> {
    private static final long serialVersionUID = 1799289245655423961L;

    public String registrant;
    public AccessPermission permission;
    public RegistrantType registrantType;
    public PermissionType permissionType;
    public boolean mutable;
    public String source;

    public RegistrantAccessPermission() {
    }

    public RegistrantAccessPermission(RegistrantType registrantType) {
        this.registrantType = registrantType;
        this.permissionType = PermissionType.EXPLICIT;
        this.mutable = true;
    }

    public RegistrantAccessPermission(String registrant, AccessPermission permission, PermissionType permissionType,
                                      RegistrantType registrantType, String source, boolean mutable) {
        this.registrant = registrant;
        this.permission = permission;
        this.permissionType = permissionType;
        this.registrantType = registrantType;
        this.source = source;
        this.mutable = mutable;
    }

    public boolean isAdmin() {
        return PermissionType.ADMINISTRATOR.equals(permissionType);
    }

    public boolean isOwner() {
        return PermissionType.OWNER.equals(permissionType);
    }

    public boolean isExplicit() {
        return PermissionType.EXPLICIT.equals(permissionType);
    }

    public boolean isRegex() {
        return PermissionType.REGEX.equals(permissionType);
    }

    public boolean isTeam() {
        return PermissionType.TEAM.equals(permissionType);
    }

    public boolean isMissing() {
        return PermissionType.MISSING.equals(permissionType);
    }

    public int getScore() {
        switch (registrantType) {
            case REPOSITORY:
                if (isAdmin()) {
                    return 0;
                }
                if (isOwner()) {
                    return 1;
                }
                if (isExplicit()) {
                    return 2;
                }
                if (isRegex()) {
                    return 3;
                }
                if (isTeam()) {
                    return 4;
                }
            default:
                return 0;
        }
    }

    @Override
    public int compareTo(RegistrantAccessPermission p) {
        switch (registrantType) {
            case REPOSITORY:
                // repository permissions are sorted in score order
                // to convey the order in which permissions are tested
                int score1 = getScore();
                int score2 = p.getScore();
                if (score1 <= 2 && score2 <= 2) {
                    // group admin, owner, and explicit together
                    return StringUtils.compareRepositoryNames(registrant, p.registrant);
                }
                if (score1 < score2) {
                    return -1;
                } else if (score2 < score1) {
                    return 1;
                }
                return StringUtils.compareRepositoryNames(registrant, p.registrant);
            default:
                // user and team permissions are string sorted
                return registrant.toLowerCase().compareTo(p.registrant.toLowerCase());
        }
    }

    @Override
    public int hashCode() {
        return registrant.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof RegistrantAccessPermission) {
            RegistrantAccessPermission p = (RegistrantAccessPermission) o;
            return registrant.equals(p.registrant);
        }

        return false;
    }

    @Override
    public String toString() {
        return permission.asRole(registrant);
    }
}