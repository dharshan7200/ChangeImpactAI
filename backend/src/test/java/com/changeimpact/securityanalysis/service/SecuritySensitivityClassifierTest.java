package com.changeimpact.securityanalysis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Security-sensitivity classification (call targets, annotations, names)")
class SecuritySensitivityClassifierTest {

    private final SecuritySensitivityClassifier classifier = new SecuritySensitivityClassifier();

    private Map<String, SecuritySensitivityClassifier.Classification> classify(String simple, String owner, String annotations,
                                                                                 String ownerAnnotations, String params, List<String> calls) {
        return classifier.classify(new SecuritySensitivityClassifier.Subject("p." + owner + "#" + simple, simple, owner, "METHOD",
                        annotations, ownerAnnotations, params, "void", calls)).stream()
                .collect(Collectors.toMap(SecuritySensitivityClassifier.Classification::category, c -> c));
    }

    @Test
    @DisplayName("resolved crypto and SQL call targets are classified with the call as evidence")
    void callTargets() {
        var c = classify("passwordHash", "UserService", null, "@Service", "String",
                List.of("java.security.MessageDigest#getInstance(String)", "org.springframework.jdbc.core.JdbcTemplate#queryForList(String)"));
        assertEquals("HIGH", c.get("CRYPTOGRAPHY").severity());
        assertTrue(c.get("CRYPTOGRAPHY").evidence().contains("calls java.security.MessageDigest#getInstance(String)"));
        assertEquals("MEDIUM", c.get("SQL").severity());
        assertTrue(c.containsKey("SENSITIVE_DATA"), "name contains 'password'");
    }

    @Test
    @DisplayName("authorization annotations, on the method or its class, and REST endpoints taking web input")
    void annotationsAndEndpoints() {
        var c = classify("delete", "AdminController", "@DeleteMapping(\"/users/{id}\")\n@PreAuthorize(\"hasRole('ADMIN')\")",
                "@RestController", "@PathVariable Long id", List.of());
        assertEquals("HIGH", c.get("AUTHORIZATION").severity());
        assertEquals("MEDIUM", c.get("INPUT_HANDLING").severity());
        assertTrue(c.get("INPUT_HANDLING").evidence().stream().anyMatch(e -> e.startsWith("takes web input")));
    }

    @Test
    @DisplayName("dangerous input handling (deserialization, process execution) is HIGH and not downgraded by the endpoint rule")
    void inputHandling() {
        var c = classify("upload", "ImportController", "@PostMapping(\"/import\")", null, "MultipartFile file",
                List.of("java.io.ObjectInputStream#readObject()"));
        assertEquals("HIGH", c.get("INPUT_HANDLING").severity());
    }

    @Test
    @DisplayName("ordinary code is not flagged")
    void nothing() {
        assertTrue(classify("totalPrice", "Order", null, "@Entity", "", List.of("java.math.BigDecimal#add(BigDecimal)")).isEmpty());
    }
}
