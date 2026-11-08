package com.vestio.imageoptimizer;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

/** Payload for one listing. `keys` is accepted as an alias for `assetKeys`. */
public record OptimizationRequest(String listingId, @JsonAlias("keys") List<String> assetKeys) {
    public OptimizationRequest {
        if (listingId == null || listingId.isBlank()) throw new IllegalArgumentException("listingId is required");
        if (assetKeys == null || assetKeys.isEmpty()) throw new IllegalArgumentException("assetKeys is required");
    }
}
