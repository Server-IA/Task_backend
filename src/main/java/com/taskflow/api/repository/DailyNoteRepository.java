package com.taskflow.api.repository;

import com.taskflow.api.entity.DailyNote;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DailyNoteRepository extends JpaRepository<DailyNote, UUID> {

    @EntityGraph(attributePaths = "updatedBy")
    Optional<DailyNote> findByProjectIdAndDate(UUID projectId, LocalDate date);
}
