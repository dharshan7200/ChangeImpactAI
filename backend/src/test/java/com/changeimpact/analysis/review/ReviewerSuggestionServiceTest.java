package com.changeimpact.analysis.review;

import com.changeimpact.analysis.review.ReviewerSuggestionService.Suggestion;
import com.changeimpact.analysis.review.ReviewerSuggestionService.Touch;
import com.changeimpact.reporting.service.ImpactReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Reviewer suggestions and pre-merge checklist")
class ReviewerSuggestionServiceTest {

    static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    static Touch touch(String path, String email, int daysAgo) {
        return new Touch(path, email, email.substring(0, email.indexOf('@')), NOW.minus(Duration.ofDays(daysAgo)));
    }

    @Test
    @DisplayName("score = share of each changed file (+ half the share of impacted files), recent work counts more")
    void ranking() {
        List<Touch> t = List.of(
                touch("A.java", "alice@x.io", 10), touch("A.java", "alice@x.io", 20), touch("A.java", "bob@x.io", 30),
                touch("B.java", "bob@x.io", 5),                                      // impacted only
                touch("C.java", "carol@x.io", 3 * 365), touch("C.java", "dave@x.io", 5)); // changed: dave is recent
        List<Suggestion> s = ReviewerSuggestionService.rank(t, Set.of("A.java", "C.java"), Set.of(), NOW);
        // dave ≈ 0.89 of C; bob ≈ 0.33 of A + 0.5 × all of B = 0.83; alice ≈ 0.67 of A; carol ≈ 0.11 of C
        assertEquals(List.of("dave@x.io", "bob@x.io", "alice@x.io", "carol@x.io"), s.stream().map(Suggestion::email).toList());
        assertEquals(0.89, s.get(0).score(), 0.01);
        assertEquals(0.83, s.get(1).score(), 0.01);
        Suggestion bob = s.get(1);
        assertEquals(1, bob.changedFilesOwned());
        assertEquals(1, bob.impactedFilesOwned());
        assertEquals("CHANGED", bob.evidence().get(0).relation(), "changed files listed first");
        // dave: 1 recent commit vs carol's 3-year-old one → share 8/9 of C.java
        assertEquals(0.89, s.get(0).evidence().get(0).share(), 0.01);
    }

    @Test
    @DisplayName("authors of the change and bots are never suggested")
    void exclusions() {
        List<Touch> t = List.of(touch("A.java", "alice@x.io", 1), touch("A.java", "dependabot[bot]@users.noreply.github.com", 1),
                touch("A.java", "bob@x.io", 2));
        List<Suggestion> s = ReviewerSuggestionService.rank(t, Set.of("A.java"), Set.of("alice@x.io"), NOW);
        assertEquals(List.of("bob@x.io"), s.stream().map(Suggestion::email).toList());
        assertTrue(ReviewerSuggestionService.rank(List.of(), Set.of("A.java"), Set.of(), NOW).isEmpty());
        // the same person under another address is still excluded (by name)
        List<Touch> twoAddresses = List.of(new Touch("A.java", "gary@old.example", "Gary  Gregory", NOW), touch("A.java", "bob@x.io", 2));
        assertEquals(List.of("bob@x.io"), ReviewerSuggestionService.rank(twoAddresses, Set.of("A.java"),
                Set.of("gary@new.example", ReviewerSuggestionService.nameKey("Gary Gregory")), NOW).stream().map(Suggestion::email).toList());
    }

    @Test
    @DisplayName("checklist items are derived from breaking changes, tests, security, architecture and reviewers")
    void checklist() {
        Map<String, Object> report = Map.of(
                "changedSymbols", List.of(Map.of("qualifiedName", "a.Foo#bar(int)", "breaking", true, "test", false)),
                "tests", Map.of("selected", List.of(Map.of("test", "a.FooTest#bar()", "executionStatus", "FAILED")),
                        "missingTests", List.of()),
                "security", Map.of("findings", List.of(Map.of("severity", "HIGH", "title", "SQL injection"))),
                "architecture", Map.of("status", "OK", "newCycles", List.of(List.of("a.x", "a.y")), "addedDependencyCount", 1,
                        "addedDependencies", List.of(Map.of("source", "a.x", "target", "a.y")), "newViolationCount", 0),
                "reviewers", Map.of("suggestions", List.of(Map.of("name", "Bob"))));
        List<Map<String, Object>> c = ImpactReportService.checklist(report);
        List<String> areas = c.stream().map(i -> (String) i.get("area")).toList();
        assertEquals(List.of("REVIEW", "TESTS", "TESTS", "SECURITY", "ARCHITECTURE", "ARCHITECTURE", "REVIEW"), areas, c.toString());
        assertTrue(((String) c.get(1).get("action")).startsWith("Fix 1 failing test"));
        assertEquals(List.of("a.x → a.y"), c.get(5).get("evidence"));
        assertEquals("No specific checks were derived — a standard review is enough",
                ImpactReportService.checklist(Map.of()).get(0).get("action"));
    }
}
