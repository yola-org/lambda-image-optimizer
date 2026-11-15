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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "listings")
@DynamicUpdate
class OptimizerListing {
    @Id
    private UUID id;

    private String status;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ElementCollection
    @CollectionTable(name = "listing_asset_keys", joinColumns = @JoinColumn(name = "listing_id"))
    @OrderColumn(name = "position")
    @Column(name = "asset_key", nullable = false)
    private List<UUID> assetKeys;

    protected OptimizerListing() {
    }

    List<UUID> assetKeys() {
        return assetKeys;
    }

    boolean isAwaitingAssets() {
        return "AWAITING_ASSETS".equals(status);
    }

    void markAvailable() {
        status = "AVAILABLE";
        updatedAt = LocalDateTime.now();
    }
}
