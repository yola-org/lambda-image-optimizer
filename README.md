# Lambda image optimizer

The Java 25 Spring Boot image optimizer runs as an SQS-triggered Lambda or as a command-line job. It converts listing images to WebP and uses Hibernate to advance listing status.

## Outputs

Each input image produces aspect-ratio-preserving WebP files. Images are never enlarged.

| Rendition | Maximum longest edge | WebP quality | Intended use |
| --- | ---: | ---: | --- |
| `<asset-id>.webp` | 2560px | 90 | Listing detail / high-resolution display |
| `<asset-id>_thumbnail.webp` | 960px | 84 | Desktop cards and galleries |
| `<asset-id>_thumbnail_mobile.webp` | 480px | 82 | Mobile cards and constrained connections |

The backend stores uploaded images as `listings/<listing-id>/<asset-id>.<ext>`. The Lambda keeps the directory and basename and writes these siblings:

```text
listings/<listing-id>/<asset-id>.webp
listings/<listing-id>/<asset-id>_thumbnail.webp
listings/<listing-id>/<asset-id>_thumbnail_mobile.webp
```

Each non-WebP source is deleted after every rendition for its SQS message has been written. A WebP input is replaced in place after its smaller renditions are written. Retries reuse completed outputs. When needed, the Lambda copies older renditions into the current layout.

## SQS message contract

```json
{
  "userId": "1e817a52-4eba-4174-87b9-3102aa7552e0",
  "listingId": "81297688-1533-4f03-abd2-8be5d6ea8e96",
  "assetKeys": ["listings/81297688-1533-4f03-abd2-8be5d6ea8e96/969680cf-17ff-4492-b4f6-6a58c309025c.png"]
}
```

`keys` is also accepted in place of `assetKeys`. Enable `ReportBatchItemFailures` on the event source mapping so SQS can retry failed records individually.

The Lambda uses the listing's asset UUIDs to verify that all WebP renditions exist in S3 before advancing the listing from `AWAITING_ASSETS` to `AVAILABLE`.

That transition saves one `listing-published` notification through this
application's repository. `NotificationService` saves and flushes the notification,
then calls the shared `AppSyncEventsPublisher` directly and waits for the send to
complete. Lambda has no internal notification event or dispatcher. Duplicate processing messages do not create another publication
notification for an already available listing. A new image-processing cycle can
produce a new notification.

There is no notification outbox. AppSync send failures propagate and roll back the
notification and listing status transaction so SQS processing can retry. The live
send happens before database commit, so a later commit failure may leave a live
event whose database transaction did not commit.

Deploy backend migration `016-simplify-notifications` before this Lambda.
The backend sends `listing-created` when a listing is created.

## Configuration

| Variable | Description |
| --- | --- |
| `S3_ASSETS_BUCKET` | Bucket for source images and optimized outputs |
| `APPSYNC_EVENTS_HTTP_ENDPOINT` | HTTPS AppSync Events endpoint ending in `/event` |
| `APPSYNC_EVENTS_REGION` | AppSync signing region (defaults to `AWS_REGION`, then `ca-central-1`) |
| `APP_ENV` | `dev` reads `DEV/PRIMARY_JDBC_URL`. `prod` reads `PROD/PRIMARY_JDBC_URL`. |

The Lambda reads the secret when it opens a database connection. The secret can contain a plain PostgreSQL JDBC URL or a JSON property named `PRIMARY_JDBC_URL` or `JDBC_PRIMARY_URL`. The execution role needs `secretsmanager:GetSecretValue` for the secret and `kms:Decrypt` if a customer-managed key protects it. It also needs S3 object read, write and delete permissions, bucket listing permission, network access to PostgreSQL, and `appsync:EventPublish` restricted to the notifications namespace. The infra module supplies the AppSync variables and this policy.

## Build and run

```bash
./gradlew build
docker build -f docker/Dockerfile -t image-optimizer .
```

Run the same workflow with the Spring Boot `CommandLineRunner` by setting `S3_ASSETS_BUCKET`, `APP_ENV`, and the AWS SDK region and credentials for the chosen environment:

```bash
./gradlew run --args='/path/to/request.json'
```

## CI/CD Workflow

GitHub Actions CI is configured in `.github/workflows/ci.yml`.

- **Verification:** Version calculation, Gradle tests, Spotless, JaCoCo, and the two SpotBugs jobs run independently. The Gradle job packages the runtime files with `stageLambda` after its tests pass. CI uploads the JaCoCo and SpotBugs reports. SpotBugs findings are report-only.
- **Pull requests to `main` and manual runs:** The workflow runs verification jobs.
- **Pushes to `main`:** After the release gate and verification jobs succeed, the workflow runs the release jobs:
  1. `docker`: Builds and pushes the Docker container image to Amazon ECR.
  2. `tag`: Creates and pushes the git release tag.
  3. `infra-bump`: Opens a PR against `yola-org/aws-infra` to bump `image_optimizer_image_tag`.


## Shared notification contract

The entity, `NotificationType`, `EventType`, `Event` and publisher come from
`vestio-common`. This application keeps its own Spring Data notification
repository and service. The service sends directly through the shared publisher;
it does not use application events, dispatchers or duplicate shared types.

A notification contains `userId`, `type`, JSON `payload`, optional `listingId`,
`id`, `createdAt` and `readAt`. The event envelope contains `id`, `type`, JSON map
`payload` and `occurredAt`. Event type `notification` identifies a saved
notification payload; the envelope also supports type `chat` for future use.
The shared contract fixture verifies the same JSON shape in all three projects.

### Shared events library

Notifications, enums, realtime event payloads and the AppSync publisher use
`com.vestio:vestio-common:0.3.0` from GitHub Packages. Spring repositories and
transaction listeners remain in this application. Shared JPA entities are included
in the application's entity scan.

For local builds, set the following in `~/.gradle/gradle.properties` (never commit credentials):

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_TOKEN_WITH_READ_PACKAGES
```

Alternatively, set `GITHUB_ACTOR` and `PACKAGES_READ_TOKEN` (or `GITHUB_TOKEN`).
CI passes `PACKAGES_READ_TOKEN`, falling back to `INFRA_GITHUB_TOKEN`. Configure
a token with `read:packages` and access to the `yola-org/vestio-common` package.
The reusable Gradle workflows in `shared-workflows` must include package authentication.
