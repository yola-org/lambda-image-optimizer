package com.vestio.imageoptimizer;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@Service
final class OptimizationService {
    private static final Logger LOGGER = Logger.getLogger(OptimizationService.class.getName());
    private static final Pattern SOURCE_PATH = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:/original)?\\.[a-z0-9]+");

    private final ImageOptimizer optimizer;
    private final ListingStatusUpdater listingStatusUpdater;

    OptimizationService(ImageOptimizer optimizer, ListingStatusUpdater listingStatusUpdater) {
        this.optimizer = optimizer;
        this.listingStatusUpdater = listingStatusUpdater;
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
        List<UUID> assetIds = request.assetKeys().stream()
                .map(key -> assetId(key, prefix)).toList();
        if (!listingStatusUpdater.canProcess(listingId, assetIds)) return;
        try {
            optimizer.optimize(request);
            listingStatusUpdater.markAvailableWhenReady(listingId);
        } catch (ImageRejectedException exception) {
            listingStatusUpdater.markRejectedContent(listingId, assetId(exception.assetKey(), prefix));
            LOGGER.warning("Content moderation refused an image for listing " + listingId);
        }
    }

    private static UUID assetId(String key, String prefix) {
        return UUID.fromString(key.substring(prefix.length()).split("[/.]", 2)[0]);
    }
}
