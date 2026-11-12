-- «Ver todos los proyectos» (permiso PROJECT_VIEW_ALL) en una sola función, para las consultas que deciden qué
-- proyectos ve una persona sin pasar por el código (URLs de archivos, a quién se puede mencionar, la bandeja). Antes
-- esas consultas miraban solo workspace_members.role = 'OWNER', así que alguien con un rol propio que ve todos los
-- proyectos no cargaba las imágenes, no podía ser mencionado y no recibía avisos de los proyectos donde no era miembro.
--
-- El project manager siempre los ve; los demás, si lo trae su rol propio o, sin rol propio, el rol «Miembro».

CREATE FUNCTION sees_all_projects(p_workspace_id UUID, p_user_id UUID) RETURNS BOOLEAN
    LANGUAGE sql STABLE AS
$$
SELECT EXISTS (SELECT 1
               FROM workspace_members wm
                        JOIN workspaces w ON w.id = wm.workspace_id
                        LEFT JOIN workspace_roles r ON r.id = wm.custom_role_id
               WHERE wm.workspace_id = p_workspace_id
                 AND wm.user_id = p_user_id
                 AND (wm.role = 'OWNER'
                   OR COALESCE(r.permissions, w.member_permissions) @> '["PROJECT_VIEW_ALL"]'::jsonb))
$$;
