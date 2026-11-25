package com.vestio.imageoptimizer;

import java.util.List;

import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsResponse;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.Image;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;

/** Applies the listing policy before any optimized image can be published. */
final class ImageModeration {
    static final String METADATA_KEY = "moderation-policy";
    // Include current categories and earlier model names; do not reject swimwear or kissing.
    private static final Set<String> BLOCKED = Set.of(
            "Explicit", "Explicit Nudity", "Non-Explicit Nudity", "Obstructed Intimate Parts",
            "Violence", "Graphic Violence", "Visually Disturbing", "Blood & Gore", "Blood",
            "Drugs & Tobacco", "Drugs", "Drug Products", "Drug Use", "Drug Paraphernalia");
    private final RekognitionClient client;
    private final float minConfidence;

    ImageModeration(RekognitionClient client, float minConfidence) {
        if (!Float.isFinite(minConfidence) || minConfidence < 0 || minConfidence > 100) {
            throw new IllegalArgumentException("IMAGE_MODERATION_MIN_CONFIDENCE must be between 0 and 100");
        }
        this.client = client;
        this.minConfidence = minConfidence;
    }

    String policyId() {
        return "listing-v1-" + minConfidence;
    }

    void check(BufferedImage source) throws IOException {
        DetectModerationLabelsResponse response = client.detectModerationLabels(DetectModerationLabelsRequest.builder()
                .minConfidence(minConfidence)
                .image(Image.builder().bytes(SdkBytes.fromByteArray(jpeg(source))).build()).build());
        List<String> blocked = response.moderationLabels().stream()
                .filter(label -> label.confidence() != null && label.confidence() >= minConfidence)
                .filter(label -> (label.name() != null && BLOCKED.contains(label.name()))
                        || (label.parentName() != null && BLOCKED.contains(label.parentName())))
                .map(label -> label.name() + " (" + label.confidence() + "%)").toList();
        if (!blocked.isEmpty()) {
            throw new IOException("Image rejected by content moderation: " + String.join(", ", blocked));
        }
    }

    private static byte[] jpeg(BufferedImage source) throws IOException {
        // Rekognition accepts JPEG/PNG, with an 80px minimum and a 5MB byte limit.
        // Convert WebP and transparent PNG too, without writing a public staging object.
        double scale = Math.min(1.0, 2560.0 / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(80, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(80, (int) Math.round(source.getHeight() * scale));
        while (true) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpeg", bytes)) throw new IOException("No JPEG writer available for moderation");
            if (bytes.size() <= 5_000_000) return bytes.toByteArray();
            if (width == 80 && height == 80) throw new IOException("Moderation image exceeds Rekognition byte limit");
            width = Math.max(80, width / 2);
            height = Math.max(80, height / 2);
        }
    }
}
