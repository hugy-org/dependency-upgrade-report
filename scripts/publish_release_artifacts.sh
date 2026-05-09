#!/usr/bin/env bash
# Usage:
#   scripts/publish_release_artifacts.sh 0.1.0
#   scripts/publish_release_artifacts.sh 0.1.0 v0.1.0
#
# Packages the CLI, creates/pushes the git tag, creates the GitHub Release if needed,
# and uploads the packaged CLI zip plus checksum.
set -euo pipefail

VERSION="${1:?Usage: $0 <version> [tag]}"
TAG="${2:-v${VERSION}}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if ! command -v gh >/dev/null 2>&1; then
  echo "ERROR: GitHub CLI 'gh' is required but was not found in PATH." >&2
  exit 1
fi

cd "${REPO_ROOT}"

./gradlew :dependency-report-cli:installDist

ARTIFACT_DIR="${REPO_ROOT}/dependency-report-cli/build/install"
ZIP_FILE="${ARTIFACT_DIR}/dependency-report-${VERSION}.zip"
CHECKSUM_FILE="${ZIP_FILE}.sha256"

rm -f "${ZIP_FILE}" "${CHECKSUM_FILE}"

pushd "${ARTIFACT_DIR}" >/dev/null
zip -r "dependency-report-${VERSION}.zip" dependency-report
shasum -a 256 "dependency-report-${VERSION}.zip" > "dependency-report-${VERSION}.zip.sha256"
popd >/dev/null

if [ ! -f "${ZIP_FILE}" ]; then
  echo "ERROR: Expected release archive not found: ${ZIP_FILE}" >&2
  exit 1
fi

if [ ! -f "${CHECKSUM_FILE}" ]; then
  echo "ERROR: Expected checksum file not found: ${CHECKSUM_FILE}" >&2
  exit 1
fi

if git rev-parse "${TAG}" >/dev/null 2>&1; then
  echo "Tag ${TAG} already exists locally."
else
  git tag -a "${TAG}" -m "Release ${TAG}"
  echo "Created local tag ${TAG}."
fi

git push origin "refs/tags/${TAG}"

if gh release view "${TAG}" >/dev/null 2>&1; then
  echo "GitHub Release ${TAG} already exists."
else
  gh release create "${TAG}" \
    --title "${TAG}" \
    --generate-notes
  echo "Created GitHub Release ${TAG}."
fi

gh release upload "${TAG}" \
  "${ZIP_FILE}" \
  "${CHECKSUM_FILE}" \
  --clobber

echo "Published ${TAG} end to end."
echo "Uploaded release artifacts for ${TAG}:"
echo "  ${ZIP_FILE}"
echo "  ${CHECKSUM_FILE}"
