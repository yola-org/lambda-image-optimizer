package com.vestio.imageoptimizer;

import com.vestio.common.entity.Notification;
import com.vestio.common.entity.enums.NotificationType;
import com.vestio.common.publisher.AppSyncEventsPublisher;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ListingStatusUpdaterTest {
    @Test
    void publishesListingOnlyWhenEveryAssetIsReadyAndDoesNotDuplicateNotification() {
        UUID listingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<UUID> ready = new HashSet<>();
        var optimizer = new ImageOptimizer(s3WithRenditions(listingId, ready), new ImageSettings("assets", "secret"));
        var publisher = mock(AppSyncEventsPublisher.class);
        when(publisher.dispatchNotificationEvent(any())).thenReturn(CompletableFuture.completedFuture(null));
        try (var factory = new Configuration().addAnnotatedClass(OptimizerListing.class).addAnnotatedClass(Notification.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:listing-optimizer;MODE=PostgreSQL")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory()) {
            factory.inTransaction(session -> {
                session.createNativeMutationQuery("INSERT INTO listings(id,status,title,user_id) VALUES (:id,'AWAITING_ASSETS','My shirt',:userId)")
                        .setParameter("id", listingId).setParameter("userId", userId).executeUpdate();
                var assets = List.of(first, second);
                for (int i = 0; i < assets.size(); i++) {
                    session.createNativeMutationQuery("INSERT INTO listing_asset_keys(listing_id,position,asset_key) VALUES (:id,:position,:asset)")
                            .setParameter("id", listingId).setParameter("position", i).setParameter("asset", assets.get(i)).executeUpdate();
                }
            });
            ready.add(first);
            factory.inTransaction(session -> update(session, optimizer, publisher, listingId));
            verifyNoInteractions(publisher);
            factory.inTransaction(session -> assertEquals("AWAITING_ASSETS",
                    session.createNativeQuery("SELECT status FROM listings", String.class).getSingleResult()));
            ready.add(second);
            when(publisher.dispatchNotificationEvent(any()))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("AppSync unavailable")))
                    .thenReturn(CompletableFuture.completedFuture(null));
            assertThrows(CompletionException.class, () -> factory.inTransaction(session -> update(session, optimizer, publisher, listingId)));
            factory.inTransaction(session -> {
                assertEquals("AWAITING_ASSETS", session.createNativeQuery("SELECT status FROM listings", String.class).getSingleResult());
                assertEquals(0L, session.createQuery("select count(n) from Notification n", Long.class).getSingleResult());
            });
            factory.inTransaction(session -> update(session, optimizer, publisher, listingId));
            factory.inTransaction(session -> update(session, optimizer, publisher, listingId));
            factory.inTransaction(session -> {
                assertEquals("AVAILABLE", session.createNativeQuery("SELECT status FROM listings", String.class).getSingleResult());
                var saved = session.createQuery("from Notification", Notification.class).getSingleResult();
                assertEquals(NotificationType.LISTING_PUBLISHED, saved.getType());
                assertEquals(listingId, saved.getListingId());
                assertEquals(userId, saved.getUserId());
                assertEquals(Map.of("listingTitle", "My shirt"), saved.getPayload());
            });
            verify(publisher, times(2)).dispatchNotificationEvent(argThat(notification ->
                    notification.getType() == NotificationType.LISTING_PUBLISHED
                            && listingId.equals(notification.getListingId())));
        }
    }

    private static void update(org.hibernate.Session session, ImageOptimizer optimizer, AppSyncEventsPublisher publisher, UUID listingId) {
        var repository = mock(NotificationRepository.class);
        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            session.persist(notification);
            session.flush();
            return notification;
        });
        new ListingStatusUpdater(session, optimizer, new NotificationService(repository, publisher)).markAvailableWhenReady(listingId);
    }

    private static S3Client s3WithRenditions(UUID listingId, Set<UUID> readyAssets) {
        return (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("headObject")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String key = ((HeadObjectRequest) args[0]).key();
                    if (readyAssets.stream().noneMatch(asset -> key.startsWith("listings/" + listingId + "/" + asset) && !key.substring(("listings/" + listingId + "/" + asset).length()).contains("/"))) {
                        throw NoSuchKeyException.builder().statusCode(404).build();
                    }
                    return HeadObjectResponse.builder().metadata(Map.of("source-key", key)).build();
                });
    }
}
