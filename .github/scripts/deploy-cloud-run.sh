#!/usr/bin/env bash
set -euo pipefail

required_variables=(
  APP_ALLOWED_ORIGINS
  APP_IMAGE
  APP_SESSION_SECRET_NAME
  CLOUD_RUN_RUNTIME_SERVICE_ACCOUNT
  CLOUD_RUN_SERVICE
  CLOUD_SQL_INSTANCE
  DB_NAME
  DB_PASSWORD_SECRET_NAME
  DB_USER
  GCP_ARTIFACT_IMAGE
  GCP_ARTIFACT_REPOSITORY
  GCP_PROJECT_ID
  GCP_REGION
  GITHUB_SHA
  IP_HASH_SECRET_NAME
)

for variable_name in "${required_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    printf 'required environment variable is missing: %s\n' "${variable_name}" >&2
    exit 2
  fi
done

docker_bin="${DOCKER_BIN:-docker}"
gcloud_bin="${GCLOUD_BIN:-gcloud}"
registry="${GCP_REGION}-docker.pkg.dev"
artifact_image="${registry}/${GCP_PROJECT_ID}/${GCP_ARTIFACT_REPOSITORY}/${GCP_ARTIFACT_IMAGE}"
target_image="${artifact_image}:${GITHUB_SHA}"
db_url="jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${CLOUD_SQL_INSTANCE}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlRefreshStrategy=lazy"
runtime_environment="^@^DB_URL=${db_url}@DB_USER=${DB_USER}@APP_SESSION_SECURE=true@APP_ALLOWED_ORIGINS=${APP_ALLOWED_ORIGINS}"
runtime_secrets="DB_PASSWORD=${DB_PASSWORD_SECRET_NAME}:latest,APP_SESSION_SECRET=${APP_SESSION_SECRET_NAME}:latest,APP_ABUSE_IP_HASH_SECRET=${IP_HASH_SECRET_NAME}:latest"

"${gcloud_bin}" auth configure-docker "${registry}" --quiet
"${docker_bin}" tag "${APP_IMAGE}" "${target_image}"
push_output="$("${docker_bin}" push "${target_image}")"
printf '%s\n' "${push_output}"
pushed_digest="$(printf '%s\n' "${push_output}" | sed -nE 's/.*digest: (sha256:[0-9a-f]{64}).*/\1/p' | tail -n 1)"

if [[ ! "${pushed_digest}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  printf 'could not resolve pushed image digest for %s\n' "${target_image}" >&2
  exit 3
fi

if [[ -n "${EXPECTED_IMAGE_DIGEST:-}" && "${pushed_digest}" != "${EXPECTED_IMAGE_DIGEST}" ]]; then
  printf 'pushed digest %s does not match preview digest %s\n' "${pushed_digest}" "${EXPECTED_IMAGE_DIGEST}" >&2
  exit 4
fi

immutable_image="${artifact_image}@${pushed_digest}"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'image_digest=%s\n' "${pushed_digest}" >> "${GITHUB_OUTPUT}"
  printf 'image_ref=%s\n' "${immutable_image}" >> "${GITHUB_OUTPUT}"
fi

"${gcloud_bin}" run deploy "${CLOUD_RUN_SERVICE}" \
  "--project=${GCP_PROJECT_ID}" \
  "--region=${GCP_REGION}" \
  "--image=${immutable_image}" \
  "--service-account=${CLOUD_RUN_RUNTIME_SERVICE_ACCOUNT}" \
  "--update-env-vars=${runtime_environment}" \
  "--update-secrets=${runtime_secrets}" \
  --platform=managed \
  --allow-unauthenticated \
  --execution-environment=gen2 \
  --cpu=1 \
  --memory=512Mi \
  --concurrency=80 \
  --timeout=3600 \
  --min=0 \
  --max=1 \
  --cpu-throttling \
  --quiet

printf 'deployed Cloud Run service=%s image=%s\n' "${CLOUD_RUN_SERVICE}" "${immutable_image}"
