package com.vestio.imageoptimizer;

record ImageSettings(String bucketName, String sourceJdbcUrl) {

    static ImageSettings fromEnvironment() {
        return new ImageSettings(required("S3_ASSETS_BUCKET"), null);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be configured");
        return value;
    }

}
