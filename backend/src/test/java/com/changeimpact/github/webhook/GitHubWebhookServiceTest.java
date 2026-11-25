package com.changeimpact.github.webhook;

import com.changeimpact.common.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("Webhook delivery registration (de-duplication vs GitHub redeliveries)")
class GitHubWebhookServiceTest {

    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final GitHubWebhookService service = new GitHubWebhookService(new AppProperties(), deliveries, null, null, null, null,
            null, null, null, new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private WebhookDelivery existing(String status, Instant received) {
        WebhookDelivery d = WebhookDelivery.builder().deliveryId("d1").event("pull_request").status(status).receivedAt(received).build();
        when(deliveries.findByDeliveryId("d1")).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    @DisplayName("new delivery is registered")
    void fresh() {
        when(deliveries.findByDeliveryId("d1")).thenReturn(Optional.empty());
        assertTrue(service.register("d1", "pull_request", "h"));
        verify(deliveries).saveAndFlush(any());
    }

    @Test
    @DisplayName("processed deliveries are duplicates; a FAILED or stuck one is processed again on redelivery")
    void redelivery() {
        existing("ACCEPTED", Instant.now());
        assertFalse(service.register("d1", "pull_request", "h"));
        existing("PROCESSING", Instant.now());
        assertFalse(service.register("d1", "pull_request", "h"), "still being processed");
        WebhookDelivery failed = existing("FAILED", Instant.now());
        failed.setError("boom");
        assertTrue(service.register("d1", "pull_request", "h"));
        assertEquals("PROCESSING", failed.getStatus());
        assertNull(failed.getError());
        existing("PROCESSING", Instant.now().minusSeconds(3600));
        assertTrue(service.register("d1", "pull_request", "h"), "stuck for an hour (e.g. crash)");
    }
}
