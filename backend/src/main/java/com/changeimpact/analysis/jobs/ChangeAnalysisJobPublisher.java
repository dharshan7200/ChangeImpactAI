package com.changeimpact.analysis.jobs;

import com.changeimpact.analysis.entity.ChangeAnalysis;
import com.changeimpact.analysis.repository.ChangeAnalysisRepository;
import com.changeimpact.analysis.service.ChangeAnalysisService;
import com.changeimpact.common.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class ChangeAnalysisJobPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AppProperties appProperties;
    private final ObjectProvider<ChangeAnalysisService> changeAnalysisServiceProvider;
    private final ChangeAnalysisRepository changeAnalysisRepository;
    private final TaskExecutor taskExecutor;

    @Value("${spring.rabbitmq.listener.simple.auto-startup:true}")
    private boolean rabbitAutoStartup;

    public ChangeAnalysisJobPublisher(RabbitTemplate rabbitTemplate,
                                     AppProperties appProperties,
                                     ObjectProvider<ChangeAnalysisService> changeAnalysisServiceProvider,
                                     ChangeAnalysisRepository changeAnalysisRepository,
                                     @Qualifier("applicationTaskExecutor") @Lazy TaskExecutor taskExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.appProperties = appProperties;
        this.changeAnalysisServiceProvider = changeAnalysisServiceProvider;
        this.changeAnalysisRepository = changeAnalysisRepository;
        this.taskExecutor = taskExecutor;
    }

    public void publish(UUID jobId, UUID analysisId) {
        if (!rabbitAutoStartup) {
            log.info("RabbitMQ consumers disabled (standalone mode). Executing change analysis {} (job {}) asynchronously.",
                    analysisId, jobId);
            runAsync(analysisId);
            return;
        }

        try {
            log.info("Publishing change analysis {} (job {})", analysisId, jobId);
            rabbitTemplate.convertAndSend(appProperties.getRabbitmq().getExchanges().getMain(), "analysis.job",
                    new ChangeAnalysisJobMessage(jobId, analysisId));
        } catch (Exception e) {
            log.warn("RabbitMQ publish failed ({}); falling back to local async execution for change analysis {}",
                    e.getMessage(), analysisId);
            runAsync(analysisId);
        }
    }

    private void runAsync(UUID analysisId) {
        taskExecutor.execute(() -> {
            try {
                ChangeAnalysisService changeAnalysisService = changeAnalysisServiceProvider.getIfAvailable();
                if (changeAnalysisService != null) {
                    changeAnalysisService.execute(analysisId);
                } else {
                    log.error("ChangeAnalysisService bean not available to execute change analysis {}", analysisId);
                }
            } catch (Exception e) {
                log.error("Async execution failed for change analysis {}: {}", analysisId, e.getMessage(), e);
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckAnalyses() {
        if (!rabbitAutoStartup) {
            try {
                List<ChangeAnalysis> queuedAnalyses = changeAnalysisRepository.findByStatus(ChangeAnalysis.Status.QUEUED);
                if (!queuedAnalyses.isEmpty()) {
                    log.info("Found {} stuck QUEUED change analyses on startup. Triggering async processing...", queuedAnalyses.size());
                    for (ChangeAnalysis analysis : queuedAnalyses) {
                        log.info("Auto-recovering stuck change analysis {}", analysis.getId());
                        runAsync(analysis.getId());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to recover stuck change analyses on startup: {}", e.getMessage(), e);
            }
        }
    }
}
