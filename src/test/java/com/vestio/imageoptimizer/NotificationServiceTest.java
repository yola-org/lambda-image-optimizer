package com.vestio.imageoptimizer;

import com.vestio.common.event.NotificationCreated;

import com.vestio.common.entity.enums.NotificationType;

import com.vestio.common.publisher.AppSyncEventsPublisher;

import com.vestio.common.publisher.RealtimeChannelsNamespace;

import com.vestio.common.entity.NotificationOutbox;

import com.vestio.common.entity.Notification;

import com.vestio.common.event.BaseEvent;

import com.vestio.common.event.NotificationEvent;

import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    @Test
    void savesAndDeliversNotificationWithoutListingId() {
        var mapper = JsonMapper.builder().build();
        UUID userId = UUID.randomUUID();
        UUID[] id = new UUID[1];
        try (var factory = new Configuration().addAnnotatedClass(Notification.class).addAnnotatedClass(NotificationOutbox.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:generic-notifications").buildSessionFactory()) {
            factory.inTransaction(session -> {
                session.createNativeMutationQuery("CREATE TABLE notifications (id UUID PRIMARY KEY, user_id UUID, type VARCHAR(128), params JSON, created_at TIMESTAMP, read_at TIMESTAMP)").executeUpdate();
                session.createNativeMutationQuery("CREATE TABLE notification_outbox (notification_id UUID PRIMARY KEY REFERENCES notifications(id), listing_id UUID, user_id UUID, payload VARCHAR, created_at TIMESTAMP, published_at TIMESTAMP)").executeUpdate();
                NotificationRepository repository = mock(NotificationRepository.class);
                when(repository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
                    Notification notification = invocation.getArgument(0);
                    session.persist(notification);
                    session.flush();
                    return notification;
                });
                var dispatcher = new NotificationDispatcher(session, mapper);
                var service = new NotificationService(repository, event -> dispatcher.onNotificationCreated((NotificationCreated) event));
                var notification = service.notifyUser(userId, NotificationType.OFFER_CREATED, Map.of("offerId", UUID.randomUUID()));
                id[0] = notification.getId();
                assertNotNull(notification.getCreatedAt());
                assertNull(notification.getReadAt());
                assertFalse(notification.getParams().containsKey("listingId"));
            });
            factory.inTransaction(session -> {
                var outbox = session.find(NotificationOutbox.class, id[0]);
                assertNull(outbox.getListingId());
                assertEquals(userId, outbox.getUserId());
            });
            AppSyncEventsPublisher publisher = mock(AppSyncEventsPublisher.class);
            when(publisher.dispatchEvent(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
            factory.inTransaction(session -> new NotificationDeliveryService(session, publisher, mapper).publishNotification(id[0]));
            factory.inTransaction(session -> new NotificationDeliveryService(session, publisher, mapper).publishNotification(id[0]));
            var event = org.mockito.ArgumentCaptor.forClass(BaseEvent.class);
            verify(publisher).dispatchEvent(event.capture(), eq(RealtimeChannelsNamespace.NOTIFICATIONS_NAMESPACE), eq(userId));
            NotificationEvent notificationEvent = (NotificationEvent) event.getValue();
            assertEquals(id[0], notificationEvent.getId());
            assertEquals("notification-event", notificationEvent.getEventType());
            assertEquals(NotificationType.OFFER_CREATED, notificationEvent.getNotification().getType());
            assertFalse(notificationEvent.getNotification().getParams().containsKey("listingId"));
        }
    }
}
