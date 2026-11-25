package com.changeimpact.common.logging;

import com.changeimpact.common.metrics.PlatformMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.function.BiConsumer;

/** Sink for job stage timings: recorded as {@code changeimpact_pipeline_stage_seconds} (see {@link PlatformMetrics}). */
@Configuration
public class StageTimingsConfig {

    @Bean
    public BiConsumer<String, Map<String, Long>> stageTimings() {
        return PlatformMetrics::stageTimings;
    }
}
