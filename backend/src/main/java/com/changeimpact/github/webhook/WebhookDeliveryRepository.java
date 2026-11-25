package com.changeimpact.github.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {
    boolean existsByDeliveryId(String deliveryId);

    Optional<WebhookDelivery> findByDeliveryId(String deliveryId);

    List<WebhookDelivery> findTop50ByRepositoryFullNameInOrderByReceivedAtDesc(Collection<String> repositoryFullNames);
}
