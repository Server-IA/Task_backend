package com.taskflow.api.security;

import com.taskflow.api.entity.enums.WorkspaceRole;
import com.taskflow.api.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceSecurityTest {

    private static final String SLUG = "mi-equipo";

    @Mock
    private WorkspaceMemberRepository memberRepository;

    @InjectMocks
    private WorkspaceSecurity workspaceSecurity;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        UserPrincipal principal = UserPrincipal.fromToken(userId, "ana@test.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerTieneTodosLosPermisos() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.of(WorkspaceRole.OWNER));

        assertThat(workspaceSecurity.isMember(SLUG)).isTrue();
        assertThat(workspaceSecurity.isAdmin(SLUG)).isTrue();
        assertThat(workspaceSecurity.isOwner(SLUG)).isTrue();
    }

    @Test
    void adminAdministraPeroNoEsDueno() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.of(WorkspaceRole.ADMIN));

        assertThat(workspaceSecurity.isMember(SLUG)).isTrue();
        assertThat(workspaceSecurity.isAdmin(SLUG)).isTrue();
        assertThat(workspaceSecurity.isOwner(SLUG)).isFalse();
    }

    @Test
    void memberSoloEsMiembro() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.of(WorkspaceRole.MEMBER));

        assertThat(workspaceSecurity.isMember(SLUG)).isTrue();
        assertThat(workspaceSecurity.isAdmin(SLUG)).isFalse();
        assertThat(workspaceSecurity.isOwner(SLUG)).isFalse();
    }

    @Test
    void quienNoPerteneceNoTieneAcceso() {
        when(memberRepository.findRole(SLUG, userId)).thenReturn(Optional.empty());

        assertThat(workspaceSecurity.isMember(SLUG)).isFalse();
        assertThat(workspaceSecurity.isAdmin(SLUG)).isFalse();
    }

    @Test
    void sinAutenticacionNoConsultaLaBaseDeDatos() {
        SecurityContextHolder.clearContext();

        assertThat(workspaceSecurity.isMember(SLUG)).isFalse();
        verifyNoInteractions(memberRepository);
    }
}
