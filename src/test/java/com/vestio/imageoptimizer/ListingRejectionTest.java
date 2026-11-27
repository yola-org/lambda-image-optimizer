package com.vestio.imageoptimizer;

import com.vestio.common.entity.Notification;
import com.vestio.common.entity.enums.NotificationType;
import com.vestio.common.event.Event;
import com.vestio.common.publisher.AppSyncEventsPublisher;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingRejectionTest {
    @Test
    void notifiesOwnerOnRejectionWithRetriesAndIgnoresDuplicateAndObsoleteMessages() {
        UUID listingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID oldAsset = UUID.randomUUID();
        UUID currentAsset = UUID.randomUUID();
        ImageOptimizer optimizer = mock(ImageOptimizer.class);
        AppSyncEventsPublisher publisher = mock(AppSyncEventsPublisher.class);
        when(publisher.dispatchNotificationEvent(any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("AppSync unavailable")))
                .thenReturn(CompletableFuture.completedFuture(null));
        try (SessionFactory factory = new Configuration().addAnnotatedClass(OptimizerListing.class).addAnnotatedClass(Notification.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:rejected-" + listingId + ";MODE=PostgreSQL")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory()) {
            factory.inTransaction(session -> {
                session.createNativeMutationQuery("INSERT INTO listings(id,status,title,user_id) VALUES (:id,'AWAITING_ASSETS','Shirt',:user)")
                        .setParameter("id", listingId).setParameter("user", userId).executeUpdate();
                session.createNativeMutationQuery("INSERT INTO listing_asset_keys(listing_id,position,asset_key) VALUES (:id,0,:asset)")
                        .setParameter("id", listingId).setParameter("asset", currentAsset).executeUpdate();
            });
            factory.inTransaction(session -> {
                ListingStatusUpdater updater = updater(session, optimizer, publisher);
                assertFalse(updater.canProcess(listingId, List.of(oldAsset)));
                assertFalse(updater.canProcess(listingId, List.of()));
                assertTrue(updater.canProcess(listingId, List.of(currentAsset)));
                updater.markRejectedContent(listingId, oldAsset);
            });
            verifyNoInteractions(publisher);
            assertThrows(CompletionException.class, () -> factory.inTransaction(session ->
                    updater(session, optimizer, publisher).markRejectedContent(listingId, currentAsset)));
            factory.inTransaction(session -> {
                assertEquals("AWAITING_ASSETS", status(session));
                assertEquals(0L, session.createQuery("select count(n) from Notification n", Long.class).getSingleResult());
                updater(session, optimizer, publisher).markRejectedContent(listingId, currentAsset);
            });
            factory.inTransaction(session -> {
                assertEquals("REJECTED_CONTENT", status(session));
                ListingStatusUpdater updater = updater(session, optimizer, publisher);
                assertFalse(updater.canProcess(listingId, List.of(currentAsset)));
                updater.markAvailableWhenReady(listingId);
                updater.markRejectedContent(listingId, currentAsset);
            });
            factory.inTransaction(session -> {
                assertEquals("REJECTED_CONTENT", status(session));
                Notification notification = session.createQuery("from Notification", Notification.class).getSingleResult();
                assertEquals(NotificationType.LISTING_REFUSED, notification.getType());
                assertEquals(userId, notification.getUserId());
                assertEquals(listingId, notification.getListingId());
                assertEquals(Map.of("listingTitle", "Shirt"), notification.getPayload());
                var mapper = JsonMapper.builder().build();
                var event = mapper.readTree(mapper.writeValueAsString(Event.notification(notification, mapper)));
                assertEquals("notification", event.path("type").asText());
                assertEquals("listing-refused", event.path("payload").path("type").asText());
                assertNotNull(session.createQuery("select l.updatedAt from OptimizerListing l", Instant.class).getSingleResult());
                session.createNativeMutationQuery("UPDATE listings SET status='AVAILABLE'").executeUpdate();
            });
            factory.inTransaction(session -> {
                updater(session, optimizer, publisher).markRejectedContent(listingId, currentAsset);
            });
            factory.inTransaction(session -> assertEquals("AVAILABLE", status(session)));
        }
        verifyNoInteractions(optimizer);
        verify(publisher, times(2)).dispatchNotificationEvent(argThat(notification ->
                notification.getType() == NotificationType.LISTING_REFUSED
                        && listingId.equals(notification.getListingId()) && userId.equals(notification.getUserId())));
    }

    private static ListingStatusUpdater updater(Session session, ImageOptimizer optimizer, AppSyncEventsPublisher publisher) {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            session.persist(notification);
            session.flush();
            return notification;
        });
        return new ListingStatusUpdater(session, optimizer,
                new NotificationService(repository, JsonMapper.builder().build(), publisher));
    }

    private static String status(Session session) {
        return session.createNativeQuery("SELECT status FROM listings", String.class).getSingleResult();
    }
}
