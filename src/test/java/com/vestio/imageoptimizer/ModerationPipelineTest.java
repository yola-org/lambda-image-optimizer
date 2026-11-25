package com.vestio.imageoptimizer;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.ModerationLabel;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModerationPipelineTest {
    @Test
    void flaggedSourceCannotWriteRenditionsDeleteSourceOrPublishListing() throws Exception {
        S3Client s3 = s3WithImage();
        RekognitionClient rekognition = rejectingClient();
        ListingStatusUpdater updater = mock(ListingStatusUpdater.class);
        UUID listing = UUID.randomUUID();
        OptimizationService service = new OptimizationService(optimizer(s3, rekognition), updater);

        assertThrows(IOException.class, () -> service.process(new OptimizationRequest(listing.toString(),
                List.of("listings/" + listing + "/" + UUID.randomUUID() + ".png"))));

        verify(s3).getObjectAsBytes(any(GetObjectRequest.class));
        verifyNoMoreInteractions(s3);
        verifyNoInteractions(updater);
    }

    @Test
    void rekognitionFailureCannotWriteOrPublish() throws Exception {
        S3Client s3 = s3WithImage();
        RekognitionClient rekognition = mock(RekognitionClient.class);
        when(rekognition.detectModerationLabels(any(DetectModerationLabelsRequest.class)))
                .thenThrow(new IllegalStateException("unavailable"));
        ListingStatusUpdater updater = mock(ListingStatusUpdater.class);
        UUID listing = UUID.randomUUID();
        OptimizationService service = new OptimizationService(optimizer(s3, rekognition), updater);
        assertThrows(IllegalStateException.class, () -> service.process(new OptimizationRequest(listing.toString(),
                List.of("listings/" + listing + "/" + UUID.randomUUID() + ".webp"))));
        verify(s3).getObjectAsBytes(any(GetObjectRequest.class));
        verifyNoMoreInteractions(s3);
        verifyNoInteractions(updater);
    }

    @Test
    void retriesCannotPublishUncheckedExistingRenditions() throws Exception {
        S3Client s3 = s3WithImage();
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().metadata(Map.of("source-key", "old-source.png")).build());
        assertThrows(UncheckedIOException.class, () -> optimizer(s3, rejectingClient())
                .ensureListingAssetRenditions(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void retriesReuseApprovalForCurrentPolicyWithoutAnotherApiCall() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .metadata(Map.of("source-key", "old-source.png", ImageModeration.METADATA_KEY, "listing-v1-80.0")).build());
        RekognitionClient rekognition = mock(RekognitionClient.class);
        assertTrue(optimizer(s3, rekognition).ensureListingAssetRenditions(UUID.randomUUID(), UUID.randomUUID()));
        verifyNoInteractions(rekognition);
    }

    @Test
    void changingThresholdRequiresAnotherModerationCheck() throws Exception {
        S3Client s3 = s3WithImage();
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .metadata(Map.of("source-key", "old-source.png", ImageModeration.METADATA_KEY, "listing-v1-90.0")).build());
        assertThrows(UncheckedIOException.class, () -> optimizer(s3, rejectingClient())
                .ensureListingAssetRenditions(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void legacyRenditionsAreCheckedBeforeCopying() throws Exception {
        UUID listing = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        String stem = "listings/" + listing + "/" + asset;
        S3Client s3 = s3WithImage();
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            if (request.key().equals(stem + ".webp")) throw NoSuchKeyException.builder().statusCode(404).build();
            return HeadObjectResponse.builder().metadata(Map.of("source-key", "legacy.png")).build();
        });
        assertThrows(UncheckedIOException.class, () -> optimizer(s3, rejectingClient()).ensureListingAssetRenditions(listing, asset));
        verify(s3, never()).copyObject(any(CopyObjectRequest.class));
    }

    private static ImageOptimizer optimizer(S3Client s3, RekognitionClient rekognition) {
        return new ImageOptimizer(s3, new ImageSettings("assets", "secret"), new ImageModeration(rekognition, 80));
    }

    private static RekognitionClient rejectingClient() {
        RekognitionClient client = mock(RekognitionClient.class);
        when(client.detectModerationLabels(any(DetectModerationLabelsRequest.class))).thenReturn(
                DetectModerationLabelsResponse.builder().moderationLabels(
                        ModerationLabel.builder().name("Violence").confidence(99f).build()).build());
        return client;
    }

    private static S3Client s3WithImage() throws IOException {
        S3Client s3 = mock(S3Client.class);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "png", bytes);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes.toByteArray()));
        return s3;
    }
}
