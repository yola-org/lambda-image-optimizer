package com.vestio.imageoptimizer;

import com.vestio.common.publisher.AppSyncEventsPublisher;

import com.vestio.common.publisher.RealtimeChannelsNamespace;

import com.vestio.common.entity.NotificationOutbox;

import com.vestio.common.event.NotificationEvent;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
class NotificationDeliveryService {
    private final EntityManager entityManager;
    private final AppSyncEventsPublisher publisher;
    private final ObjectMapper objectMapper;

    NotificationDeliveryService(EntityManager entityManager, AppSyncEventsPublisher publisher, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void publishForListing(UUID listingId) {
        var pending = entityManager.createQuery("""
                select n from NotificationOutbox n
                where n.listingId = :listingId and n.publishedAt is null order by n.createdAt, n.id
                """, NotificationOutbox.class)
                .setParameter("listingId", listingId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (var notification : pending) publish(notification);
    }

    @Transactional
    public void publishNotification(UUID notificationId) {
        NotificationOutbox notification = entityManager.find(NotificationOutbox.class, notificationId, LockModeType.PESSIMISTIC_WRITE);
        if (notification != null && notification.getPublishedAt() == null) publish(notification);
    }

    private void publish(NotificationOutbox notification) {
        NotificationEvent event = objectMapper.readValue(notification.getPayload(), NotificationEvent.class);
        publisher.dispatchEvent(event, RealtimeChannelsNamespace.NOTIFICATIONS_NAMESPACE, notification.getUserId()).join();
        notification.setPublishedAt(LocalDateTime.now());
    }
}
