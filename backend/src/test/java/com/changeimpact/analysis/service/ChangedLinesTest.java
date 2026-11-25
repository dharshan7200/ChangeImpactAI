package com.changeimpact.analysis.service;

import com.changeimpact.diff.model.Hunk;
import com.changeimpact.diff.semantic.SymbolChange;
import com.changeimpact.diff.semantic.SymbolSnapshot;
import com.changeimpact.parser.model.SymbolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Changed lines of a symbol (input of changed-line coverage)")
class ChangedLinesTest {

    private static SymbolSnapshot sym(String qn, SymbolType type, int start, int end) {
        return new SymbolSnapshot(UUID.randomUUID(), qn, qn, type, null, "PUBLIC", null, "", false, false, false, null, "h",
                Set.of(), "src/main/java/p/A.java", start, end, false);
    }

    private static final Map<String, List<Hunk>> HUNKS = Map.of("src/main/java/p/A.java",
            List.of(new Hunk(8, 9, 8, 10), new Hunk(20, 19, 22, 23), new Hunk(40, 41, 44, 43)));

    @Test
    @DisplayName("modified member: only the head lines of overlapping hunks, clipped to the member")
    void modified() {
        var change = new SymbolChange(sym("p.A#m()", SymbolType.METHOD, 5, 9), sym("p.A#m()", SymbolType.METHOD, 9, 23));
        assertEquals(List.of(9, 10, 22, 23), ChangeAnalysisService.changedLines(change, HUNKS));
    }

    @Test
    @DisplayName("added member: all its lines; removed member and types: none (no double counting of nested members)")
    void addedRemovedTypes() {
        assertEquals(List.of(30, 31, 32), ChangeAnalysisService.changedLines(new SymbolChange(null, sym("p.A#n()", SymbolType.METHOD, 30, 32)), HUNKS));
        assertEquals(List.of(), ChangeAnalysisService.changedLines(new SymbolChange(sym("p.A#o()", SymbolType.METHOD, 1, 3), null), HUNKS));
        assertEquals(List.of(), ChangeAnalysisService.changedLines(
                new SymbolChange(sym("p.A", SymbolType.CLASS, 1, 90), sym("p.A", SymbolType.CLASS, 1, 95)), HUNKS));
    }

    @Test
    @DisplayName("pure deletions inside a member contribute no head lines; config-only changes have none")
    void deletionsOnly() {
        var change = new SymbolChange(sym("p.A#q()", SymbolType.METHOD, 38, 50), sym("p.A#q()", SymbolType.METHOD, 40, 50));
        assertEquals(List.of(), ChangeAnalysisService.changedLines(change, HUNKS), "hunk 40-41 → 44-43 is a pure deletion");
    }
}
