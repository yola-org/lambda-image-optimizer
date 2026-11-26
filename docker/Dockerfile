FROM public.ecr.aws/lambda/java:25

# ------------------------------------------------------------
# Application version (provided by GitLab CI)
# ------------------------------------------------------------

ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}
ENV JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"

# ------------------------------------------------------------
# Copy the application and runtime dependency JARs
# ------------------------------------------------------------

COPY build/libs/*.jar ${LAMBDA_TASK_ROOT}/lib/

# ------------------------------------------------------------
# Lambda handler
# ------------------------------------------------------------

CMD ["com.vestio.imageoptimizer.SqsLambdaHandler"]
