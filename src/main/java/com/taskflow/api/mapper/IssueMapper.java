package com.taskflow.api.mapper;

import com.taskflow.api.dto.response.IssueResponse;
import com.taskflow.api.dto.response.IssueSummaryResponse;
import com.taskflow.api.entity.Issue;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(uses = {UserMapper.class, ProjectMapper.class})
public interface IssueMapper {

    @Mapping(target = "projectId", source = "project.id")
    IssueResponse toResponse(Issue issue);

    IssueSummaryResponse toSummary(Issue issue);
}
