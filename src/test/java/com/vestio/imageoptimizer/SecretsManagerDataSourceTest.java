package com.vestio.imageoptimizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecretsManagerDataSourceTest {
    private static final String SECRET_ARN = "arn:aws:secretsmanager:ca-central-1:123456789012:secret:test";
    private static final String JDBC_URL = "jdbc:postgresql://unused.invalid/listings";

    @Test
    void fetchesTheConfiguredSecretAndOpensJdbcConnectionWithItsUrl() throws Exception {
        SecretsManagerClient secrets = mock(SecretsManagerClient.class);
        GetSecretValueRequest request = stubSecret(secrets, "{\"PRIMARY_JDBC_URL\":\"" + JDBC_URL + "\"}");
        SecretsManagerDataSource dataSource = new SecretsManagerDataSource(secrets, SECRET_ARN, new ObjectMapper());
        Connection connection = mock(Connection.class);

        try (var driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(JDBC_URL)).thenReturn(connection);

            assertSame(connection, dataSource.getConnection());

            verify(secrets).getSecretValue(request);
            driverManager.verify(() -> DriverManager.getConnection(JDBC_URL));
            driverManager.verifyNoMoreInteractions();
        }
    }

    @Test
    void rejectsAnInvalidSecretBeforeOpeningJdbcConnection() {
        SecretsManagerClient secrets = mock(SecretsManagerClient.class);
        GetSecretValueRequest request = stubSecret(secrets, "{\"PRIMARY_JDBC_URL\":\"invalid\"}");
        SecretsManagerDataSource dataSource = new SecretsManagerDataSource(secrets, SECRET_ARN, new ObjectMapper());

        try (var driverManager = mockStatic(DriverManager.class)) {
            assertThrows(IllegalStateException.class, dataSource::getConnection);

            verify(secrets).getSecretValue(request);
            driverManager.verifyNoInteractions();
        }
    }

    private static GetSecretValueRequest stubSecret(SecretsManagerClient secrets, String value) {
        GetSecretValueRequest request = GetSecretValueRequest.builder().secretId(SECRET_ARN).build();
        doCallRealMethod().when(secrets)
                .getSecretValue(ArgumentMatchers.<Consumer<GetSecretValueRequest.Builder>>any());
        when(secrets.getSecretValue(request)).thenReturn(GetSecretValueResponse.builder().secretString(value).build());
        return request;
    }
}
