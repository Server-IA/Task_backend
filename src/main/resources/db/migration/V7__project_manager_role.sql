-- Roles: solo project manager (OWNER) y miembro (MEMBER). Ya no existe el rol ADMIN.

-- Los administradores pasan a ser miembros, igual que las invitaciones (pendientes o no) con ese rol
UPDATE workspace_members SET role = 'MEMBER' WHERE role = 'ADMIN';
UPDATE workspace_invitations SET role = 'MEMBER' WHERE role = 'ADMIN';

-- Todo workspace debe tener al menos un project manager: si alguno no lo tuviera, lo es su creador
UPDATE workspace_members m
SET role = 'OWNER'
FROM workspaces w
WHERE m.workspace_id = w.id
  AND m.user_id = w.owner_id
  AND NOT EXISTS (SELECT 1 FROM workspace_members o WHERE o.workspace_id = w.id AND o.role = 'OWNER');

ALTER TABLE workspace_members DROP CONSTRAINT ck_workspace_members_role;
ALTER TABLE workspace_members ADD CONSTRAINT ck_workspace_members_role CHECK (role IN ('OWNER', 'MEMBER'));

-- Se invita siempre como miembro; el project manager puede promoverlo después
ALTER TABLE workspace_invitations DROP CONSTRAINT ck_workspace_invitations_role;
ALTER TABLE workspace_invitations ADD CONSTRAINT ck_workspace_invitations_role CHECK (role = 'MEMBER');
