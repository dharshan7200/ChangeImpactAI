package com.changeimpact.testing.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PIT killing-test names")
class MutationQualityServiceTest {

    @Test
    @DisplayName("JUnit 5 unique ids from a real PIT report are shortened to Class#method")
    void shortNames() {
        assertEquals("PetControllerTests#processCreationFormSuccess", MutationQualityService.shortTestName(
                "org.springframework.samples.petclinic.owner.PetControllerTests.[engine:junit-jupiter]/[class:org.springframework.samples.petclinic.owner.PetControllerTests]/[method:processCreationFormSuccess()]"));
        assertEquals("RiskScoringServiceTest", MutationQualityService.shortTestName("com.changeimpact.risk.service.RiskScoringServiceTest"));
    }
}
