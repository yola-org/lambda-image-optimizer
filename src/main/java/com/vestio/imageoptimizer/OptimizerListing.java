package com.vestio.imageoptimizer;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
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
    private List<Asset> assets;

    protected OptimizerListing() {
    }

    List<Asset> assets() {
        return assets;
    }

    void markAvailable() {
        if ("AWAITING_ASSETS".equals(status)) {
            status = "AVAILABLE";
            updatedAt = LocalDateTime.now();
        }
    }

    @Embeddable
    static class Asset {
        @Column(name = "asset_key", nullable = false)
        private UUID id;

        @Column(name = "optimized_name")
        private String optimizedName;

        protected Asset() {
        }

        UUID id() {
            return id;
        }

        boolean isOptimized() {
            return optimizedName != null;
        }

        void setOptimizedName(String name) {
            optimizedName = Objects.requireNonNull(name);
        }
    }
}
