package com.vestio.imageoptimizer;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Downloads source assets, creates WebP renditions, then removes each source only after successful writes. */
final class ImageOptimizer {
    private static final Logger LOGGER = Logger.getLogger(ImageOptimizer.class.getName());
    private static final long MAX_PIXELS = 100_000_000L;
    private static final List<Rendition> RENDITIONS = List.of(
            new Rendition("original", 2560, 0.90f),
            new Rendition("thumbnail", 960, 0.84f),
            new Rendition("thumbnail-mobile", 480, 0.82f));
    private final S3Client s3;
    private final ImageSettings settings;

    ImageOptimizer(S3Client s3, ImageSettings settings) {
        this.s3 = s3;
        this.settings = settings;
    }

    void optimize(OptimizationRequest request) throws IOException {
        String sourceBucket = settings.bucketName();
        LOGGER.fine("Optimizing " + request.assetKeys().size() + " asset(s) for listing "
                + request.listingId() + " from bucket " + sourceBucket);
        List<String> sourcesToDelete = new java.util.ArrayList<>();
        for (String sourceKey : request.assetKeys()) {
            validateSourceKey(sourceKey);
            String destinationBase = destinationBase(request.listingId(), sourceKey);
            LOGGER.fine("Processing " + sourceKey + " -> " + destinationBase);
            ResponseBytes<GetObjectResponse> downloaded;
            try {
                downloaded = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(sourceBucket).key(sourceKey).build());
            } catch (NoSuchKeyException missing) {
                // A prior Lambda attempt may have already uploaded and deleted this source.
                LOGGER.info("Source " + sourceKey + " is missing; checking for output from a prior attempt");
                s3.headObject(HeadObjectRequest.builder().bucket(settings.bucketName()).key(destinationBase + "/original.webp").build());
                continue;
            }
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(downloaded.asByteArray()));
            if (source == null) throw new IOException("Unsupported or corrupt image: " + sourceKey);
            if ((long) source.getWidth() * source.getHeight() > MAX_PIXELS) throw new IOException("Image exceeds 100 megapixels: " + sourceKey);

            for (Rendition rendition : RENDITIONS) {
                byte[] webp = webp(resize(source, rendition.longestEdge()), rendition.quality());
                s3.putObject(PutObjectRequest.builder().bucket(settings.bucketName())
                                .key(destinationBase + "/" + rendition.name() + ".webp")
                                .contentType("image/webp").cacheControl("public, max-age=31536000, immutable")
                                .metadata(Map.of("listing-id", request.listingId(), "source-key", sourceKey)).build(),
                        RequestBody.fromBytes(webp));
            }
            LOGGER.fine("Uploaded " + RENDITIONS.size() + " renditions for " + sourceKey);
            sourcesToDelete.add(sourceKey);
        }
        // Delete no source until all assets in this SQS message have every rendition uploaded.
        for (String sourceKey : sourcesToDelete) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(sourceBucket).key(sourceKey).build());
            LOGGER.fine("Deleted source " + sourceKey);
        }
        LOGGER.fine("Finished optimizing listing " + request.listingId());
    }

    private String destinationBase(String listingId, String sourceKey) {
        String withoutExtension = sourceKey.replaceFirst("\\.[^.]+$", "");
        return "listings/" + listingId + "/" + withoutExtension;
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
    private record Rendition(String name, int longestEdge, float quality) { }
}
