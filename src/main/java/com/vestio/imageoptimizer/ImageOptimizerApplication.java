package com.vestio.imageoptimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import javax.sql.DataSource;
import java.nio.file.Path;

@SpringBootApplication
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
        return new SecretsManagerDataSource(secretsManager, settings.jdbcSecretArn(), mapper);
    }

    @Bean
    ImageOptimizer imageOptimizer(S3Client s3, ImageSettings settings) {
        return new ImageOptimizer(s3, settings);
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
