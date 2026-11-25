package com.changeimpact.analysis.jobs;

import com.changeimpact.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChangeAnalysisJobPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;

    public void publish(UUID jobId, UUID analysisId) {
        log.info("Publishing change analysis {} (job {})", analysisId, jobId);
        rabbitTemplate.convertAndSend(appProperties.getRabbitmq().getExchanges().getMain(), "analysis.job",
                new ChangeAnalysisJobMessage(jobId, analysisId));
    }
}
