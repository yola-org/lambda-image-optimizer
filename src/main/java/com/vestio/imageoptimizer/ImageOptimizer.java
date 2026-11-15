package com.vestio.imageoptimizer;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

/** Downloads source assets, creates WebP renditions, then removes each source only after successful writes. */
final class ImageOptimizer {
    private static final Logger LOGGER = Logger.getLogger(ImageOptimizer.class.getName());
    private static final long MAX_PIXELS = 100_000_000L;
    private static final List<Rendition> RENDITIONS = List.of(
            new Rendition("thumbnail", "_thumbnail", 960, 0.84f),
            new Rendition("thumbnail-mobile", "_thumbnail_mobile", 480, 0.82f),
            new Rendition("original", "", 2560, 0.90f));
    private final S3Client s3;
    private final ImageSettings settings;

    ImageOptimizer(S3Client s3, ImageSettings settings) {
        this.s3 = s3;
        this.settings = settings;
    }

    void optimize(OptimizationRequest request) throws IOException {
        String bucket = settings.bucketName();
        List<String> sourcesToDelete = new ArrayList<>();
        for (String sourceKey : request.assetKeys()) {
            validateSourceKey(sourceKey);
            String stem = sourceKey.replaceFirst("\\.[^./]+$", "");
            if (stem.startsWith("listings/" + request.listingId() + "/") && stem.endsWith("/original")) {
                stem = stem.substring(0, stem.length() - "/original".length());
            }
            String optimizedKey = stem + ".webp";
            ResponseBytes<GetObjectResponse> downloaded;
            try {
                downloaded = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(sourceKey).build());
            } catch (S3Exception missing) {
                if (missing.statusCode() != 404 || !ensureRenditions(request.listingId(), stem)) {
                    throw missing;
                }
                continue;
            }
            if (isOptimized(downloaded.response().metadata()) && ensureRenditions(request.listingId(), stem)) {
                continue;
            }
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(downloaded.asByteArray()));
            if (source == null) throw new IOException("Unsupported or corrupt image: " + sourceKey);
            if ((long) source.getWidth() * source.getHeight() > MAX_PIXELS) throw new IOException("Image exceeds 100 megapixels: " + sourceKey);

            // Write the full-size image last. For WebP inputs it replaces the source.
            for (Rendition rendition : RENDITIONS) {
                s3.putObject(PutObjectRequest.builder().bucket(bucket)
                                .key(rendition.key(stem))
                                .contentType("image/webp").cacheControl("public, max-age=31536000, immutable")
                                .metadata(Map.of("listing-id", request.listingId(), "source-key", sourceKey)).build(),
                        RequestBody.fromBytes(webp(resize(source, rendition.longestEdge()), rendition.quality())));
            }
            if (!sourceKey.equals(optimizedKey)) {
                sourcesToDelete.add(sourceKey);
            }
        }
        // Delete no source until all assets in this SQS message have every rendition uploaded.
        for (String sourceKey : sourcesToDelete) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(sourceKey).build());
            LOGGER.fine("Deleted source " + sourceKey);
        }
    }

    boolean ensureListingAssetRenditions(UUID listingId, UUID assetId) {
        return ensureRenditions(listingId.toString(), "listings/" + listingId + "/" + assetId);
    }

    private boolean ensureRenditions(String listingId, String stem) {
        HeadObjectResponse original = head(stem + ".webp");
        if (original != null && isOptimized(original.metadata())
                && RENDITIONS.stream().filter(rendition -> !rendition.suffix().isEmpty())
                        .allMatch(rendition -> head(rendition.key(stem)) != null)) {
            return true;
        }

        String legacyStem = stem + "/original";
        HeadObjectResponse legacyOriginal = head(legacyStem + ".webp");
        if (legacyOriginal != null && isOptimized(legacyOriginal.metadata())
                && copyRenditions(stem, rendition -> rendition.nestedKey(legacyStem))) {
            return true;
        }

        String legacyDirectory = "listings/" + listingId + "/" + legacyStem;
        return copyRenditions(stem, rendition -> legacyDirectory + "/" + rendition.name() + ".webp");
    }

    private boolean copyRenditions(String stem, Function<Rendition, String> sourceKey) {
        if (!RENDITIONS.stream().allMatch(rendition -> head(sourceKey.apply(rendition)) != null)) {
            return false;
        }
        String bucket = settings.bucketName();
        for (Rendition rendition : RENDITIONS) {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(sourceKey.apply(rendition))
                    .destinationBucket(bucket).destinationKey(rendition.key(stem))
                    .build());
        }
        return true;
    }

    private HeadObjectResponse head(String key) {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(settings.bucketName()).key(key).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return null;
            }
            throw exception;
        }
    }

    private boolean isOptimized(Map<String, String> metadata) {
        return metadata.containsKey("source-key");
    }

    private static void validateSourceKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..")) throw new IllegalArgumentException("Invalid S3 source key");
    }

    private static BufferedImage resize(BufferedImage source, int maxEdge) {
        double scale = Math.min(1.0d, (double) maxEdge / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage result = new BufferedImage(width, height, source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return result;
    }

    private static byte[] webp(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
        if (!writers.hasNext()) throw new IOException("No WebP ImageIO writer is available");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                String[] compressionTypes = parameters.getCompressionTypes();
                if (compressionTypes != null && compressionTypes.length > 0) parameters.setCompressionType("Lossy");
                parameters.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        // The writer buffers into the ImageOutputStream, so it must be closed (flushed) before reading the bytes.
        return bytes.toByteArray();
    }
    private record Rendition(String name, String suffix, int longestEdge, float quality) {
        String key(String stem) {
            return stem + suffix + ".webp";
        }

        String nestedKey(String stem) {
            return stem + suffix.replace('_', '-') + ".webp";
        }
    }
}
