package com.vestio.imageoptimizer;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import com.vestio.common.entity.enums.ListingStatus;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "listings")
@DynamicUpdate
class OptimizerListing {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private ListingStatus status;
    private String title;
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @ElementCollection
    @CollectionTable(name = "listing_asset_keys", joinColumns = @JoinColumn(name = "listing_id"))
    @OrderColumn(name = "position")
    @Column(name = "asset_key", nullable = false)
    private List<UUID> assetKeys;

    protected OptimizerListing() {
    }

    UUID userId() { return userId; }
    String title() { return title; }

    List<UUID> assetKeys() {
        return assetKeys;
    }

    boolean isAwaitingAssets() {
        return status == ListingStatus.AWAITING_ASSETS;
    }

    void markRejectedContent() {
        status = ListingStatus.REJECTED_CONTENT;
        updatedAt = Instant.now();
    }

    void markAvailable() {
        status = ListingStatus.AVAILABLE;
        updatedAt = Instant.now();
    }
}
