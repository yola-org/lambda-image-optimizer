package com.vestio.imageoptimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.datasource.AbstractDataSource;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

final class SecretsManagerDataSource extends AbstractDataSource {
    private final SecretsManagerClient secretsManager;
    private final String secretArn;
    private final ObjectMapper mapper;

    SecretsManagerDataSource(SecretsManagerClient secretsManager, String secretArn, ObjectMapper mapper) {
        this.secretsManager = secretsManager;
        this.secretArn = secretArn;
        this.mapper = mapper;
    }

    @Override
    public Connection getConnection() throws SQLException {
        String secret = secretsManager.getSecretValue(request -> request.secretId(secretArn)).secretString();
        try {
            return DriverManager.getConnection(readJdbcUrl(secret));
        } catch (SQLException exception) {
            // JDBC exception text can contain connection details.
            throw new SQLException("Could not connect to the primary database", exception.getSQLState());
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("Database credentials are supplied by Secrets Manager");
    }

    private String readJdbcUrl(String secret) {
        if (secret != null) {
            String value = secret.strip();
            if (value.startsWith("jdbc:postgresql:")) {
                return value;
            }
            try {
                JsonNode json = mapper.readTree(value);
                if (json != null) {
                    String url = json.path("PRIMARY_JDBC_URL").asText(
                            json.path("JDBC_PRIMARY_URL").asText()).strip();
                    if (url.startsWith("jdbc:postgresql:")) {
                        return url;
                    }
                }
            } catch (JsonProcessingException ignored) {
                // Parser exception text can contain the secret.
            }
        }
        throw new IllegalStateException("The primary JDBC secret must contain a PostgreSQL JDBC URL");
    }
}
