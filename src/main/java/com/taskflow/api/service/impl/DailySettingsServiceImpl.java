package com.taskflow.api.service.impl;

import com.taskflow.api.dto.request.DailySettingsRequest;
import com.taskflow.api.dto.response.DailySettingsResponse;
import com.taskflow.api.dto.response.DailySettingsResponse.Sections;
import com.taskflow.api.entity.DailySettings;
import com.taskflow.api.entity.Project;
import com.taskflow.api.exception.BadRequestException;
import com.taskflow.api.repository.DailySettingsRepository;
import com.taskflow.api.service.DailySettingsService;
import com.taskflow.api.util.VisibleText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DailySettingsServiceImpl implements DailySettingsService {

    private final DailySettingsRepository settingsRepository;
    private final ResourceLookup lookup;

    @Override
    @Transactional(readOnly = true)
    public DailySettingsResponse get(String slug, UUID projectId) {
        Project project = lookup.project(slug, projectId);
        return settingsRepository.findByProjectId(project.getId())
                .map(DailySettingsServiceImpl::toResponse)
                .orElseGet(DailySettingsServiceImpl::defaults);
    }

    @Override
    @Transactional
    public DailySettingsResponse save(String slug, UUID projectId, UUID userId, DailySettingsRequest request) {
        Project project = lookup.project(slug, projectId);
        // Dos guardados a la vez de un proyecto sin ajustes: el segundo espera y edita la fila que creó el primero
        lookup.lockProject(project);
        DailySettingsRequest.Sections sections = request.sections();
        if (!sections.yesterday() && !sections.today() && !sections.blockers()) {
            throw new BadRequestException("Deja visible al menos uno de los bloques: Ayer, Hoy o Bloqueos");
        }
        List<String> agenda = new ArrayList<>();
        for (String item : request.agenda() == null ? List.<String>of() : request.agenda()) {
            String text = VisibleText.strip(item);
            if (text.isEmpty()) {
                throw new BadRequestException("Un punto a tratar no puede estar vacío");
            }
            agenda.add(text);
        }

        DailySettings settings = settingsRepository.findByProjectId(project.getId())
                .orElseGet(() -> new DailySettings(project));
        settings.setShowSummary(sections.summary());
        settings.setShowYesterday(sections.yesterday());
        settings.setShowToday(sections.today());
        settings.setShowBlockers(sections.blockers());
        settings.setShowNote(sections.note());
        settings.setAgenda(agenda);
        settings.setUpdatedBy(lookup.user(userId));
        return toResponse(settingsRepository.saveAndFlush(settings));
    }

    /** Lo que vale un proyecto que nunca cambió sus ajustes. */
    static DailySettingsResponse defaults() {
        return new DailySettingsResponse(new Sections(true, true, true, true, true), List.of());
    }

    private static DailySettingsResponse toResponse(DailySettings settings) {
        return new DailySettingsResponse(
                new Sections(settings.isShowSummary(), settings.isShowYesterday(), settings.isShowToday(),
                        settings.isShowBlockers(), settings.isShowNote()),
                List.copyOf(settings.getAgenda()));
    }
}
