package com.vestio.imageoptimizer;

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
    void persistsRejectionAndNeverPublishesItWhileIgnoringObsoleteAssetMessages() {
        UUID listingId = UUID.randomUUID();
        UUID oldAsset = UUID.randomUUID();
        UUID currentAsset = UUID.randomUUID();
        ImageOptimizer optimizer = mock(ImageOptimizer.class);
        NotificationService notifications = mock(NotificationService.class);
        try (SessionFactory factory = new Configuration().addAnnotatedClass(OptimizerListing.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:rejected-" + listingId)
                .setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory()) {
            factory.inTransaction(session -> {
                session.createNativeMutationQuery("INSERT INTO listings(id,status,title,user_id) VALUES (:id,'AWAITING_ASSETS','Shirt',:user)")
                        .setParameter("id", listingId).setParameter("user", UUID.randomUUID()).executeUpdate();
                session.createNativeMutationQuery("INSERT INTO listing_asset_keys(listing_id,position,asset_key) VALUES (:id,0,:asset)")
                        .setParameter("id", listingId).setParameter("asset", currentAsset).executeUpdate();
            });
            factory.inTransaction(session -> {
                ListingStatusUpdater updater = new ListingStatusUpdater(session, optimizer, notifications);
                assertFalse(updater.canProcess(listingId, List.of(oldAsset)));
                assertFalse(updater.canProcess(listingId, List.of()));
                assertTrue(updater.canProcess(listingId, List.of(currentAsset)));
                updater.markRejectedContent(listingId, oldAsset);
            });
            factory.inTransaction(session -> {
                assertEquals("AWAITING_ASSETS", status(session));
                new ListingStatusUpdater(session, optimizer, notifications).markRejectedContent(listingId, currentAsset);
            });
            factory.inTransaction(session -> {
                assertEquals("REJECTED_CONTENT", status(session));
                ListingStatusUpdater updater = new ListingStatusUpdater(session, optimizer, notifications);
                assertFalse(updater.canProcess(listingId, List.of(currentAsset)));
                updater.markAvailableWhenReady(listingId);
                updater.markRejectedContent(listingId, currentAsset);
            });
            factory.inTransaction(session -> {
                assertEquals("REJECTED_CONTENT", status(session));
                assertNotNull(session.createQuery("select l.updatedAt from OptimizerListing l", Instant.class).getSingleResult());
                session.createNativeMutationQuery("UPDATE listings SET status='AVAILABLE'").executeUpdate();
            });
            factory.inTransaction(session -> {
                new ListingStatusUpdater(session, optimizer, notifications).markRejectedContent(listingId, currentAsset);
            });
            factory.inTransaction(session -> assertEquals("AVAILABLE", status(session)));
        }
        verifyNoInteractions(optimizer, notifications);
    }

    private static String status(Session session) {
        return session.createNativeQuery("SELECT status FROM listings", String.class).getSingleResult();
    }
}
