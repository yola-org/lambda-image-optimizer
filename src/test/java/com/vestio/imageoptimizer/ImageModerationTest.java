package com.vestio.imageoptimizer;

import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.ModerationLabel;
import software.amazon.awssdk.services.rekognition.model.RekognitionException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ImageModerationTest {
    @ParameterizedTest
    @ValueSource(strings = {"Explicit", "Explicit Nudity", "Non-Explicit Nudity", "Violence",
            "Blood & Gore", "Drugs & Tobacco", "Drugs", "Visually Disturbing"})
    void rejectsProhibitedCategoriesAtThreshold(String category) {
        RekognitionClient client = client(ModerationLabel.builder().name(category).confidence(80f).build());
        assertThrows(ImageRejectedException.class, () -> new ImageModeration(client, 80).check(image()));
    }

    @Test
    void rejectsChildLabelsUsingTheirParent() {
        RekognitionClient client = client(ModerationLabel.builder().name("Physical Violence")
                .parentName("Graphic Violence").confidence(95f).build());
        assertThrows(ImageRejectedException.class, () -> new ImageModeration(client, 80).check(image()));
    }

    @Test
    void acceptsUnrelatedAndLowConfidenceLabels() throws Exception {
        RekognitionClient client = client(
                ModerationLabel.builder().name("Violence").confidence(79.9f).build(),
                ModerationLabel.builder().name("Female Swimwear or Underwear").confidence(99f).build(),
                ModerationLabel.builder().name("Kissing on the Lips")
                        .parentName("Non-Explicit Nudity of Intimate parts and Kissing").confidence(99f).build());
        new ImageModeration(client, 80).check(image());
    }

    @Test
    void convertsTransparentImagesToJpegAndUsesConfiguredConfidence() throws Exception {
        RekognitionClient client = client();
        new ImageModeration(client, 85).check(new BufferedImage(30, 60, BufferedImage.TYPE_INT_ARGB));
        ArgumentCaptor<DetectModerationLabelsRequest> capture = ArgumentCaptor.forClass(DetectModerationLabelsRequest.class);
        verify(client).detectModerationLabels(capture.capture());
        DetectModerationLabelsRequest request = capture.getValue();
        assertEquals(85f, request.minConfidence());
        byte[] bytes = request.image().bytes().asByteArray();
        assertTrue(bytes.length < 5_000_000);
        assertEquals(0xff, bytes[0] & 0xff);
        assertEquals(0xd8, bytes[1] & 0xff);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertTrue(decoded.getWidth() >= 80 && decoded.getHeight() >= 80);
        assertEquals(0xffffff, decoded.getRGB(0, 0) & 0xffffff);
    }

    @Test
    void propagatesRekognitionFailures() {
        RekognitionClient client = mock(RekognitionClient.class);
        when(client.detectModerationLabels(any(DetectModerationLabelsRequest.class)))
                .thenThrow(RekognitionException.builder().message("unavailable").build());
        assertThrows(RekognitionException.class, () -> new ImageModeration(client, 80).check(image()));
    }

    @ParameterizedTest
    @ValueSource(floats = {-1, 101, Float.NaN, Float.POSITIVE_INFINITY})
    void rejectsInvalidConfidence(float confidence) {
        assertThrows(IllegalArgumentException.class, () -> new ImageModeration(mock(RekognitionClient.class), confidence));
    }

    private static BufferedImage image() {
        return new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    }

    private static RekognitionClient client(ModerationLabel... labels) {
        RekognitionClient client = mock(RekognitionClient.class);
        when(client.detectModerationLabels(any(DetectModerationLabelsRequest.class)))
                .thenReturn(DetectModerationLabelsResponse.builder().moderationLabels(labels).build());
        return client;
    }
}
