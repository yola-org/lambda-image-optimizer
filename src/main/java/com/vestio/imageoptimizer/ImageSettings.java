package com.vestio.imageoptimizer;

record ImageSettings(String bucketName, String jdbcSecretArn) {

    static ImageSettings fromEnvironment() {
        return new ImageSettings(required("S3_ASSETS_BUCKET"), required("PRIMARY_JDBC_SECRET_ARN"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured");
        return value;
    }

}
