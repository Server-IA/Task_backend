package com.taskflow.api.entity.enums;

public enum WorkspaceRole {
    OWNER,
    ADMIN,
    MEMBER;

    /** OWNER y ADMIN pueden administrar el workspace. */
    public boolean isAdmin() {
        return this == OWNER || this == ADMIN;
    }
}
