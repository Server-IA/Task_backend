package com.taskflow.api.service.impl;

import com.taskflow.api.config.AppProperties;
import com.taskflow.api.dto.request.InviteMemberRequest;
import com.taskflow.api.email.EmailNotifier;
import com.taskflow.api.entity.User;
import com.taskflow.api.entity.Workspace;
import com.taskflow.api.entity.WorkspaceInvitation;
import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.exception.TooManyRequestsException;
import com.taskflow.api.mapper.WorkspaceMapper;
import com.taskflow.api.ratelimit.RateLimitRule;
import com.taskflow.api.ratelimit.RateLimitService;
import com.taskflow.api.repository.WorkspaceInvitationRepository;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvitationServiceImplTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private WorkspaceInvitationRepository invitationRepository;
    @Mock
    private WorkspaceMemberRepository memberRepository;
    @Mock
    private ResourceLookup lookup;
    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private EmailNotifier emailNotifier;

    private InvitationServiceImpl invitationService;
    private final User pm = new User();

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(null, null, "http://localhost:5173", "America/Bogota",
                new AppProperties.Tokens(24, 60, 7));
        invitationService = new InvitationServiceImpl(invitationRepository, memberRepository,
                lookup, workspaceMapper, emailNotifier, properties, new RateLimitService());
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName("Mi equipo");
        pm.setId(UUID.randomUUID());
        pm.setFullName("Ana");
        when(lookup.workspace(SLUG)).thenReturn(workspace);
        when(lookup.user(pm.getId())).thenReturn(pm);
    }

    @Test
    void unMismoCorreoNoRecibeUnaAvalanchaDeInvitaciones() {
        for (int i = 0; i < RateLimitRule.INVITE_RECIPIENT.getCapacity(); i++) {
            invitationService.invite(SLUG, pm.getId(), new InviteMemberRequest("victima@test.com"));
        }

        assertThatThrownBy(() -> invitationService.invite(SLUG, pm.getId(),
                new InviteMemberRequest("Victima@Test.com")))
                .isInstanceOf(TooManyRequestsException.class);
        verify(emailNotifier, times(RateLimitRule.INVITE_RECIPIENT.getCapacity()))
                .sendWorkspaceInvitation(any(), any(), any(), any());
    }

    @Test
    void cadaPersonaTieneUnLimiteDeInvitacionesPorHora() {
        for (int i = 0; i < RateLimitRule.INVITE_SENDER.getCapacity(); i++) {
            invitationService.invite(SLUG, pm.getId(), new InviteMemberRequest("persona" + i + "@test.com"));
        }

        assertThatThrownBy(() -> invitationService.invite(SLUG, pm.getId(),
                new InviteMemberRequest("otra@test.com")))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void laPersonaInvitadaEntraSiempreComoMiembro() {
        invitationService.invite(SLUG, pm.getId(), new InviteMemberRequest("  Beto@Test.com "));

        ArgumentCaptor<WorkspaceInvitation> saved = ArgumentCaptor.forClass(WorkspaceInvitation.class);
        verify(invitationRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(saved.getValue().getEmail()).isEqualTo("beto@test.com");
    }
}
