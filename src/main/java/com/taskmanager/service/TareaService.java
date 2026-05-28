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
import java.util.Collections;
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

        if (dto.getAsignadoId() != null) {
            Usuario asignado = usuarioRepository.findById(dto.getAsignadoId())
                    .orElseThrow(() -> new ResourceNotFoundException("Usuario", dto.getAsignadoId()));
            tarea.setAsignado(asignado);
        }

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

        if (dto.getAsignadoId() != null) {
            Usuario asignado = usuarioRepository.findById(dto.getAsignadoId())
                    .orElseThrow(() -> new ResourceNotFoundException("Usuario", dto.getAsignadoId()));
            tarea.setAsignado(asignado);
        } else {
            tarea.setAsignado(null);
        }

        Tarea updated = tareaRepository.save(tarea);
        return convertToDTO(updated);
    }

        public TareaDTO updateWithPermissions(Long id, TareaDTO dto, String emailUsuario) {
                Tarea tarea = tareaRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Tarea", id));

                Long uid = membershipPermissionService.requireUserId(emailUsuario);
                boolean canManage = membershipPermissionService.canManageProyecto(uid, tarea.getProyecto().getId());
                boolean isCreator = tarea.getCreador() != null && tarea.getCreador().getId().equals(uid);
                boolean isAssigned = tarea.getAsignado() != null && tarea.getAsignado().getId().equals(uid);

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
        tareaRepository.deleteById(id);
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
        if (tarea.getAsignado() != null) {
            dto.setAsignadoId(tarea.getAsignado().getId());
            dto.setAsignadoNombre(tarea.getAsignado().getApodo() != null
                    ? tarea.getAsignado().getApodo()
                    : tarea.getAsignado().getNombre());
        }
        if (tarea.getCreador() != null) {
            dto.setCreadorId(tarea.getCreador().getId());
            dto.setCreadorNombre(tarea.getCreador().getApodo() != null
                    ? tarea.getCreador().getApodo()
                    : tarea.getCreador().getNombre());
        }
        dto.setPrioridad(tarea.getPrioridad());
        dto.setFechaLimite(tarea.getFechaLimite());
        dto.setFechaCompletada(tarea.getFechaCompletada());
        dto.setOrden(tarea.getOrden());
        dto.setFechaCreacion(tarea.getFechaCreacion());
        return dto;
    }
}
