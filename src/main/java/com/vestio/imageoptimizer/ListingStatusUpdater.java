package com.vestio.imageoptimizer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class ListingStatusUpdater {
    private final EntityManager entityManager;
    private final ImageOptimizer optimizer;

    ListingStatusUpdater(EntityManager entityManager, ImageOptimizer optimizer) {
        this.entityManager = entityManager;
        this.optimizer = optimizer;
    }

    @Transactional
    public void updateOptimizedAssets(UUID listingId) {
        // The backend holds the same row lock while changing asset IDs and sending its event.
        OptimizerListing listing = entityManager.find(OptimizerListing.class, listingId, LockModeType.PESSIMISTIC_WRITE);
        if (listing == null || listing.assets().isEmpty()) {
            return;
        }

        boolean allOptimized = true;
        for (OptimizerListing.Asset asset : listing.assets()) {
            if (asset.isOptimized()) {
                continue;
            }
            if (optimizer.ensureListingAssetRenditions(listingId, asset.id())) {
                asset.setOptimizedName("original.webp");
            } else {
                allOptimized = false;
            }
        }
        if (allOptimized) {
            listing.markAvailable();
        }
    }
}
