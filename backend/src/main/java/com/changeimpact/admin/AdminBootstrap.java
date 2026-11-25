package com.changeimpact.admin;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.repository.repository.RoleRepository;
import com.changeimpact.repository.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Grants ROLE_ADMIN to existing users listed in ADMIN_EMAILS (new users get it at registration). */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {

    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var admin = roleRepository.findByName("ROLE_ADMIN").orElse(null);
        if (admin == null) return;
        for (String email : appProperties.getSecurity().getAdminEmails()) {
            if (email == null || email.isBlank()) continue;
            userRepository.findByEmail(email.trim().toLowerCase()).ifPresent(u -> {
                if (u.getRoles().stream().noneMatch(r -> "ROLE_ADMIN".equals(r.getName()))) {
                    u.addRole(admin);
                    userRepository.save(u);
                    log.info("Granted ROLE_ADMIN to user {}", u.getId());
                }
            });
        }
    }
}
