package net.jojoaddison.security;

public class Permission {

    private AccessControl accessControl;

    public Permission(AccessControl accessControl) {
        this.accessControl = accessControl;
    }

    public AccessControl getAccessControl() {
        return accessControl;
    }

    public void setAccessControl(AccessControl accessControl) {
        this.accessControl = accessControl;
    }

    public boolean canAll() {
        return PermissionContants.ALL.equals(accessControl.getAction());
    }

    public boolean canCreate() {
        return PermissionContants.CREATE.equals(accessControl.getAction());
    }

    public boolean canRead() {
        return PermissionContants.READ.equals(accessControl.getAction());
    }

    public boolean canUpdate() {
        return PermissionContants.UPDATE.equals(accessControl.getAction());
    }

    public boolean canDelete() {
        return PermissionContants.DELETE.equals(accessControl.getAction());
    }
}
