package com.vestio.imageoptimizer;

import com.vestio.common.publisher.AppSyncEventsPublisher;

import com.vestio.common.entity.enums.NotificationType;

import com.vestio.common.entity.Notification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final AppSyncEventsPublisher publisher;

    @Transactional
    public Notification notifyUser(UUID userId, NotificationType type, Map<String, Object> payload) {
        return notifyUser(userId, type, payload, null);
    }

    @Transactional
    public Notification notifyUser(UUID userId, NotificationType type, Map<String, Object> payload, UUID listingId) {
        Notification notification = Notification.builder()
                .userId(userId)
                .type(type)
                .payload(payload == null ? Map.of() : new java.util.LinkedHashMap<>(payload))
                .listingId(listingId)
                .build();
        Notification saved = notificationRepository.saveAndFlush(notification);
        publisher.dispatchNotificationEvent(saved).join();
        return saved;
    }

    @Transactional
    public Notification notifyUserListingPublished(UUID userId, String listingTitle, UUID listingId) {
        Map<String, Object> payload = new HashMap<>();
        if (listingTitle != null) payload.put("listingTitle", listingTitle);
        return notifyUser(userId, NotificationType.LISTING_PUBLISHED, payload, listingId);
    }
}
