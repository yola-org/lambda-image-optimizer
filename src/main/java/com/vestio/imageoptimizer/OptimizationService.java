package com.vestio.imageoptimizer;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
final class OptimizationService {
    private static final Pattern SOURCE_PATH = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:/original)?\\.[a-z0-9]+");

    private final ImageOptimizer optimizer;
    private final ListingStatusUpdater listingStatusUpdater;
    private final NotificationDeliveryService notificationDelivery;

    OptimizationService(ImageOptimizer optimizer, ListingStatusUpdater listingStatusUpdater, NotificationDeliveryService notificationDelivery) {
        this.optimizer = optimizer;
        this.listingStatusUpdater = listingStatusUpdater;
        this.notificationDelivery = notificationDelivery;
    }

    void process(OptimizationRequest request) throws IOException {
        UUID listingId = UUID.fromString(request.listingId());
        String prefix = "listings/" + listingId + "/";
        for (String key : request.assetKeys()) {
            if (key == null || !key.startsWith(prefix)
                    || !SOURCE_PATH.matcher(key.substring(prefix.length())).matches()) {
                throw new IllegalArgumentException("Invalid source key for listing " + listingId);
            }
        }
        optimizer.optimize(request);
        listingStatusUpdater.markAvailableWhenReady(listingId);
        // Also runs on SQS retries when the listing is already available.
        notificationDelivery.publishForListing(listingId);
    }
}
