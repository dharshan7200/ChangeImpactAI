package com.changeimpact.auth.service;

import com.changeimpact.auth.dto.AuthResponse;
import com.changeimpact.auth.dto.LoginRequest;
import com.changeimpact.auth.dto.RegisterRequest;
import com.changeimpact.auth.security.JwtService;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.repository.entity.Role;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.RefreshTokenRepository;
import com.changeimpact.repository.repository.RoleRepository;
import com.changeimpact.repository.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private UserDetailsService userDetailsService;
    @Mock
    private AppProperties appProperties;

    @InjectMocks
    private AuthService authService;

    private Role userRole;
    private AppProperties.Security securityProps;

    @BeforeEach
    void setUp() {
        userRole = Role.builder().id(UUID.randomUUID()).name("ROLE_USER").build();

        securityProps = new AppProperties.Security();
        securityProps.getJwt().setAccessTokenExpiryMs(900_000L);
        securityProps.getJwt().setRefreshTokenExpiryMs(604_800_000L);

        lenient().when(appProperties.getSecurity()).thenReturn(securityProps);
    }

    @Test
    @DisplayName("Should register new user successfully when email and username are unique")
    void shouldRegisterNewUser() {
        RegisterRequest request = new RegisterRequest(
                "john@example.com", "johndoe", "P@ssword123", "John Doe");

        when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
        when(userRepository.existsByUsername("johndoe")).thenReturn(false);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode("P@ssword123")).thenReturn("hashed_password");

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                "john@example.com", "hashed_password", Collections.emptyList());
        when(userDetailsService.loadUserByUsername("john@example.com")).thenReturn(userDetails);
        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("mock-access-token");
        when(jwtService.generateRefreshToken("john@example.com")).thenReturn("mock-refresh-token");

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals("mock-access-token", response.accessToken());
        assertEquals("mock-refresh-token", response.refreshToken());
        assertEquals("john@example.com", response.user().email());
        assertEquals("johndoe", response.user().username());

        verify(userRepository).save(any(User.class));
        verify(refreshTokenRepository).save(any());
    }

    @Test
    @DisplayName("Should throw BusinessException when email is already in use")
    void shouldRejectDuplicateEmail() {
        RegisterRequest request = new RegisterRequest(
                "duplicate@example.com", "uniqueuser", "P@ssword123", "Duplicate User");

        when(userRepository.existsByEmail("duplicate@example.com")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(request));
        assertEquals("DUPLICATE_EMAIL", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should authenticate user and return tokens on valid login")
    void shouldLoginSuccessfully() {
        LoginRequest request = new LoginRequest("test@example.com", "Password123");
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .username("testuser")
                .passwordHash("hashed")
                .build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                "test@example.com", "hashed", Collections.emptyList());
        when(userDetailsService.loadUserByUsername("test@example.com")).thenReturn(userDetails);
        when(jwtService.generateAccessToken(any(), any(), any())).thenReturn("access-token-jwt");
        when(jwtService.generateRefreshToken("test@example.com")).thenReturn("refresh-token-jwt");

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("access-token-jwt", response.accessToken());
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(userRepository).save(user);
    }
}

