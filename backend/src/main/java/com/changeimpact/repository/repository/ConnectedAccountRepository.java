package com.changeimpact.repository.repository;

import com.changeimpact.repository.entity.ConnectedAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectedAccountRepository extends JpaRepository<ConnectedAccount, UUID> {

    List<ConnectedAccount> findByUserIdAndIsActiveTrue(UUID userId);

    Optional<ConnectedAccount> findByUserIdAndProviderAndProviderAccountId(
            UUID userId, String provider, String providerAccountId);

    @Query("SELECT ca FROM ConnectedAccount ca WHERE ca.user.id = :userId AND ca.isActive = true AND ca.provider = 'github'")
    List<ConnectedAccount> findActiveGitHubAccountsByUserId(UUID userId);
}

