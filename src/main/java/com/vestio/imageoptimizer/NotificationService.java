package com.vestio.imageoptimizer;

import com.vestio.common.entity.enums.NotificationType;

import com.vestio.common.entity.Notification;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Notification notifyUser(UUID userId, NotificationType type, Map<String, Object> params) {
        Notification notification = Notification.builder()
                .userId(userId)
                .type(type)
                .params(params == null ? Map.of() : Map.copyOf(params))
                .build();
        Notification saved = notificationRepository.saveAndFlush(notification);
        eventPublisher.publishEvent(new NotificationCreated(saved));
        return saved;
    }

    @Transactional
    public Notification notifyUserListingReady(UUID userId, String listingTitle, UUID listingId) {
        Map<String, Object> params = new HashMap<>();
        if (listingTitle != null) params.put("listingTitle", listingTitle);
        if (listingId != null) params.put("listingId", listingId);
        return notifyUser(userId, NotificationType.LISTING_READY, params);
    }
}
