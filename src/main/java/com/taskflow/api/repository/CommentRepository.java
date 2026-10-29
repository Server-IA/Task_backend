package com.taskflow.api.repository;

import com.taskflow.api.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    @Query("select c from Comment c join fetch c.author where c.issue.id = :issueId order by c.createdAt asc")
    List<Comment> findAllByIssueIdWithAuthor(@Param("issueId") UUID issueId);

    @Query("select c from Comment c join fetch c.author where c.id = :id and c.issue.id = :issueId")
    Optional<Comment> findByIdAndIssueId(@Param("id") UUID id, @Param("issueId") UUID issueId);
}
