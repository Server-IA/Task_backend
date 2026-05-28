package com.taskmanager.service;

import com.taskmanager.dto.TareaDTO;
import com.taskmanager.exception.ResourceNotFoundException;
import com.taskmanager.model.*;
import com.taskmanager.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class TareaService implements ITareaService {

    @Autowired
    private TareaRepository tareaRepository;

    @Autowired
    private ProyectoRepository proyectoRepository;

    @Autowired
    private EstadoRepository estadoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private ComentarioTareaRepository comentarioTareaRepository;

    @Autowired
    private MembershipPermissionService membershipPermissionService;

    public List<TareaDTO> findAccessibleForUser(String emailUsuario) {
        Usuario u = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new RuntimeException("No se pudo identificar tu cuenta de usuario."));
        List<Long> proyectoIds = proyectoRepository.findAccessibleByUsuarioId(u.getId()).stream()
                .map(Proyecto::getId)
                .collect(Collectors.toList());
        if (proyectoIds.isEmpty()) {
            return Collections.emptyList();
        }
        return tareaRepository.findByProyectoIdIn(proyectoIds).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<TareaDTO> findAll() {
        return tareaRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public TareaDTO findById(Long id) {
        Tarea tarea = tareaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tarea", id));
        return convertToDTO(tarea);
    }

    public List<TareaDTO> findByProyectoId(Long proyectoId) {
        return tareaRepository.findByProyectoIdOrderByOrdenAsc(proyectoId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<TareaDTO> findByAsignadoId(Long asignadoId) {
        return tareaRepository.findByAsignadoId(asignadoId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<TareaDTO> findByProyectoIdAndEstadoId(Long proyectoId, Long estadoId) {
        return tareaRepository.findByProyectoIdAndEstadoId(proyectoId, estadoId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public TareaDTO create(TareaDTO dto, String emailCreador) {
        if (dto.getFechaLimite() != null && dto.getFechaLimite().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha límite no puede ser una fecha pasada");
        }

        Proyecto proyecto = proyectoRepository.findById(dto.getProyectoId())
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto", dto.getProyectoId()));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new ResourceNotFoundException("Estado", dto.getEstadoId()));

        Usuario creador = usuarioRepository.findByEmail(emailCreador)
                .orElseThrow(() -> new RuntimeException("No se pudo identificar tu cuenta de usuario. Por favor, inicia sesión nuevamente."));

        Tarea tarea = new Tarea();
        tarea.setTitulo(dto.getTitulo());
        tarea.setDescripcion(dto.getDescripcion());
        tarea.setProyecto(proyecto);
        tarea.setEstado(estado);
        tarea.setCreador(creador);
        tarea.setPrioridad(dto.getPrioridad());
        tarea.setFechaLimite(dto.getFechaLimite());
        tarea.setOrden(dto.getOrden());
        syncAsignados(tarea, resolveAsignadoIds(dto));

        Tarea saved = tareaRepository.save(tarea);
        return convertToDTO(saved);
    }

    public TareaDTO update(Long id, TareaDTO dto) {
        if (dto.getFechaLimite() != null && dto.getFechaLimite().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha límite no puede ser una fecha pasada");
        }

        Tarea tarea = tareaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tarea", id));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new ResourceNotFoundException("Estado", dto.getEstadoId()));

        if (dto.getProyectoId() != null && !dto.getProyectoId().equals(tarea.getProyecto().getId())) {
            Proyecto proyecto = proyectoRepository.findById(dto.getProyectoId())
                    .orElseThrow(() -> new ResourceNotFoundException("Proyecto", dto.getProyectoId()));
            tarea.setProyecto(proyecto);
        }

        tarea.setTitulo(dto.getTitulo());
        tarea.setDescripcion(dto.getDescripcion());
        tarea.setEstado(estado);
        tarea.setPrioridad(dto.getPrioridad());
        tarea.setFechaLimite(dto.getFechaLimite());
        tarea.setFechaCompletada(dto.getFechaCompletada());
        tarea.setOrden(dto.getOrden());
        syncAsignados(tarea, resolveAsignadoIds(dto));

        Tarea updated = tareaRepository.save(tarea);
        return convertToDTO(updated);
    }

    public TareaDTO updateWithPermissions(Long id, TareaDTO dto, String emailUsuario) {
        Tarea tarea = tareaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tarea", id));

        Long uid = membershipPermissionService.requireUserId(emailUsuario);
        boolean canManage = membershipPermissionService.canManageProyecto(uid, tarea.getProyecto().getId());
        boolean isCreator = tarea.getCreador() != null && tarea.getCreador().getId().equals(uid);
        boolean isAssigned = tarea.getAsignados().stream()
                .anyMatch(a -> a.getUsuario().getId().equals(uid));

        if (canManage || isCreator) {
            return update(id, dto);
        }

        if (!isAssigned) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes editar esta tarea");
        }

        if (dto.getEstadoId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El estado es obligatorio");
        }

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new ResourceNotFoundException("Estado", dto.getEstadoId()));

        tarea.setEstado(estado);

        Tarea updated = tareaRepository.save(tarea);
        return convertToDTO(updated);
    }

    public void delete(Long id) {
        Tarea tarea = tareaRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tarea", id));
        if (!"Completado".equalsIgnoreCase(tarea.getEstado().getNombre())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se pueden eliminar tareas con estado 'Completado'");
        }
        comentarioTareaRepository.deleteByTareaId(id);
        tareaRepository.delete(tarea);
    }

    private List<Long> resolveAsignadoIds(TareaDTO dto) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (dto.getAsignadoIds() != null) {
            dto.getAsignadoIds().stream().filter(id -> id != null).forEach(ids::add);
        }
        if (ids.isEmpty() && dto.getAsignadoId() != null) {
            ids.add(dto.getAsignadoId());
        }
        return new ArrayList<>(ids);
    }

    private void syncAsignados(Tarea tarea, List<Long> asignadoIds) {
        tarea.getAsignados().clear();
        if (asignadoIds == null || asignadoIds.isEmpty()) {
            return;
        }
        for (Long usuarioId : asignadoIds) {
            Usuario usuario = usuarioRepository.findById(usuarioId)
                    .orElseThrow(() -> new ResourceNotFoundException("Usuario", usuarioId));
            TareaAsignado asignacion = new TareaAsignado();
            asignacion.setTarea(tarea);
            asignacion.setUsuario(usuario);
            tarea.getAsignados().add(asignacion);
        }
    }

    private String displayName(Usuario usuario) {
        if (usuario.getApodo() != null && !usuario.getApodo().isBlank()) {
            return usuario.getApodo();
        }
        return usuario.getNombre();
    }

    private TareaDTO convertToDTO(Tarea tarea) {
        TareaDTO dto = new TareaDTO();
        dto.setId(tarea.getId());
        dto.setTitulo(tarea.getTitulo());
        dto.setDescripcion(tarea.getDescripcion());
        dto.setProyectoId(tarea.getProyecto().getId());
        dto.setProyectoNombre(tarea.getProyecto().getNombre());
        dto.setEstadoId(tarea.getEstado().getId());
        dto.setEstadoNombre(tarea.getEstado().getNombre());

        List<Long> asignadoIds = new ArrayList<>();
        List<String> asignadoNombres = new ArrayList<>();
        if (tarea.getAsignados() != null) {
            for (TareaAsignado asignacion : tarea.getAsignados()) {
                asignadoIds.add(asignacion.getUsuario().getId());
                asignadoNombres.add(displayName(asignacion.getUsuario()));
            }
        }
        dto.setAsignadoIds(asignadoIds);
        dto.setAsignadoNombres(asignadoNombres);
        if (!asignadoIds.isEmpty()) {
            dto.setAsignadoId(asignadoIds.get(0));
            dto.setAsignadoNombre(asignadoNombres.get(0));
        }

        if (tarea.getCreador() != null) {
            dto.setCreadorId(tarea.getCreador().getId());
            dto.setCreadorNombre(displayName(tarea.getCreador()));
        }
        dto.setPrioridad(tarea.getPrioridad());
        dto.setFechaLimite(tarea.getFechaLimite());
        dto.setFechaCompletada(tarea.getFechaCompletada());
        dto.setOrden(tarea.getOrden());
        dto.setFechaCreacion(tarea.getFechaCreacion());
        return dto;
    }
}
