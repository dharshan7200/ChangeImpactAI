package com.changeimpact.analysis.jobs;

import com.changeimpact.analysis.service.ChangeAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Worker for change impact analyses. Failures are persisted by the service, then dead-lettered. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChangeAnalysisConsumer {

    private final ChangeAnalysisService changeAnalysisService;

    @RabbitListener(queues = "${app.rabbitmq.queues.analysis-job}", containerFactory = "rabbitListenerContainerFactory")
    public void handle(ChangeAnalysisJobMessage message) {
        MDC.put("jobId", String.valueOf(message.jobId()));
        try {
            changeAnalysisService.execute(message.analysisId());
        } catch (Exception e) {
            log.error("Change analysis {} failed and is dead-lettered: {}", message.analysisId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Change analysis " + message.analysisId() + " failed", e);
        } finally {
            MDC.remove("jobId");
        }
    }
}
