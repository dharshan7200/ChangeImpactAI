package com.changeimpact.auth.security;

import com.changeimpact.repository.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Custom Spring Security {@link UserDetails} implementation that retains
 * the domain user's UUID and username directly in the SecurityContext.
 */
@Getter
@AllArgsConstructor
@Builder
public class UserPrincipal implements UserDetails {

    private final UUID id;
    private final String email;
    private final String username;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;
    private final boolean enabled;
    private final boolean accountNonLocked;

    public static UserPrincipal from(User user) {
        var authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName()))
                .collect(Collectors.toSet());

        return UserPrincipal.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .enabled(user.getEnabled() != null && user.getEnabled())
                .accountNonLocked(user.getAccountLocked() == null || !user.getAccountLocked())
                .build();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        // Return email as standard login principal identifier
        return email;
    }

    public String getDisplayUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

