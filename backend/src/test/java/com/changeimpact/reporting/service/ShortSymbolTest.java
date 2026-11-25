package com.changeimpact.reporting.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Short symbol names in Markdown / Check Run output")
class ShortSymbolTest {

    @Test
    void shortens() {
        assertEquals("PetControllerTests.ProcessUpdateFormHasErrors#processUpdateFormWithDuplicateName",
                ImpactReportService.shortSymbol("org.springframework.samples.petclinic.owner.PetControllerTests$ProcessUpdateFormHasErrors#processUpdateFormWithDuplicateName"));
        assertEquals("Owner#addPet(…)", ImpactReportService.shortSymbol("org.springframework.samples.petclinic.owner.Owner#addPet(Pet)"));
        assertEquals("Vet#getSpecialties()", ImpactReportService.shortSymbol("p.Vet#getSpecialties()"));
        assertEquals("Owner", ImpactReportService.shortSymbol("org.springframework.samples.petclinic.owner.Owner"));
    }
}
