#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:?Usage: $0 <version>}"

./gradlew :dependency-report-cli:installDist

pushd dependency-report-cli/build/install >/dev/null
zip -r "dependency-report-${VERSION}.zip" dependency-report
shasum -a 256 "dependency-report-${VERSION}.zip" > "dependency-report-${VERSION}.zip.sha256"
popd >/dev/null


