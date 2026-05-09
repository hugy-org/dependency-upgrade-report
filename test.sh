./gradlew :dependency-report-cli:run --args="generate \
  --previous-catalog samples/basic/libs.before.toml \
  --current-catalog samples/basic/libs.after.toml \
  --config samples/basic/dependency-report.yaml \
  --output-dir samples/basic/output/test/"