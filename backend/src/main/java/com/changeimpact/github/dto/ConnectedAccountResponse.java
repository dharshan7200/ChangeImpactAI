package com.changeimpact.github.dto;

import com.changeimpact.repository.entity.ConnectedAccount;

import java.time.Instant;
import java.util.UUID;

/**
 * Public representation of a connected GitHub account.
 * Sensitive fields such as tokens are strictly omitted.
 */
public record ConnectedAccountResponse(
        UUID id,
        String provider,
        String providerAccountId,
        String login,
        String displayName,
        String avatarUrl,
        String email,
        Instant connectedAt,
        boolean isActive
) {
    public static ConnectedAccountResponse from(ConnectedAccount account) {
        return new ConnectedAccountResponse(
                account.getId(),
                account.getProvider(),
                account.getProviderAccountId(),
                account.getLogin(),
                account.getDisplayName(),
                account.getAvatarUrl(),
                account.getEmail(),
                account.getConnectedAt(),
                account.getIsActive() != null && account.getIsActive()
        );
    }
}

