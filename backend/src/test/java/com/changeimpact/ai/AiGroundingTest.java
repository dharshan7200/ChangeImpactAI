package com.changeimpact.ai;

import com.changeimpact.ai.review.AiReviewMarkdown;
import com.changeimpact.ai.review.ReviewValidator;
import com.changeimpact.rag.RagServiceAccess;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic guards around the language model: citation / mention validation, query building, retry parsing. */
class AiGroundingTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ReviewValidator validator = new ReviewValidator(Set.of("C1", "I1", "T1"),
            "public String processFindForm(int page, Owner owner) { ownersResults.isEmpty(); }",
            Set.of("processFindForm", "OwnerController", "Owner", "findPaginatedForOwnersLastName"));

    private JsonNode ids(String... ids) { return json.valueToTree(ids); }

    @Test
    void verifiedNeedsAValidCitationAndOnlyKnownIdentifiers() {
        var v = validator.check(ids("C1", "I1"), "`processFindForm` redirects before `ownersResults.isEmpty()` is checked");
        assertThat(v.status()).isEqualTo("VERIFIED");
        assertThat(v.validCitations()).containsExactly("C1", "I1");
    }

    @Test
    void inventedCitationsAreDroppedAndAFindingWithoutValidOnesIsUnverified() {
        var v = validator.check(ids("C9", "[X1]"), "`processFindForm` is risky");
        assertThat(v.status()).isEqualTo("UNVERIFIED");
        assertThat(v.invalidCitations()).containsExactly("C9", "X1");
    }

    @Test
    void inventedSymbolsMakeAFindingUnverified() {
        var v = validator.check(ids("C1"), "`OwnerService.deleteAllOwners` is no longer called; `processFindForm` changed");
        assertThat(v.status()).isEqualTo("UNVERIFIED");
        assertThat(v.unknownMentions()).containsExactly("OwnerService.deleteAllOwners");
    }

    @Test
    void commonWordsAndLiteralsInBackticksAreAccepted() {
        assertThat(validator.check(ids("T1"), "returns `null` when `page > 1`; see `Owner#getLastName`".replace("#getLastName", "")).status())
                .isEqualTo("VERIFIED");
    }

    @Test
    void longQualifiedNamesInBackticksDoNotShiftThePairing() {
        // production verification 2026-11-23: a 125-character name in backticks made the prose ", adding" and
        // "redirects out-of-bounds" count as unknown code names
        String longName = "org.springframework.samples.petclinic.owner.OwnerController#processFindForm(int,Owner,BindingResult,Model,RedirectAttributes)";
        var v = new ReviewValidator(Set.of("C1"), longName + " int page, RedirectAttributes redirectAttributes", Set.of("OwnerController", "processFindForm", "Owner"))
                .check(ids("C1"), "In `" + longName + "`, adding `RedirectAttributes redirectAttributes` redirects out-of-bounds `page` requests");
        assertThat(v.unknownMentions()).isEmpty();
        assertThat(v.status()).isEqualTo("VERIFIED");
    }

    @Test
    void literalsWithoutIdentifiersNameNoCodeAndAreAccepted() {
        // review-v3 evaluation: "inputs like `[[`" was wrongly reported as an unknown name
        var v = validator.check(ids("C1"), "`processFindForm` now rejects inputs like `[[`, `404` or `\"\"`");
        assertThat(v.unknownMentions()).isEmpty();
        assertThat(v.status()).isEqualTo("VERIFIED");
    }

    @Test
    void codeShapedNamesInPlainTextAreCheckedToo() {
        var invented = validator.check(ids("C1"), "OwnerController#processFindForm now calls ownerAuditLog and purge_stale_owners first");
        assertThat(invented.status()).isEqualTo("UNVERIFIED");
        assertThat(invented.unknownMentions()).containsExactly("ownerAuditLog", "purge_stale_owners");
        var real = validator.check(ids("C1"), "OwnerController#processFindForm checks ownersResults.isEmpty() after redirecting "
                + "(see https://github.com/x/y_z#readmeSection); JaCoCo and GitHub data, e.g. page 2, are fine");
        assertThat(real.unknownMentions()).isEmpty();
        assertThat(real.status()).isEqualTo("VERIFIED");
    }

    @Test
    void markdownExportKeepsValidationStatusAndLabelsTheModelOutput() {
        assertThat(AiReviewMarkdown.section(Map.of("status", "NOT_REQUESTED"))).isEmpty();
        String md = AiReviewMarkdown.section(Map.of("status", "COMPLETED", "model", "m", "promptVersion", "review-v2",
                "result", Map.of("summary", "Adds a check.", "findings", List.of(
                        Map.of("severity", "LOW", "title", "A | B", "explanation", "line1\nline2", "status", "VERIFIED", "citations", List.of("C1")),
                        Map.of("severity", "HIGH", "title", "Invented", "explanation", "x", "status", "UNVERIFIED",
                                "reason", "no valid evidence cited", "citations", List.of())),
                        "testSuggestions", List.of(), "questionsForAuthor", List.of("Why?"))));
        assertThat(md).contains("## AI review (language model — check before acting)", "| LOW | **A \\| B** line1 line2 | verified | C1 |",
                "| HIGH | **Invented** x | unverified (no valid evidence cited) |  |", "- Why?");
    }

    @Test
    void anIndexBuildWaitingForQuotaDoesNotHoldUpAQuery() throws Exception {
        GeminiClient client = new GeminiClient(true, "k", "http://127.0.0.1:9", "c", "f", "e", 768, 5, 10);
        client.throttle(9, 9);                                       // index batch: 9 of its 9 (90 %)
        Thread index = new Thread(() -> {
            try { client.throttle(9, 9); } catch (RuntimeException expected) { /* interrupted below */ }
        });
        index.start();
        Thread.sleep(300);                                           // the next batch is now waiting for the window
        long t = System.nanoTime();
        client.throttle(1, 10);                                      // a search may use the reserved tenth
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(1000);
        assertThat(index.isAlive()).isTrue();
        index.interrupt();
        index.join(5000);
    }

    @Test
    void autoModeTreatsCodeNamesAsCodeAndQuestionsAsNaturalLanguage() {
        for (String code : new String[]{"findByLastNameStartingWith", "PetValidator", "Owner.addVisit", "should_strip_auth",
                "OwnerController#processFindForm", "HTTPAdapter send", "`vets`"}) {
            assertThat(RagServiceAccess.codeLike(code)).as(code).isTrue();
        }
        for (String text : new String[]{"how are vets cached", "veterinarians as JSON or XML", "set the Content-Length header",
                "verify TLS certificates", "has the entity been saved to the database yet"}) {
            assertThat(RagServiceAccess.codeLike(text)).as(text).isFalse();
        }
    }

    @Test
    void aDailyQuotaIsRecognisedSoCallersFailFastInsteadOfRetrying() {
        String body = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded ... Please retry in 17h8m16.25s.\","
                + "\"details\":[{\"violations\":[{\"quotaId\":\"EmbedContentRequestsPerDayPerProjectPerModel-FreeTier\"}]}]}}";
        assertThat(GeminiClient.dailyQuotaExhausted(body)).isTrue();
        assertThat(GeminiClient.dailyQuotaExhausted("{\"quotaId\":\"EmbedContentRequestsPerMinutePerProjectPerModel-FreeTier\"}")).isFalse();
        assertThat(GeminiClient.quotaMessage("embedding", body)).contains("daily Gemini quota for embedding", "retry in 17h8m");
    }

    @Test
    void searchQueryIsCamelCaseSplitWithoutStopWords() {
        assertThat(RagServiceAccess.tsQuery("How are owners found by lastName?")).isEqualTo("owners | found | last | name");
        assertThat(RagServiceAccess.tsQuery("the of a")).isEmpty();
    }

    @Test
    void retryDelayIsReadFromGemini429() {
        String body = "{\"error\":{\"code\":429,\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\": \"34s\"}]}}";
        assertThat(GeminiClient.retryDelayMillis(body, 1000)).isEqualTo(34_500);
        assertThat(GeminiClient.retryDelayMillis("{}", 1000)).isEqualTo(1000);
        assertThat(GeminiClient.retryDelayMillis("\"retryDelay\": \"900s\"", 1000)).isEqualTo(90_000); // capped
    }

    @Test
    void vectorLiteralIsPgvectorSyntax() {
        assertThat(GeminiClient.vectorLiteral(new float[]{0.5f, -1f})).isEqualTo("[0.5,-1.0]");
    }
}
