package com.vestio.imageoptimizer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;

@Service
class ListingStatusUpdater {
    private final EntityManager entityManager;
    private final ImageOptimizer optimizer;
    private final NotificationService notificationService;

    ListingStatusUpdater(EntityManager entityManager, ImageOptimizer optimizer, NotificationService notificationService) {
        this.entityManager = entityManager;
        this.optimizer = optimizer;
        this.notificationService = notificationService;
    }

    @Transactional
    public boolean canProcess(UUID listingId, List<UUID> requestedAssets) {
        // Wait for the backend transaction that queued these assets to commit.
        OptimizerListing listing = entityManager.find(OptimizerListing.class, listingId, LockModeType.PESSIMISTIC_WRITE);
        return listing != null && listing.isAwaitingAssets() && !requestedAssets.isEmpty()
                && listing.assetKeys().containsAll(requestedAssets);
    }

    @Transactional
    public void markRejectedContent(UUID listingId, UUID rejectedAsset) {
        OptimizerListing listing = entityManager.find(OptimizerListing.class, listingId, LockModeType.PESSIMISTIC_WRITE);
        if (listing != null && listing.isAwaitingAssets() && listing.assetKeys().contains(rejectedAsset)) {
            listing.markRejectedContent();
        }
    }

    @Transactional
    public void markAvailableWhenReady(UUID listingId) {
        // The backend holds the same row lock while changing asset IDs and sending its event.
        OptimizerListing listing = entityManager.find(OptimizerListing.class, listingId, LockModeType.PESSIMISTIC_WRITE);
        if (listing == null || !listing.isAwaitingAssets() || listing.assetKeys().isEmpty()) {
            return;
        }
        if (listing.assetKeys().stream().allMatch(assetId -> optimizer.ensureListingAssetRenditions(listingId, assetId))) {
            listing.markAvailable();
            notificationService.notifyUserListingPublished(listing.userId(), listing.title(), listingId);
        }
    }
}
