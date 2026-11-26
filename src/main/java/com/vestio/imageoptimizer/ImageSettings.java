package com.vestio.imageoptimizer;

record ImageSettings(String bucketName, String jdbcSecretName) {

    static ImageSettings fromEnvironment() {
        return new ImageSettings(required("S3_ASSETS_BUCKET"), jdbcSecretName(required("APP_ENV")));
    }

    static String jdbcSecretName(String environment) {
        return switch (environment) {
            case "dev" -> "DEV/PRIMARY_JDBC_URL";
            case "prod" -> "PROD/PRIMARY_JDBC_URL";
            default -> throw new IllegalStateException("APP_ENV must be dev or prod");
        };
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured");
        return value;
    }

}
