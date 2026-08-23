#!/usr/bin/env bash
set -euo pipefail

candidate_dir="${1:-delivery}"
# shellcheck disable=SC1090
source "${candidate_dir}/candidate.env"

test "${GIT_SHA}" = "${GITHUB_SHA}"
printf '%s  %s\n' "${ARCHIVE_SHA256}" "${candidate_dir}/minigame-image.tar" | sha256sum --check --status
docker load --input "${candidate_dir}/minigame-image.tar"
actual_image_id="$(docker image inspect --format '{{.Id}}' "${APP_IMAGE}")"
test "${actual_image_id}" = "${IMAGE_ID}"

printf 'verified candidate sha=%s image=%s id=%s\n' "${GIT_SHA}" "${APP_IMAGE}" "${IMAGE_ID}"
