package com.changeimpact.analysis.service;

import java.util.UUID;

/** Published (after the corresponding transaction committed) when a change analysis starts, completes or fails. */
public record ChangeAnalysisEvent(UUID analysisId, Phase phase) {
    public enum Phase { STARTED, COMPLETED, FAILED }
}
