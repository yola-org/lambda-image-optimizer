FROM public.ecr.aws/lambda/java:17

# ------------------------------------------------------------
# Application version (provided by GitLab CI)
# ------------------------------------------------------------

ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}

# ------------------------------------------------------------
# Copy the Gradle Shadow JAR
# ------------------------------------------------------------

COPY build/libs/*-all.jar ${LAMBDA_TASK_ROOT}/lib/

# ------------------------------------------------------------
# Lambda handler
# ------------------------------------------------------------

CMD ["com.vestio.imageoptimizer.SqsLambdaHandler"]
