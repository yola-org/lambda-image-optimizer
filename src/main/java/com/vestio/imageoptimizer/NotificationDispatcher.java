package com.vestio.imageoptimizer;

import com.vestio.common.entity.NotificationOutbox;

import com.vestio.common.entity.Notification;

import com.vestio.common.event.NotificationEvent;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationDispatcher {
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    // Save the backend's event envelope before commit, so SQS can retry delivery afterward.
    @EventListener
    public void onNotificationCreated(NotificationCreated created) {
        Notification notification = created.notification();
        NotificationEvent event = new NotificationEvent(notification);
        entityManager.persist(NotificationOutbox.builder()
                .id(notification.getId())
                .userId(notification.getUserId())
                .listingId(listingId(notification))
                .payload(objectMapper.writeValueAsString(event))
                .createdAt(notification.getCreatedAt())
                .build());
    }

    private UUID listingId(Notification notification) {
        Object value = notification.getParams().get("listingId");
        if (value instanceof UUID id) return id;
        if (value instanceof String id) {
            try { return UUID.fromString(id); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }
}
