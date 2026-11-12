package com.taskflow.api.repository;

import com.taskflow.api.entity.DailySettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DailySettingsRepository extends JpaRepository<DailySettings, UUID> {

    Optional<DailySettings> findByProjectId(UUID projectId);
}
