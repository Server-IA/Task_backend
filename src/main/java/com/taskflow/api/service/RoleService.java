package com.taskflow.api.service;

import com.taskflow.api.dto.request.RolePermissionsRequest;
import com.taskflow.api.dto.request.RoleRequest;
import com.taskflow.api.dto.request.UpdateRoleRequest;
import com.taskflow.api.dto.response.PermissionCatalogResponse;
import com.taskflow.api.dto.response.RoleResponse;

import java.util.List;
import java.util.UUID;

/**
 * Roles de un workspace: los dos de fábrica (project manager y «Miembro») y los que crea el workspace.
 * <p>
 * Quien gestiona roles sin ser project manager no puede dar permisos que su propio rol no tiene, ni tocar un rol que
 * tenga permisos que a él le faltan (403): así nadie se sube los permisos a sí mismo.
 */
public interface RoleService {

    /** Catálogo de permisos agrupado por módulo. */
    PermissionCatalogResponse catalog();

    /** Project manager, «Miembro» y los roles creados (por nombre), cada uno con cuántas personas lo tienen. */
    List<RoleResponse> list(String slug);

    RoleResponse create(String slug, UUID actorId, RoleRequest request);

    RoleResponse update(String slug, UUID actorId, UUID roleId, UpdateRoleRequest request);

    /** Cambia los permisos del rol «Miembro». */
    RoleResponse updateMemberPermissions(String slug, UUID actorId, RolePermissionsRequest request);

    /** Elimina un rol creado; quienes lo tenían (y las invitaciones pendientes con él) pasan a «Miembro». */
    void delete(String slug, UUID actorId, UUID roleId);
}
