package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.IssueSummaryResponse;
import com.taskflow.api.dto.response.PlanningRefResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.ProjectModule;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Set;

@Mapper(uses = {UserMapper.class, ProjectMapper.class})
public interface IssueMapper {

    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "cycle", source = "cycles", qualifiedByName = "currentCycle")
    IssueResponse toResponse(Issue issue);

    IssueSummaryResponse toSummary(Issue issue);

    PlanningRefResponse toRef(Cycle cycle);

    PlanningRefResponse toRef(ProjectModule module);

    /** Un issue está como máximo en un ciclo (restricción única en cycle_issues). */
    @Named("currentCycle")
    default PlanningRefResponse currentCycle(Set<Cycle> cycles) {
        return cycles == null || cycles.isEmpty() ? null : toRef(cycles.iterator().next());
    }
}
