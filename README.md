# Lambda image optimizer

Java 25 AWS Lambda that consumes SQS messages, converts each listed S3 image to WebP, and deletes the source only after every output was successfully uploaded. It is intentionally a small Lambda handler, not Spring Boot: this keeps startup time, memory use, and the container image smaller.

## Outputs

Each input image produces aspect-ratio-preserving WebP files. Images are never enlarged.

| Rendition | Maximum longest edge | WebP quality | Intended use |
| --- | ---: | ---: | --- |
| `original.webp` | 2560px | 90 | Listing detail / high-resolution display |
| `thumbnail.webp` | 960px | 84 | Desktop cards and galleries |
| `thumbnail-mobile.webp` | 480px | 82 | Mobile cards and constrained connections |

For input key `uploads/a/kitchen.jpg` and listing `listing-123`, outputs are:

```text
optimized/listing-123/uploads/a/kitchen/original.webp
optimized/listing-123/uploads/a/kitchen/thumbnail.webp
optimized/listing-123/uploads/a/kitchen/thumbnail-mobile.webp
```

## SQS message contract

```json
{
  "listingId": "listing-123",
  "bucket": "optional-source-bucket",
  "assetKeys": ["uploads/a/kitchen.jpg", "uploads/a/front.png"]
}
```

`keys` is also accepted in place of `assetKeys`. The `bucket` field is optional; when absent the configured `SOURCE_BUCKET` is used.

## Configuration

| Variable | Required | Description |
| --- | --- | --- |
| `SOURCE_BUCKET` | Yes | Default source S3 bucket |
| `OUTPUT_BUCKET` | No | Bucket for WebP files; defaults to `SOURCE_BUCKET` |
| `OUTPUT_PREFIX` | No | Output key prefix; defaults to `optimized` |

Enable `ReportBatchItemFailures` on the SQS event source mapping. A malformed message or failed S3/image operation is returned as a failed record, without retrying successful batch records.

The Lambda role needs `s3:GetObject` and `s3:DeleteObject` on source objects, plus `s3:PutObject` and `s3:HeadObject` on the output prefix. If the output bucket differs, source deletion still occurs only after all output writes for the whole message have returned successfully.

## Build and deploy

```bash
./gradlew test build
docker build -t image-optimizer .
```

The image uses `public.ecr.aws/lambda/java:25`, an Amazon Linux 2023 Lambda base image. Configure the Lambda with enough memory for its largest input image (start with 1,024 MB) and a timeout appropriate to the batch size (start with 60 seconds).

## CI/CD Workflow

GitHub Actions CI is configured in `.github/workflows/ci.yml` and executes in a strict linear sequence:

```text
version ──> changes ──> build-and-test ──> release-gate ──> docker ──> tag ──> infra-bump
```

- **Pull Request / Branch MR:** Runs `version` (using the MR title for bump detection, dry-run without creating tags), `changes`, and `build-and-test`. All subsequent release jobs are skipped. Duplicate runs are prevented via branch triggers (`branches: [main]`) and concurrency cancellation (`cancel-in-progress: true`).
- **Merged PR on `main`:** When a branch is merged into `main`, `release-gate` confirms the merge. If application inputs changed:
  1. `docker`: Builds and pushes the Docker container image to Amazon ECR.
  2. `tag`: Creates and pushes the git release tag.
  3. `infra-bump`: Opens a PR against `yola-org/aws-infra` to bump `image_optimizer_image_tag`.
- **Direct pushes to `main`:** Blocked from triggering release actions by the release gate.

