package com.changeimpact.history.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Heuristic commit labels")
class HistoryLabelTest {

    @Test
    void revertDetection() {
        assertTrue(HistoryService.isRevert("Revert \"Add cache\"\n\nThis reverts commit 1234567abc."));
        assertTrue(HistoryService.isRevert("Undo change\n\nThis reverts commit abcdef1234567."));
        assertFalse(HistoryService.isRevert("Reverting is not mentioned here: add feature"));
    }

    @Test
    void bugfixDetection() {
        assertTrue(HistoryService.isBugfix("Fix NPE in OwnerController"));
        assertTrue(HistoryService.isBugfix("hotfix: handle empty pet name"));
        assertTrue(HistoryService.isBugfix("Closes issue #42 in validator"));
        assertFalse(HistoryService.isBugfix("Add visit scheduling"));
        assertFalse(HistoryService.isBugfix("Prefix owner names\n\nfix later"), "only the subject line is used");
    }
}
