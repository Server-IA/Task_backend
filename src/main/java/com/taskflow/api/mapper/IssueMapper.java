package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.ChecklistItemResponse;
import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.IssueSummaryResponse;
import com.taskflow.api.dto.response.PlanningRefResponse;
import com.taskflow.api.dto.response.RelatedIssueResponse;
import com.taskflow.api.entity.Cycle;
import com.taskflow.api.entity.Issue;
import com.taskflow.api.entity.IssueChecklistItem;
import com.taskflow.api.entity.ProjectModule;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;
import java.util.Set;

@Mapper(uses = {UserMapper.class, ProjectMapper.class})
public interface IssueMapper {

    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "cycle", source = "cycles", qualifiedByName = "currentCycle")
    IssueResponse toResponse(Issue issue);

    IssueSummaryResponse toSummary(Issue issue);

    /** Referencia a una tarea (la otra de una dependencia, las de la daily…). Necesita su estado y su proyecto. */
    RelatedIssueResponse toRelated(Issue issue);

    /**
     * La otra tarea de una dependencia cuando el usuario no tiene acceso a su proyecto: sin título ni asignados.
     * Con {@code @Named} para que MapStruct no la use en lugar de {@link #toRelated}.
     */
    @Named("restrictedRelated")
    default RelatedIssueResponse toRestrictedRelated(Issue issue) {
        RelatedIssueResponse full = toRelated(issue);
        return new RelatedIssueResponse(full.id(), full.code(), null, full.state(), List.of(), full.project(),
                full.dueDate(), full.blockedByCount(), full.subIssueCount(), full.completedSubIssueCount(),
                full.checklistTotal(), full.checklistDone());
    }

    ChecklistItemResponse toChecklistItemResponse(IssueChecklistItem item);

    PlanningRefResponse toRef(Cycle cycle);

    PlanningRefResponse toRef(ProjectModule module);

    /** Un issue está como máximo en un ciclo (restricción única en cycle_issues). */
    @Named("currentCycle")
    default PlanningRefResponse currentCycle(Set<Cycle> cycles) {
        return cycles == null || cycles.isEmpty() ? null : toRef(cycles.iterator().next());
    }
}
