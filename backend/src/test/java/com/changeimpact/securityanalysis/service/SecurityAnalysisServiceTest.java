package com.changeimpact.securityanalysis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Security analysis mapping helpers")
class SecurityAnalysisServiceTest {

    @Test
    @DisplayName("Dependency-Check package URLs map to Maven coordinates")
    void coordinates() {
        assertEquals("org.apache.commons:commons-text", SecurityAnalysisService.coordinate("pkg:maven/org.apache.commons/commons-text@1.9"));
        assertEquals("com.google.guava:guava", SecurityAnalysisService.coordinate("pkg:maven/com.google.guava/guava@33.3.1-jre"));
        assertNull(SecurityAnalysisService.coordinate("pkg:npm/lodash@4.17.20"));
        assertNull(SecurityAnalysisService.coordinate(null));
    }

    @Test
    @DisplayName("tool severities normalise to the platform scale")
    void severities() {
        assertEquals("HIGH", SecurityAnalysisService.semgrepSeverity("ERROR"));
        assertEquals("MEDIUM", SecurityAnalysisService.semgrepSeverity("WARNING"));
        assertEquals("LOW", SecurityAnalysisService.semgrepSeverity("INFO"));
        assertEquals("MEDIUM", SecurityAnalysisService.normalizeSeverity("moderate"));
        assertEquals("CRITICAL", SecurityAnalysisService.normalizeSeverity("CRITICAL"));
        assertEquals("INFO", SecurityAnalysisService.normalizeSeverity(null));
        assertTrue(SecurityAnalysisService.severityRank("CRITICAL") < SecurityAnalysisService.severityRank("LOW"));
    }
}
