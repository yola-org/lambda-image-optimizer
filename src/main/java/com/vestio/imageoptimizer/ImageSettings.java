package com.vestio.imageoptimizer;

record ImageSettings(String bucketName, String sourceJdbcUrl) {

    static ImageSettings fromEnvironment() {
        String bucketName = required("S3_ASSETS_BUCKET");
        String sourceJdbcUrl = required("PRIMARY_JDBC_URL");
        return new ImageSettings(bucketName, sourceJdbcUrl);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured");
        return value;
    }

}
