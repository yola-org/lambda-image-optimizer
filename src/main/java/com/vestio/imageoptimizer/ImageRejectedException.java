package com.vestio.imageoptimizer;

/** A confirmed policy violation, distinct from retryable image or AWS service failures. */
final class ImageRejectedException extends RuntimeException {
    private final String assetKey;

    ImageRejectedException(String message) {
        this(message, null);
    }

    ImageRejectedException(String message, String assetKey) {
        super(message);
        this.assetKey = assetKey;
    }

    String assetKey() {
        return assetKey;
    }
}
