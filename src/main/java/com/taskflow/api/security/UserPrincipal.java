package com.taskflow.api.security;

import com.taskflow.api.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Usuario autenticado. Los permisos de workspace no viajan aquí: se consultan en la base de datos.
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final UUID id;
    private final String email;
    private final String password;
    private final boolean enabled;
    private final boolean accountNonLocked;

    private UserPrincipal(UUID id, String email, String password, boolean enabled, boolean accountNonLocked) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
    }

    /** Usado en el login: incluye el hash de la contraseña y el estado de la cuenta. */
    public static UserPrincipal fromUser(User user) {
        return new UserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.isEmailVerified(), user.isActive());
    }

    /** Usado al validar un access token: solo con los datos que lleva el JWT. */
    public static UserPrincipal fromToken(UUID id, String email) {
        return new UserPrincipal(id, email, null, true, true);
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }
}
