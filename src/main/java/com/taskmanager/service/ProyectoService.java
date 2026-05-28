package com.taskmanager.service;

import com.taskmanager.dto.ProyectoDTO;
import com.taskmanager.exception.ResourceNotFoundException;
import com.taskmanager.model.*;
import com.taskmanager.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProyectoService implements IProyectoService {

    @Autowired
    private ProyectoRepository proyectoRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private TipoProyectoRepository tipoProyectoRepository;

    @Autowired
    private EstadoRepository estadoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private TareaRepository tareaRepository;

    @Autowired
    private ComentarioTareaRepository comentarioTareaRepository;

    @Autowired
    private MiembroProyectoRepository miembroProyectoRepository;

    @Autowired
    private MembershipPermissionService membershipPermissionService;

    public List<ProyectoDTO> findAccessibleForUser(String emailUsuario) {
        Usuario u = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new RuntimeException("No se pudo identificar tu cuenta de usuario."));
        return proyectoRepository.findAccessibleByUsuarioId(u.getId()).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<ProyectoDTO> findAccessibleByEmpresaForUser(String emailUsuario, Long empresaId) {
        Usuario u = usuarioRepository.findByEmail(emailUsuario)
                .orElseThrow(() -> new RuntimeException("No se pudo identificar tu cuenta de usuario."));
        Set<Long> accessibleIds = proyectoRepository.findAccessibleByUsuarioId(u.getId()).stream()
                .map(Proyecto::getId)
                .collect(Collectors.toSet());
        return proyectoRepository.findByEmpresaId(empresaId).stream()
                .filter(p -> accessibleIds.contains(p.getId()))
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public List<ProyectoDTO> findAll() {
        return proyectoRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public ProyectoDTO findById(Long id) {
        Proyecto proyecto = proyectoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto", id));
        return convertToDTO(proyecto);
    }

    public List<ProyectoDTO> findByEmpresaId(Long empresaId) {
        return proyectoRepository.findByEmpresaId(empresaId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    public ProyectoDTO create(ProyectoDTO dto, String emailCreador) {
        LocalDate hoy = LocalDate.now();
        if (dto.getFechaInicio() != null && dto.getFechaInicio().isBefore(hoy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha de inicio no puede ser una fecha pasada");
        }
        if (dto.getFechaFinEstimada() != null && dto.getFechaFinEstimada().isBefore(hoy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha fin estimada no puede ser una fecha pasada");
        }

        membershipPermissionService.requireEmpresaManagement(emailCreador, dto.getEmpresaId());

        Empresa empresa = empresaRepository.findById(dto.getEmpresaId())
                .orElseThrow(() -> new ResourceNotFoundException("Empresa", dto.getEmpresaId()));

        TipoProyecto tipoProyecto = tipoProyectoRepository.findById(dto.getTipoProyectoId())
                .orElseThrow(() -> new ResourceNotFoundException("TipoProyecto", dto.getTipoProyectoId()));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new ResourceNotFoundException("Estado", dto.getEstadoId()));

        Usuario creador = usuarioRepository.findByEmail(emailCreador)
                .orElseThrow(() -> new RuntimeException("No se pudo identificar tu cuenta de usuario. Por favor, inicia sesión nuevamente."));

        Proyecto proyecto = new Proyecto();
        proyecto.setNombre(dto.getNombre());
        proyecto.setDescripcion(dto.getDescripcion());
        proyecto.setEmpresa(empresa);
        proyecto.setTipoProyecto(tipoProyecto);
        proyecto.setEstado(estado);
        proyecto.setCreador(creador);
        proyecto.setPrioridad(dto.getPrioridad());
        proyecto.setFechaInicio(dto.getFechaInicio());
        proyecto.setFechaFinEstimada(dto.getFechaFinEstimada());
        proyecto.setFechaFinReal(dto.getFechaFinReal());
        proyecto.setProgreso(dto.getProgreso());

        Proyecto saved = proyectoRepository.save(proyecto);

        if (!miembroProyectoRepository.existsByUsuarioIdAndProyectoId(creador.getId(), saved.getId())) {
            MiembroProyecto miembroProyecto = new MiembroProyecto();
            miembroProyecto.setUsuario(creador);
            miembroProyecto.setProyecto(saved);
            miembroProyecto.setRol("LIDER");
            miembroProyectoRepository.save(miembroProyecto);
        }

        return convertToDTO(saved);
    }

    public ProyectoDTO update(Long id, ProyectoDTO dto) {
        LocalDate hoy = LocalDate.now();
        if (dto.getFechaInicio() != null && dto.getFechaInicio().isBefore(hoy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha de inicio no puede ser una fecha pasada");
        }
        if (dto.getFechaFinEstimada() != null && dto.getFechaFinEstimada().isBefore(hoy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La fecha fin estimada no puede ser una fecha pasada");
        }

        Proyecto proyecto = proyectoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto", id));

        Empresa empresa = empresaRepository.findById(dto.getEmpresaId())
                .orElseThrow(() -> new ResourceNotFoundException("Empresa", dto.getEmpresaId()));

        TipoProyecto tipoProyecto = tipoProyectoRepository.findById(dto.getTipoProyectoId())
                .orElseThrow(() -> new ResourceNotFoundException("TipoProyecto", dto.getTipoProyectoId()));

        Estado estado = estadoRepository.findById(dto.getEstadoId())
                .orElseThrow(() -> new ResourceNotFoundException("Estado", dto.getEstadoId()));

        proyecto.setNombre(dto.getNombre());
        proyecto.setDescripcion(dto.getDescripcion());
        proyecto.setEmpresa(empresa);
        proyecto.setTipoProyecto(tipoProyecto);
        proyecto.setEstado(estado);
        proyecto.setPrioridad(dto.getPrioridad());
        proyecto.setFechaInicio(dto.getFechaInicio());
        proyecto.setFechaFinEstimada(dto.getFechaFinEstimada());
        proyecto.setFechaFinReal(dto.getFechaFinReal());
        proyecto.setProgreso(dto.getProgreso());

        Proyecto updated = proyectoRepository.save(proyecto);
        return convertToDTO(updated);
    }

    public void delete(Long id) {
        Proyecto proyecto = proyectoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto", id));
        if (!"Completado".equalsIgnoreCase(proyecto.getEstado().getNombre())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se pueden eliminar proyectos con estado 'Completado'");
        }
        comentarioTareaRepository.deleteByProyectoId(id);
        miembroProyectoRepository.deleteByProyectoId(id);
        tareaRepository.softDeleteByProyectoId(id);
        proyectoRepository.deleteById(id);
    }

    private ProyectoDTO convertToDTO(Proyecto proyecto) {
        ProyectoDTO dto = new ProyectoDTO();
        dto.setId(proyecto.getId());
        dto.setNombre(proyecto.getNombre());
        dto.setDescripcion(proyecto.getDescripcion());
        dto.setEmpresaId(proyecto.getEmpresa().getId());
        dto.setEmpresaNombre(proyecto.getEmpresa().getNombre());
        dto.setTipoProyectoId(proyecto.getTipoProyecto().getId());
        dto.setTipoProyectoNombre(proyecto.getTipoProyecto().getNombre());
        dto.setEstadoId(proyecto.getEstado().getId());
        dto.setEstadoNombre(proyecto.getEstado().getNombre());
        if (proyecto.getCreador() != null) {
            dto.setCreadorId(proyecto.getCreador().getId());
            dto.setCreadorNombre(proyecto.getCreador().getApodo() != null
                    ? proyecto.getCreador().getApodo()
                    : proyecto.getCreador().getNombre());
        }
        dto.setPrioridad(proyecto.getPrioridad());
        dto.setFechaInicio(proyecto.getFechaInicio());
        dto.setFechaFinEstimada(proyecto.getFechaFinEstimada());
        dto.setFechaFinReal(proyecto.getFechaFinReal());
        dto.setProgreso(proyecto.getProgreso());
        dto.setFechaCreacion(proyecto.getFechaCreacion());
        return dto;
    }
}
