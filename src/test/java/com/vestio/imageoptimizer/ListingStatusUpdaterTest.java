package com.vestio.imageoptimizer;

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

class ListingStatusUpdaterTest {
    @Test
    void movesToAvailableAfterAllCurrentAssetsAreReadyWithTheExistingSchema() {
        UUID listingId = UUID.randomUUID();
        UUID firstAsset = UUID.randomUUID();
        UUID secondAsset = UUID.randomUUID();
        Set<UUID> readyAssets = new HashSet<>();
        ImageOptimizer optimizer = new ImageOptimizer(s3WithRenditions(listingId, readyAssets),
                new ImageSettings("assets", "secret-arn"));

        try (var factory = new Configuration()
                .addAnnotatedClass(OptimizerListing.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:listing-optimizer")
                .buildSessionFactory()) {
            factory.inTransaction(session -> {
                session.createNativeMutationQuery("""
                        CREATE TABLE listings (id UUID PRIMARY KEY, status VARCHAR(255), updated_at TIMESTAMP)
                        """).executeUpdate();
                session.createNativeMutationQuery("""
                        CREATE TABLE listing_asset_keys (
                            listing_id UUID NOT NULL, position INT NOT NULL, asset_key UUID NOT NULL,
                            PRIMARY KEY (listing_id, position)
                        )
                        """).executeUpdate();
                session.createNativeMutationQuery("INSERT INTO listings (id, status) VALUES (:id, 'AWAITING_ASSETS')")
                        .setParameter("id", listingId).executeUpdate();
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
            factory.inTransaction(session -> new ListingStatusUpdater(session, optimizer).markAvailableWhenReady(listingId));
            factory.inTransaction(session -> {
                assertEquals("AWAITING_ASSETS", session.createNativeQuery("SELECT status FROM listings", String.class)
                        .getSingleResult());
            });

            readyAssets.add(firstAsset);
            factory.inTransaction(session -> new ListingStatusUpdater(session, optimizer).markAvailableWhenReady(listingId));
            factory.inTransaction(session -> {
                assertEquals("AVAILABLE", session.createNativeQuery("SELECT status FROM listings", String.class)
                        .getSingleResult());
            });
        }
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
