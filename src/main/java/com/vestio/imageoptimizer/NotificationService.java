package com.vestio.imageoptimizer;

import com.vestio.common.publisher.AppSyncEventsPublisher;

import com.vestio.common.entity.enums.NotificationType;

import com.vestio.common.entity.Notification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vestio.common.event.payload.NotificationPayload;
import com.vestio.common.event.payload.ListingNotificationPayload;
import tools.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final AppSyncEventsPublisher publisher;

    @Transactional
    public Notification notifyUser(UUID userId, NotificationType type, NotificationPayload payload) {
        return notifyUser(userId, type, payload, null);
    }

    @Transactional
    public Notification notifyUser(UUID userId, NotificationType type, NotificationPayload payload, UUID listingId) {
        Notification notification = Notification.builder()
                .userId(userId)
                .type(type)
                .payload(Objects.requireNonNull(payload, "Notification payload is required").toMap(objectMapper))
                .listingId(listingId)
                .build();
        Notification saved = notificationRepository.saveAndFlush(notification);
        publisher.dispatchNotificationEvent(saved).join();
        return saved;
    }

    @Transactional
    public Notification notifyUserListingPublished(UUID userId, String listingTitle, UUID listingId) {
        NotificationPayload payload = new ListingNotificationPayload(listingTitle);
        return notifyUser(userId, NotificationType.LISTING_PUBLISHED, payload, listingId);
    }
}
