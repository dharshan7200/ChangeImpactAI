package com.changeimpact.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * The platform's own design rules, checked on its bytecode with ArchUnit (customer repositories are never compiled,
 * so ArchUnit cannot run on them; their rules use the graph-based engine instead).
 */
@DisplayName("Platform architecture (ArchUnit)")
class PlatformArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.changeimpact");
    }

    @Test
    @DisplayName("deterministic analysis does not depend on the AI features")
    void deterministicAnalysisIsIndependentOfAi() {
        noClasses().that().resideInAnyPackage("com.changeimpact.parser..", "com.changeimpact.diff..", "com.changeimpact.impact..",
                        "com.changeimpact.risk..", "com.changeimpact.graph..", "com.changeimpact.symbols..", "com.changeimpact.history..",
                        "com.changeimpact.testing..", "com.changeimpact.securityanalysis..", "com.changeimpact.analysis.service..",
                        "com.changeimpact.ingestion..", "com.changeimpact.reporting..", "com.changeimpact.defect..",
                        "com.changeimpact.architecture..")
                .should().dependOnClassesThat().resideInAnyPackage("com.changeimpact.ai..", "com.changeimpact.rag..")
                .because("findings must be computed without a language model; AI only explains finished results")
                .check(classes);
    }

    @Test
    @DisplayName("only the Semgrep runner starts external processes (repository code is never executed)")
    void onlySemgrepRunnerStartsProcesses() {
        noClasses().that().doNotHaveSimpleName("SemgrepRunner")
                .should().dependOnClassesThat().areAssignableTo(ProcessBuilder.class)
                .orShould(callRuntimeExec())
                .check(classes);
    }

    private static ArchCondition<JavaClass> callRuntimeExec() {
        return new ArchCondition<>("call Runtime.exec") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    if (call.getTargetOwner().isEquivalentTo(Runtime.class) && call.getName().equals("exec")) {
                        events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                    }
                }
            }
        };
    }

    @Test
    @DisplayName("JPA entities do not depend on services or controllers")
    void entitiesStayPlain() {
        noClasses().that().resideInAPackage("..entity..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..")
                .check(classes);
    }

    @Test
    @DisplayName("services do not depend on web controllers")
    void servicesDoNotUseControllers() {
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .check(classes);
    }

    @Test
    @DisplayName("constructor injection only (no @Autowired fields)")
    void noFieldInjection() {
        noFields().should().beAnnotatedWith(Autowired.class).check(classes);
    }
}
