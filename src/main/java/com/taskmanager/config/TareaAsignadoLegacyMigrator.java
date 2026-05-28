package com.taskmanager.config;

import com.taskmanager.model.Tarea;
import com.taskmanager.model.TareaAsignado;
import com.taskmanager.model.Usuario;
import com.taskmanager.repository.TareaAsignadoRepository;
import com.taskmanager.repository.TareaRepository;
import com.taskmanager.repository.UsuarioRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Component
public class TareaAsignadoLegacyMigrator {

    private final JdbcTemplate jdbcTemplate;
    private final TareaRepository tareaRepository;
    private final TareaAsignadoRepository tareaAsignadoRepository;
    private final UsuarioRepository usuarioRepository;

    public TareaAsignadoLegacyMigrator(
            JdbcTemplate jdbcTemplate,
            TareaRepository tareaRepository,
            TareaAsignadoRepository tareaAsignadoRepository,
            UsuarioRepository usuarioRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.tareaRepository = tareaRepository;
        this.tareaAsignadoRepository = tareaAsignadoRepository;
        this.usuarioRepository = usuarioRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrateLegacyAsignados() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, asignado_id FROM tareas WHERE asignado_id IS NOT NULL");
            for (Map<String, Object> row : rows) {
                Long tareaId = ((Number) row.get("id")).longValue();
                Long usuarioId = ((Number) row.get("asignado_id")).longValue();
                if (tareaAsignadoRepository.existsByTareaIdAndUsuarioId(tareaId, usuarioId)) {
                    continue;
                }
                Tarea tarea = tareaRepository.findById(tareaId).orElse(null);
                Usuario usuario = usuarioRepository.findById(usuarioId).orElse(null);
                if (tarea == null || usuario == null) {
                    continue;
                }
                TareaAsignado asignacion = new TareaAsignado();
                asignacion.setTarea(tarea);
                asignacion.setUsuario(usuario);
                tarea.getAsignados().add(asignacion);
                tareaRepository.save(tarea);
            }
        } catch (DataAccessException ignored) {
            // La columna legacy asignado_id puede no existir en instalaciones nuevas.
        }
    }
}
