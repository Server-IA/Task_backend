package com.kanstad.task.auth.service;

import com.kanstad.task.auth.dto.AuthResponse;
import com.kanstad.task.auth.dto.LoginRequest;
import com.kanstad.task.auth.dto.RegisterRequest;
import com.kanstad.task.user.dto.UserInfoResponse;
import com.kanstad.task.role.Role;
import com.kanstad.task.role.repositories.RoleRepository;
import com.kanstad.task.user.User;
import com.kanstad.task.user.repository.UserRepository;
import com.kanstad.task.auth.security.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Collections;

@Service
public class AuthService {
    
    @Autowired
    AuthenticationManager authenticationManager;
    
    @Autowired
    UserRepository userRepository;
    
    @Autowired
    RoleRepository roleRepository;
    
    @Autowired
    PasswordEncoder encoder;
    
    @Autowired
    JwtUtils jwtUtils;
    
    public AuthResponse authenticateUser(LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(loginRequest.getUsernameOrEmail(), loginRequest.getPassword()));
        
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtUtils.generateJwtToken(authentication);
        
        User user = (User) authentication.getPrincipal();
        String roleName = user.getRoles().isEmpty() ? "NO_ROLE" : user.getRoles().iterator().next().getName();
        
        return new AuthResponse(jwt, user.getId(), user.getUsername(), user.getEmail(), roleName);
    }
    
    public String registerUser(RegisterRequest signUpRequest) {
        // Verificar si el username ya existe
        if (userRepository.existsByUsername(signUpRequest.getUsername())) {
            throw new RuntimeException("Error: Username is already taken!");
        }
        
        // Verificar si el email ya existe
        if (userRepository.existsByEmail(signUpRequest.getEmail())) {
            throw new RuntimeException("Error: Email is already in use!");
        }
        
        // Obtener el rol por defecto por nombre
        Role userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new RuntimeException("Error: Role USER is not found. Please check if roles are initialized in the database."));

        // Crear nueva cuenta de usuario
        User user = User.builder()
                .username(signUpRequest.getUsername())
                .email(signUpRequest.getEmail())
                .password(encoder.encode(signUpRequest.getPassword()))
                .firstName(signUpRequest.getFirstName())
                .lastName(signUpRequest.getLastName())
                .roles(Collections.singleton(userRole))
                .build();
        
        userRepository.save(user);
        
        return "User registered successfully!";
    }
    
    public UserInfoResponse getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) authentication.getPrincipal();
        String roleName = user.getRoles().isEmpty() ? "NO_ROLE" : user.getRoles().iterator().next().getName();
        
        return UserInfoResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(roleName)
                .enabled(user.isEnabled())
                .build();
    }
}
