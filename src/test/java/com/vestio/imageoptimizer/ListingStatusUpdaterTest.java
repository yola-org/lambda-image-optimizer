package com.vestio.imageoptimizer;

import com.vestio.common.publisher.AppSyncEventsPublisher;

import com.vestio.common.publisher.RealtimeChannelsNamespace;

import com.vestio.common.entity.NotificationOutbox;

import com.vestio.common.entity.Notification;

import com.vestio.common.event.BaseEvent;

import com.vestio.common.event.NotificationEvent;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;


class ListingStatusUpdaterTest {
    @Test
    void completesOnlyWhenAllAssetsAreReadyAndRetriesNotificationDeliveryWithoutDuplicates() throws Exception {
        String jdbcUrl = System.getenv().getOrDefault("OPTIMIZER_TEST_JDBC_URL", "jdbc:h2:mem:listing-optimizer");
        UUID listingId = UUID.randomUUID();
        UUID firstAsset = UUID.randomUUID();
        UUID secondAsset = UUID.randomUUID();
        Set<UUID> readyAssets = new HashSet<>();
        ImageOptimizer optimizer = new ImageOptimizer(s3WithRenditions(listingId, readyAssets),
                new ImageSettings("assets", "secret-arn"));

        try (var factory = new Configuration()
                .addAnnotatedClass(OptimizerListing.class)
                .addAnnotatedClass(Notification.class)
                .addAnnotatedClass(NotificationOutbox.class)
                .setProperty("hibernate.connection.url", jdbcUrl)
                .buildSessionFactory()) {
            factory.inTransaction(session -> {
                session.createNativeMutationQuery("""
                        CREATE TABLE listings (id UUID PRIMARY KEY, status VARCHAR(255), title VARCHAR(255), user_id UUID, updated_at TIMESTAMP)
                        """).executeUpdate();
                session.createNativeMutationQuery("""
                        CREATE TABLE listing_asset_keys (
                            listing_id UUID NOT NULL, position INT NOT NULL, asset_key UUID NOT NULL,
                            PRIMARY KEY (listing_id, position)
                        )
                        """).executeUpdate();
                session.createNativeMutationQuery("INSERT INTO listings (id, status, title, user_id) VALUES (:id, 'AWAITING_ASSETS', 'My shirt', :userId)")
                        .setParameter("id", listingId).setParameter("userId", UUID.randomUUID()).executeUpdate();
                session.createNativeMutationQuery("CREATE TABLE notifications (id UUID PRIMARY KEY, user_id UUID, type VARCHAR(128), params " + (jdbcUrl.startsWith("jdbc:postgresql:") ? "JSONB" : "JSON") + ", created_at TIMESTAMP, read_at TIMESTAMP)").executeUpdate();
                session.createNativeMutationQuery("CREATE TABLE notification_outbox (notification_id UUID PRIMARY KEY REFERENCES notifications(id), listing_id UUID REFERENCES listings(id), user_id UUID, payload TEXT, created_at TIMESTAMP, published_at TIMESTAMP)").executeUpdate();
                List<UUID> assets = List.of(firstAsset, secondAsset);
                for (int position = 0; position < assets.size(); position++) {
                    session.createNativeMutationQuery("""
                            INSERT INTO listing_asset_keys (listing_id, position, asset_key)
                            VALUES (:listingId, :position, :assetId)
                            """)
                            .setParameter("listingId", listingId)
                            .setParameter("position", position)
                            .setParameter("assetId", assets.get(position))
                            .executeUpdate();
                }
            });

            readyAssets.add(secondAsset);
            factory.inTransaction(session -> new ListingStatusUpdater(session, optimizer, notifications(session, JsonMapper.builder().build())).markAvailableWhenReady(listingId));
            factory.inTransaction(session -> {
                assertEquals("AWAITING_ASSETS", session.createNativeQuery("SELECT status FROM listings", String.class)
                        .getSingleResult());
            });

            factory.inTransaction(session -> {
                assertEquals(0L, session.createNativeQuery("SELECT count(*) FROM notifications", Long.class).getSingleResult());
            });
            readyAssets.add(firstAsset);
            ObjectMapper failingMapper = mock(ObjectMapper.class);
            when(failingMapper.writeValueAsString(any())).thenThrow(new IllegalStateException("serialization failed"));
            assertThrows(IllegalStateException.class, () -> factory.inTransaction(session ->
                    new ListingStatusUpdater(session, optimizer, notifications(session, failingMapper)).markAvailableWhenReady(listingId)));
            factory.inTransaction(session -> {
                assertEquals("AWAITING_ASSETS", session.createNativeQuery("SELECT status FROM listings", String.class).getSingleResult());
                assertEquals(0L, session.createNativeQuery("SELECT count(*) FROM notification_outbox", Long.class).getSingleResult());
            });
            factory.inTransaction(session -> new ListingStatusUpdater(session, optimizer, notifications(session, JsonMapper.builder().build())).markAvailableWhenReady(listingId));
            factory.inTransaction(session -> {
                assertEquals("AVAILABLE", session.createNativeQuery("SELECT status FROM listings", String.class)
                        .getSingleResult());
            });
            // Duplicate SQS messages cannot create a second ready notification.
            factory.inTransaction(session -> new ListingStatusUpdater(session, optimizer, notifications(session, JsonMapper.builder().build())).markAvailableWhenReady(listingId));
            factory.inTransaction(session -> {
                assertEquals(1L, session.createNativeQuery("SELECT count(*) FROM notifications", Long.class).getSingleResult());
                assertEquals("LISTING_READY", session.createNativeQuery("SELECT type FROM notifications", String.class).getSingleResult());
                var saved = session.createQuery("from Notification", Notification.class).getSingleResult();
                assertEquals("My shirt", JsonMapper.builder().build().valueToTree(new NotificationEvent(saved)).path("notification").path("params").path("listingTitle").asString());
                assertEquals(1L, session.createNativeQuery("SELECT count(*) FROM notification_outbox", Long.class).getSingleResult());
            });
            AppSyncEventsPublisher publisher = mock(AppSyncEventsPublisher.class);
            when(publisher.dispatchEvent(any(), any(), any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("AppSync unavailable")))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
            assertThrows(java.util.concurrent.CompletionException.class, () -> factory.inTransaction(session ->
                    new NotificationDeliveryService(session, publisher, JsonMapper.builder().build()).publishForListing(listingId)));
            factory.inTransaction(session -> {
                assertEquals(1L, session.createNativeQuery("SELECT count(*) FROM notification_outbox WHERE published_at IS NULL", Long.class).getSingleResult());
                assertEquals("AVAILABLE", session.createNativeQuery("SELECT status FROM listings", String.class).getSingleResult());
            });
            factory.inTransaction(session -> new NotificationDeliveryService(session, publisher, JsonMapper.builder().build()).publishForListing(listingId));
            factory.inTransaction(session -> new NotificationDeliveryService(session, publisher, JsonMapper.builder().build()).publishForListing(listingId));
            var payloads = org.mockito.ArgumentCaptor.forClass(BaseEvent.class);
            verify(publisher, times(2)).dispatchEvent(payloads.capture(), eq(RealtimeChannelsNamespace.NOTIFICATIONS_NAMESPACE), any());
            var mapper = JsonMapper.builder().build();
            assertEquals(mapper.writeValueAsString(payloads.getAllValues().getFirst()), mapper.writeValueAsString(payloads.getAllValues().getLast()));
            var event = mapper.valueToTree(payloads.getValue());
            assertEquals("notification-event", event.path("eventType").asString());
            assertEquals("LISTING_READY", event.path("notification").path("type").asString());
            assertEquals(listingId.toString(), event.path("notification").path("params").path("listingId").asString());
            assertEquals(event.path("id").asString(), event.path("notification").path("id").asString());
            factory.inTransaction(session -> {
                assertEquals(0L, session.createNativeQuery("SELECT count(*) FROM notification_outbox WHERE published_at IS NULL", Long.class).getSingleResult());
            });
        }
    }

    private static NotificationService notifications(org.hibernate.Session session, ObjectMapper mapper) {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            session.persist(notification);
            session.flush();
            return notification;
        });
        NotificationDispatcher dispatcher = new NotificationDispatcher(session, mapper);
        return new NotificationService(repository, event -> dispatcher.onNotificationCreated((NotificationCreated) event));
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
