package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.CycleResponse;
import com.taskflow.api.dto.response.CycleStatus;
import com.taskflow.api.dto.response.ModuleResponse;
import com.taskflow.api.dto.response.ProgressResponse;
import com.taskflow.api.dto.response.RetroNoteResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.ProjectModule;
import com.taskflow.api.entity.RetroNote;
import org.mapstruct.Mapper;

/**
 * Ciclos, módulos y retrospectivas. El estado, el avance y los votos se calculan en el servicio y llegan como
 * parámetros.
 */
@Mapper(uses = {UserMapper.class, IssueMapper.class})
public interface PlanningMapper {

    CycleResponse toCycleResponse(Cycle cycle, CycleStatus status, ProgressResponse progress);

    ModuleResponse toModuleResponse(ProjectModule module, ProgressResponse progress);

    /** {@code votedByMe}: el usuario que pide votó por la nota. Si tiene tarea, necesita su estado y proyecto. */
    RetroNoteResponse toRetroNoteResponse(RetroNote note, int votes, boolean votedByMe);
}
