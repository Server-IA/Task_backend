package com.taskflow.api.repository;

import com.taskflow.api.entity.IssueActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface IssueActivityRepository extends JpaRepository<IssueActivity, UUID> {

    @Query("select a from IssueActivity a left join fetch a.actor where a.issue.id = :issueId order by a.createdAt asc")
    List<IssueActivity> findAllByIssueIdWithActor(@Param("issueId") UUID issueId);
}
