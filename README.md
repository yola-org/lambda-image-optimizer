# Lambda image optimizer

The Java 25 Spring Boot image optimizer runs as an SQS-triggered Lambda or as a command-line job. It converts listing images to WebP and uses Hibernate to record optimized filenames and advance listing status.

## Outputs

Each input image produces aspect-ratio-preserving WebP files. Images are never enlarged.

| Rendition | Maximum longest edge | WebP quality | Intended use |
| --- | ---: | ---: | --- |
| `original.webp` | 2560px | 90 | Listing detail / high-resolution display |
| `original-thumbnail.webp` | 960px | 84 | Desktop cards and galleries |
| `original-thumbnail-mobile.webp` | 480px | 82 | Mobile cards and constrained connections |

The backend stores uploaded images as `listings/<listing-id>/<asset-id>/original.<ext>`. The Lambda keeps the directory and basename and writes these siblings:

```text
listings/<listing-id>/<asset-id>/original.webp
listings/<listing-id>/<asset-id>/original-thumbnail.webp
listings/<listing-id>/<asset-id>/original-thumbnail-mobile.webp
```

Each non-WebP source is deleted after every rendition for its SQS message has been written. A WebP input is replaced in place after its smaller renditions are written. Retries reuse completed outputs. When needed, the Lambda copies older renditions into the current layout.

## SQS message contract

```json
{
  "userId": "1e817a52-4eba-4174-87b9-3102aa7552e0",
  "listingId": "81297688-1533-4f03-abd2-8be5d6ea8e96",
  "assetKeys": ["listings/81297688-1533-4f03-abd2-8be5d6ea8e96/969680cf-17ff-4492-b4f6-6a58c309025c/original.png"]
}
```

`keys` is also accepted in place of `assetKeys`. Enable `ReportBatchItemFailures` on the event source mapping so SQS can retry failed records individually.

Hibernate stores `original.webp` in `listing_asset_keys.optimized_name` for each completed asset. The Lambda advances a listing from `AWAITING_ASSETS` to `AVAILABLE` once all of its current assets have optimized filenames. Run the backend Liquibase migrations before deploying this Lambda version.

## Configuration

| Variable | Description |
| --- | --- |
| `S3_ASSETS_BUCKET` | Bucket for source images and optimized outputs |
| `APP_ENV` | `dev` reads `DEV/PRIMARY_JDBC_URL`. `prod` reads `PROD/PRIMARY_JDBC_URL`. |

The Lambda reads the secret when it opens a database connection. The secret can contain a plain PostgreSQL JDBC URL or a JSON property named `PRIMARY_JDBC_URL` or `JDBC_PRIMARY_URL`. The execution role needs `secretsmanager:GetSecretValue` for the secret and `kms:Decrypt` if a customer-managed key protects it. It also needs S3 object read, write and delete permissions, bucket listing permission, and network access to PostgreSQL.

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

