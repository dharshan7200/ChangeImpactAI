package com.changeimpact.github.webhook;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** X-GitHub-Delivery: GitHub's unique id for this delivery (de-duplication key). */
    @Column(nullable = false, unique = true)
    private String deliveryId;

    /** X-GitHub-Event, e.g. pull_request, push, installation, ping. */
    @Column(name = "event_type", nullable = false, length = 100)
    private String event;

    @Column(length = 100)
    private String action;

    private Long installationId;

    @Column(length = 512)
    private String repositoryFullName;

    /** SHA-256 of the raw request body. */
    private String payloadHash;

    /** ACCEPTED, IGNORED, FAILED */
    @Column(nullable = false, length = 20)
    private String status;

    /** Why the delivery was ignored, or what was started. */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** Processing error of a FAILED delivery. */
    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(nullable = false)
    private boolean processed;

    private java.time.Instant processedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private java.util.List<String> analysisIds;

    @org.hibernate.annotations.CreationTimestamp
    @Column(nullable = false, updatable = false)
    private java.time.Instant receivedAt;
}
