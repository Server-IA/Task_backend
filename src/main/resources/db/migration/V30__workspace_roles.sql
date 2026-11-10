-- Roles con permisos. Hasta ahora había dos roles fijos (workspace_members.role): OWNER, el project manager, y
-- MEMBER. Se conservan como base:
--   · OWNER sigue teniendo todos los permisos.
--   · MEMBER usa los permisos del rol «Miembro» del workspace (workspaces.member_permissions), que ahora se pueden
--     cambiar, o los de un rol creado por el workspace (workspace_members.custom_role_id).
-- Los permisos son los nombres del enum Permission, en un arreglo JSON.

-- Rol «Miembro»: empieza con lo mismo que un miembro podía hacer antes (Permission.MEMBER_DEFAULTS)
ALTER TABLE workspaces
    ADD COLUMN member_permissions JSONB NOT NULL DEFAULT
        '["ISSUE_CREATE","ISSUE_EDIT","ISSUE_DELETE_OWN","ISSUE_CHECKLIST","ISSUE_RELATIONS","ISSUE_ATTACH","COMMENT_CREATE","COMMENT_IMAGES","CYCLE_ASSIGN","RETRO_PARTICIPATE","MODULE_ASSIGN","DAILY_NOTE","STATS_VIEW","REPORT_GENERATE","ACTIVITY_VIEW"]'::jsonb;

-- Roles creados por cada workspace («Líder», «Desarrollador», «Invitado»…)
CREATE TABLE workspace_roles (
    id           UUID PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name         VARCHAR(40)  NOT NULL,
    description  VARCHAR(200),
    permissions  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Un nombre por workspace, sin importar mayúsculas
CREATE UNIQUE INDEX uk_workspace_roles_name ON workspace_roles (workspace_id, lower(name));

-- El rol de cada miembro. Al eliminar un rol, quienes lo tenían vuelven al rol «Miembro»
ALTER TABLE workspace_members
    ADD COLUMN custom_role_id UUID REFERENCES workspace_roles (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_workspace_members_custom_role CHECK (custom_role_id IS NULL OR role = 'MEMBER');

CREATE INDEX idx_workspace_members_custom_role ON workspace_members (custom_role_id);

-- El rol con el que entra una persona invitada (sin él, «Miembro»)
ALTER TABLE workspace_invitations
    ADD COLUMN custom_role_id UUID REFERENCES workspace_roles (id) ON DELETE SET NULL;

CREATE INDEX idx_workspace_invitations_custom_role ON workspace_invitations (custom_role_id);
