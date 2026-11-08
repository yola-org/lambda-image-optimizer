package com.vestio.imageoptimizer;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOptimizerTest {
    @Test
    void createsThreeWebpFilesThenDeletesTheSource() throws Exception {
        List<PutObjectRequest> uploads = new ArrayList<>();
        List<DeleteObjectRequest> deletions = new ArrayList<>();
        S3Client s3 = (S3Client) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{S3Client.class}, (proxy, method, args) -> {
            if (method.getName().equals("getObjectAsBytes")) return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), jpeg());
            if (method.getName().equals("putObject")) { uploads.add((PutObjectRequest) args[0]); return null; }
            if (method.getName().equals("deleteObject")) { deletions.add((DeleteObjectRequest) args[0]); return null; }
            if (method.getName().equals("serviceName")) return "S3";
            throw new UnsupportedOperationException(method.getName());
        });
        ImageOptimizer optimizer = new ImageOptimizer(s3, new ImageSettings("source", "jdbc:postgresql://localhost/test"));

        optimizer.optimize(new OptimizationRequest("listing-1", List.of("uploads/kitchen.jpg")));

        assertEquals(3, uploads.size());
        assertEquals(List.of(
                "listings/listing-1/uploads/kitchen/original.webp",
                "listings/listing-1/uploads/kitchen/thumbnail.webp",
                "listings/listing-1/uploads/kitchen/thumbnail-mobile.webp"),
                uploads.stream().map(PutObjectRequest::key).toList());
        assertTrue(uploads.stream().allMatch(it -> "image/webp".equals(it.contentType())));
        assertEquals(List.of("uploads/kitchen.jpg"), deletions.stream().map(DeleteObjectRequest::key).toList());
    }

    private static byte[] jpeg() throws Exception {
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, 0x336699);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", bytes);
        return bytes.toByteArray();
    }
}
