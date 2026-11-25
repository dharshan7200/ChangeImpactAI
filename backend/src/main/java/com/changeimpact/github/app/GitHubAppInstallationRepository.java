package com.changeimpact.github.app;

import com.changeimpact.github.app.GitHubAppInstallation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface GitHubAppInstallationRepository extends JpaRepository<GitHubAppInstallation, UUID> {
    Optional<GitHubAppInstallation> findByInstallationId(Long installationId);
}
