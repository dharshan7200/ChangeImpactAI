package com.changeimpact.analysis.jobs;

import java.util.UUID;

public record ChangeAnalysisJobMessage(UUID jobId, UUID analysisId) {}
