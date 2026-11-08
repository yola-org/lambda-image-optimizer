package com.vestio.imageoptimizer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the optimization pipeline with the real image bundled in the test resources. */
class ImageOptimizerResourceTest {
    private static final String RESOURCE = "/img.png";
    private static final String SOURCE_KEY = "uploads/photo.png";
    private static final String ORIGINAL_KEY = "listings/listing-1/uploads/photo/original.webp";
    private static final String THUMBNAIL_KEY = "listings/listing-1/uploads/photo/thumbnail.webp";
    private static final String MOBILE_KEY = "listings/listing-1/uploads/photo/thumbnail-mobile.webp";
    private static final Path OUTPUT_DIRECTORY = Path.of("build", "test-optimized-output");

    @BeforeAll
    static void deletePreviousRunOutput() throws IOException {
        if (Files.exists(OUTPUT_DIRECTORY)) {
            try (Stream<Path> paths = Files.walk(OUTPUT_DIRECTORY)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.createDirectories(OUTPUT_DIRECTORY);
    }

    @Test
    void convertsPngResourceIntoThreeWebpRenditions() throws Exception {
        BufferedImage source = ImageIO.read(resource());
        assertEquals(494, source.getWidth());
        assertEquals(740, source.getHeight());

        RecordingS3 s3 = new RecordingS3(png());
        optimizer(s3).optimize(new OptimizationRequest("listing-1", List.of(SOURCE_KEY)));

        assertEquals(List.of(ORIGINAL_KEY, THUMBNAIL_KEY, MOBILE_KEY),
                s3.uploads().stream().map(Upload::key).toList());
        assertEquals(List.of(SOURCE_KEY), s3.deletedKeys());
        assertEquals(List.of("put:" + ORIGINAL_KEY, "put:" + THUMBNAIL_KEY, "put:" + MOBILE_KEY, "delete:" + SOURCE_KEY),
                s3.events());
    }

    @Test
    void renditionsKeepAspectRatioAndAreNeverEnlarged() throws Exception {
        RecordingS3 s3 = new RecordingS3(png());
        optimizer(s3).optimize(new OptimizationRequest("listing-1", List.of(SOURCE_KEY)));

        // The 740px longest edge is already below 2560/960, so those renditions are not resized.
        assertDimensions(s3, ORIGINAL_KEY, 494, 740);
        assertDimensions(s3, THUMBNAIL_KEY, 494, 740);
        // 480 / 740 of 494 rounded to the nearest pixel.
        assertDimensions(s3, MOBILE_KEY, 320, 480);

        double sourceRatio = 494.0 / 740.0;
        for (Upload upload : s3.uploads()) {
            BufferedImage rendition = decode(upload.bytes());
            assertEquals(sourceRatio, (double) rendition.getWidth() / rendition.getHeight(), 0.005,
                    "aspect ratio changed for " + upload.key());
            assertTrue(Math.max(rendition.getWidth(), rendition.getHeight()) <= longestEdge(upload.key()),
                    "rendition was enlarged: " + upload.key());
        }
    }

    @Test
    void uploadsAreValidWebpWithOptimizationMetadata() throws Exception {
        byte[] source = png();
        RecordingS3 s3 = new RecordingS3(source);
        optimizer(s3).optimize(new OptimizationRequest("listing-1", List.of(SOURCE_KEY)));

        for (Upload upload : s3.uploads()) {
            byte[] bytes = upload.bytes();
            assertTrue(bytes.length > 12, "empty WebP: " + upload.key());
            assertEquals('R', bytes[0]);
            assertEquals('I', bytes[1]);
            assertEquals('F', bytes[2]);
            assertEquals('F', bytes[3]);
            assertEquals("WEBP", new String(bytes, 8, 4, StandardCharsets.US_ASCII),
                    "missing WebP signature: " + upload.key());
            assertNotNull(ImageIO.read(new ByteArrayInputStream(bytes)), "WebP could not be decoded: " + upload.key());
            assertTrue(bytes.length < source.length, "rendition is not smaller than the PNG source: " + upload.key());

            PutObjectRequest request = upload.request();
            assertEquals("assets-bucket", request.bucket());
            assertEquals("image/webp", request.contentType());
            assertEquals("public, max-age=31536000, immutable", request.cacheControl());
            assertEquals(Map.of("listing-id", "listing-1", "source-key", SOURCE_KEY), request.metadata());
        }
    }

    @Test
    void keepsGeneratedRenditionsOnDiskUntilTheNextRun() throws Exception {
        byte[] bundledSource = png();
        RecordingS3 s3 = new RecordingS3(bundledSource);
        optimizer(s3).optimize(new OptimizationRequest("listing-1", List.of(SOURCE_KEY)));

        for (Upload upload : s3.uploads()) {
            Path file = OUTPUT_DIRECTORY.resolve(Path.of(upload.key()).getFileName());
            Files.write(file, upload.bytes());
            assertTrue(Files.exists(file), "missing output file " + file);
            assertEquals(upload.bytes().length, Files.size(file), "incomplete output file " + file);
        }

        // The source image is only read from the classpath; the simulated S3 delete must not touch it.
        Path source = Path.of(getClass().getResource(RESOURCE).toURI());
        assertArrayEquals(bundledSource, Files.readAllBytes(source));
    }

    @Test
    void keepsEverySourceWhenAnUploadFailsMidBatch() throws Exception {
        RecordingS3 s3 = new RecordingS3(png());
        s3.failUploadsContaining("listings/listing-1/uploads/second/");

        S3Exception failure = assertThrows(S3Exception.class, () -> optimizer(s3).optimize(
                new OptimizationRequest("listing-1", List.of("uploads/first.png", "uploads/second.png"))));

        assertEquals("simulated upload failure", failure.getMessage());
        assertTrue(s3.deletedKeys().isEmpty(), "a source was deleted even though a later upload failed");
    }

    @Test
    void reportsCorruptImagesWithoutDeletingTheSource() throws Exception {
        RecordingS3 s3 = new RecordingS3("definitely not an image".getBytes(StandardCharsets.UTF_8));

        IOException failure = assertThrows(IOException.class, () -> optimizer(s3).optimize(
                new OptimizationRequest("listing-1", List.of(SOURCE_KEY))));

        assertTrue(failure.getMessage().contains("Unsupported or corrupt image"));
        assertTrue(s3.uploads().isEmpty());
        assertTrue(s3.deletedKeys().isEmpty());
    }

    private static ImageOptimizer optimizer(RecordingS3 s3) {
        return new ImageOptimizer(s3.client(), new ImageSettings("assets-bucket", "jdbc:postgresql://localhost/test"));
    }

    private static void assertDimensions(RecordingS3 s3, String key, int width, int height) throws IOException {
        BufferedImage image = decode(s3.upload(key).bytes());
        assertEquals(width, image.getWidth(), "width of " + key);
        assertEquals(height, image.getHeight(), "height of " + key);
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image, "image could not be decoded");
        return image;
    }

    private static int longestEdge(String key) {
        if (key.endsWith("thumbnail-mobile.webp")) return 480;
        if (key.endsWith("thumbnail.webp")) return 960;
        return 2560;
    }

    private static InputStream resource() {
        InputStream stream = ImageOptimizerResourceTest.class.getResourceAsStream(RESOURCE);
        assertNotNull(stream, "test resource " + RESOURCE + " is missing");
        return stream;
    }

    private static byte[] png() throws IOException {
        try (InputStream stream = resource()) {
            return stream.readAllBytes();
        }
    }

    private record Upload(PutObjectRequest request, byte[] bytes) {
        String key() { return request.key(); }
    }

    /** Minimal in-memory S3 double that records uploads, downloads and deletions in order. */
    private static final class RecordingS3 {
        private final byte[] source;
        private final List<Upload> uploads = new ArrayList<>();
        private final List<String> deletedKeys = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private String failUploadsContaining;

        RecordingS3(byte[] source) {
            this.source = source;
        }

        void failUploadsContaining(String keyFragment) {
            this.failUploadsContaining = keyFragment;
        }

        S3Client client() {
            return (S3Client) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{S3Client.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getObjectAsBytes" -> ResponseBytes.fromByteArray(
                                GetObjectResponse.builder().build(), source);
                        case "putObject" -> {
                            PutObjectRequest request = (PutObjectRequest) args[0];
                            if (failUploadsContaining != null && request.key().contains(failUploadsContaining)) {
                                throw S3Exception.builder().message("simulated upload failure").build();
                            }
                            uploads.add(new Upload(request, bytesOf((RequestBody) args[1])));
                            events.add("put:" + request.key());
                            yield null;
                        }
                        case "deleteObject" -> {
                            DeleteObjectRequest request = (DeleteObjectRequest) args[0];
                            deletedKeys.add(request.key());
                            events.add("delete:" + request.key());
                            yield null;
                        }
                        case "serviceName" -> "S3";
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        List<Upload> uploads() {
            return uploads;
        }

        Upload upload(String key) {
            return uploads.stream().filter(upload -> upload.key().equals(key)).findFirst()
                    .orElseThrow(() -> new AssertionError("no upload for " + key + ", got "
                            + uploads.stream().map(Upload::key).toList()));
        }

        List<String> deletedKeys() {
            return deletedKeys;
        }

        List<String> events() {
            return events;
        }

        private static byte[] bytesOf(RequestBody body) throws IOException {
            return body.contentStreamProvider().newStream().readAllBytes();
        }
    }
}
