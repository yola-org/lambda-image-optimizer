package com.vestio.imageoptimizer;

import org.mockito.MockedStatic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    private static final String JDBC_URL = "jdbc:postgresql://unused.invalid/listings";

    @ParameterizedTest
    @CsvSource({"dev, DEV/PRIMARY_JDBC_URL", "prod, PROD/PRIMARY_JDBC_URL"})
    void fetchesTheEnvironmentSecretAndOpensJdbcConnectionWithItsUrl(String environment, String expectedName)
            throws Exception {
        SecretsManagerClient secrets = mock(SecretsManagerClient.class);
        GetSecretValueRequest request = stubSecret(secrets, expectedName, "{\"PRIMARY_JDBC_URL\":\"" + JDBC_URL + "\"}");
        SecretsManagerDataSource dataSource = new SecretsManagerDataSource(
                secrets, ImageSettings.jdbcSecretName(environment), new ObjectMapper());
        Connection connection = mock(Connection.class);

        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(JDBC_URL)).thenReturn(connection);

            assertSame(connection, dataSource.getConnection());

            verify(secrets).getSecretValue(request);
            driverManager.verify(() -> DriverManager.getConnection(JDBC_URL));
            driverManager.verifyNoMoreInteractions();
        }
    }

    @Test
    void rejectsAnUnknownEnvironment() {
        assertThrows(IllegalStateException.class, () -> ImageSettings.jdbcSecretName("staging"));
    }

    @Test
    void rejectsAnInvalidSecretBeforeOpeningJdbcConnection() {
        SecretsManagerClient secrets = mock(SecretsManagerClient.class);
        String secretName = "DEV/PRIMARY_JDBC_URL";
        GetSecretValueRequest request = stubSecret(secrets, secretName, "{\"PRIMARY_JDBC_URL\":\"invalid\"}");
        SecretsManagerDataSource dataSource = new SecretsManagerDataSource(secrets, secretName, new ObjectMapper());

        try (MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            assertThrows(IllegalStateException.class, dataSource::getConnection);

            verify(secrets).getSecretValue(request);
            driverManager.verifyNoInteractions();
        }
    }

    private static GetSecretValueRequest stubSecret(SecretsManagerClient secrets, String name, String value) {
        GetSecretValueRequest request = GetSecretValueRequest.builder().secretId(name).build();
        doCallRealMethod().when(secrets)
                .getSecretValue(ArgumentMatchers.<Consumer<GetSecretValueRequest.Builder>>any());
        when(secrets.getSecretValue(request)).thenReturn(GetSecretValueResponse.builder().secretString(value).build());
        return request;
    }
}
