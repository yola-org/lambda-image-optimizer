package com.vestio.imageoptimizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** SQS handler. Configure the event source mapping with ReportBatchItemFailures. */
public final class SqsLambdaHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private static final Logger LOGGER = Logger.getLogger(SqsLambdaHandler.class.getName());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ImageOptimizer imageOptimizer = new ImageOptimizer(S3Client.create(), ImageSettings.fromEnvironment());
    private final SecretsManagerClient secretsManager = SecretsManagerClient.create();

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null) {
            LOGGER.warning("Received an SQS event without records");
            return new SQSBatchResponse(List.of());
        }
        if (event.getRecords().isEmpty()) {
            return new SQSBatchResponse(List.of());
        }

        checkDatabaseConnection();

        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        LOGGER.info("Processing " + event.getRecords().size() + " SQS record(s)");
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            String messageId = message.getMessageId();
            String listingId = "null";
            try {
                OptimizationRequest request = objectMapper.readValue(message.getBody(), OptimizationRequest.class);
                listingId = request.listingId();
                imageOptimizer.optimize(request);
                LOGGER.info("Optimized listing " + listingId + " from SQS message " + messageId);
            } catch (Exception exception) {
                // Log the full stack trace once at the message boundary; the message is retried via ReportBatchItemFailures.
                LOGGER.log(Level.SEVERE, "Image optimization failed for listing " + listingId
                        + " (SQS message " + messageId + ")", exception);
                failures.add(new SQSBatchResponse.BatchItemFailure(messageId));
            }
        }
        if (!failures.isEmpty()) {
            LOGGER.warning(failures.size() + " of " + event.getRecords().size() + " SQS message(s) failed");
        } else {
            LOGGER.info("Successfully processed all " + event.getRecords().size() + " SQS record(s)");
        }
        return new SQSBatchResponse(failures);
    }

    private void checkDatabaseConnection() {
        String secretId = Boolean.parseBoolean(System.getenv("APP_DEV"))
                ? "DEV/PRIMARY_JDBC_URL"
                : "PROD/PRIMARY_JDBC_URL";
        String secret = secretsManager.getSecretValue(request -> request.secretId(secretId)).secretString();

        String jdbcUrl = readJdbcUrl(secret);
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            // Opening the connection is the entire check.
        } catch (SQLException exception) {
            // The driver's exception message can contain connection details.
            throw new IllegalStateException("Primary JDBC connection check failed (SQL state "
                    + exception.getSQLState() + ")");
        }
    }

    private String readJdbcUrl(String secret) {
        if (secret != null) {
            String value = secret.strip();
            if (value.startsWith("jdbc:postgresql:")) {
                return value;
            }
            try {
                JsonNode json = objectMapper.readTree(value);
                if (json != null) {
                    String url = json.path("JDBC_PRIMARY_URL").asText().strip();
                    if (url.startsWith("jdbc:postgresql:")) {
                        return url;
                    }
                }
            } catch (JsonProcessingException ignored) {
                // The parser's exception message can contain the secret.
            }
        }
        throw new IllegalStateException("The primary JDBC secret must contain a PostgreSQL JDBC URL");
    }
}
