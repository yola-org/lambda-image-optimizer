package com.vestio.imageoptimizer;

import tools.jackson.databind.json.JsonMapper;

import com.vestio.common.entity.Notification;

import com.vestio.common.publisher.AppSyncEventsPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import javax.sql.DataSource;
import java.nio.file.Path;

@SpringBootApplication
@EntityScan(basePackageClasses = {OptimizerListing.class, Notification.class})
public class ImageOptimizerApplication {
    public static void main(String[] args) {
        start(args).close();
    }

    static ConfigurableApplicationContext start(String... args) {
        SpringApplication application = new SpringApplication(ImageOptimizerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(args);
    }

    @Bean
    AppSyncEventsPublisher appSyncEventsPublisher(
            @Value("${APPSYNC_EVENTS_HTTP_ENDPOINT}") URI endpoint,
            @Value("${APPSYNC_EVENTS_REGION:${AWS_REGION:ca-central-1}}") String region,
            JsonMapper mapper) {
        return new AppSyncEventsPublisher(endpoint, region, mapper, DefaultCredentialsProvider.create(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    @Bean
    JsonMapper eventMapper() {
        return JsonMapper.builder().build();
    }

    @Bean
    ImageSettings imageSettings() {
        return ImageSettings.fromEnvironment();
    }

    @Bean
    ObjectMapper messageMapper() {
        return new ObjectMapper();
    }

    @Bean
    S3Client s3Client() {
        return S3Client.create();
    }

    @Bean
    SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.create();
    }

    @Bean
    DataSource dataSource(SecretsManagerClient secretsManager, ImageSettings settings, ObjectMapper mapper) {
        return new SecretsManagerDataSource(secretsManager, settings.jdbcSecretName(), mapper);
    }

    @Bean
    RekognitionClient rekognitionClient() {
        // Canada Central does not support DetectModerationLabels.
        return RekognitionClient.builder().region(Region.US_EAST_1).build();
    }

    @Bean
    ImageModeration imageModeration(RekognitionClient rekognition,
            @Value("${IMAGE_MODERATION_MIN_CONFIDENCE:80}") float minConfidence) {
        return new ImageModeration(rekognition, minConfidence);
    }

    @Bean
    ImageOptimizer imageOptimizer(S3Client s3, ImageSettings settings, ImageModeration moderation) {
        return new ImageOptimizer(s3, settings, moderation);
    }

    @Bean
    CommandLineRunner optimizationCommand(ObjectMapper mapper, OptimizationService service) {
        return args -> {
            if (args.length == 0) {
                return;
            }
            if (args.length != 1) {
                throw new IllegalArgumentException("Pass one path to an optimization request JSON file");
            }
            service.process(mapper.readValue(Path.of(args[0]).toFile(), OptimizationRequest.class));
        };
    }
}
