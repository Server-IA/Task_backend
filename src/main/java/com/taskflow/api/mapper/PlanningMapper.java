package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.CycleResponse;
import com.taskflow.api.dto.response.CycleStatus;
import com.taskflow.api.dto.response.ModuleResponse;
import com.taskflow.api.dto.response.ProgressResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.ProjectModule;
import org.mapstruct.Mapper;

/**
 * Ciclos y módulos. El estado y el avance se calculan en el servicio y llegan como parámetros.
 */
@Mapper
public interface PlanningMapper {

    CycleResponse toCycleResponse(Cycle cycle, CycleStatus status, ProgressResponse progress);

    ModuleResponse toModuleResponse(ProjectModule module, ProgressResponse progress);
}
