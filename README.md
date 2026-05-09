# Dependency Upgrade Report

Dependency Upgrade Report is a reusable Kotlin library plus CLI for deterministic, CI-friendly enrichment of dependency version changes. It compares two `libs.versions.toml` snapshots, resolves changelog or release-note sources through explicit rules, fetches release-note content without autonomous browsing, and renders human-readable plus machine-readable outputs.

## Purpose

This project is meant to sit after an upstream dependency update step. A separate updater can produce a new `libs.versions.toml`, and this tool explains what changed. It does not create branches, commits, merge requests, Jira issues, or autonomous plans.

## Architecture

The repository is organized as:

- `dependency-report-core`: domain model, version catalog parsing, diffing, source resolution, fetching abstractions, LLM adapter boundary, fallback behavior, and report rendering
- `dependency-report-cli`: JVM CLI for argument parsing, YAML config loading, file IO, and writing outputs
- `dependency-report-testkit`: lightweight fixture helpers for tests

The intended future extension point is a thin `dependency-report-gradle-plugin` wrapper that delegates to `core` instead of adding repository-specific logic.

## Deterministic boundaries

Determinism is enforced by explicit orchestration:

- input catalogs are passed in as files
- source resolution follows a fixed order: `sourceRegistry`, `githubRepositories`, `changelogUrls`, then unresolved
- release-note fetching is adapter-driven and URL-based
- GitHub release-note fetching is filtered to the actual upgrade window instead of whichever latest releases the API returns first
- LLM enrichment is behind an explicit adapter interface
- failures degrade to diff-based fallback summaries instead of triggering agent-like behavior

The tool does not perform web search, repository mutation, or CI-side automation decisions.

## CLI usage

```bash
./gradlew :dependency-report-cli:run --args="generate \
  --previous-catalog /path/to/libs.before.toml \
  --current-catalog /path/to/libs.after.toml \
  --config /path/to/dependency-report.yaml \
  --output-dir /path/to/output"
```

Generated files:

- `report.json`
- `summary.txt`
- `commit-body.txt`
- `mr-description.txt`
- `jira-description.txt`
- `reviewer-checklist.md`
- `risk-summary.md`

An example config lives at [dependency-report.example.yaml](/Users/tomashugec/my%20projects/dependency-upgrade-report/dependency-report.example.yaml:1), and a runnable sample input/output set lives under [samples/basic](/Users/tomashugec/my%20projects/dependency-upgrade-report/samples/basic).

## Config shape

```yaml
sourceRegistry:
  - alias: kotlin
    type: GITHUB_RELEASES
    repository: JetBrains/kotlin
githubRepositories:
  - pluginId: com.github.ben-manes.versions
    repository: ben-manes/gradle-versions-plugin
changelogUrls:
  - module: org.jetbrains.kotlin:kotlin-bom
    url: https://kotlinlang.org/docs/releases.html
github:
  token: null
  apiBaseUrl: https://api.github.com
  maxReleases: 5
  maxScanReleases: 100
llm:
  mode: disabled
```

## Current MVP scope

The first iteration currently supports:

- parsing two version catalogs
- detecting changed aliases from `[versions]`
- mapping aliases to libraries, BOMs, and Gradle plugins
- deterministic source resolution through config-backed registry and GitHub mappings
- basic URL and GitHub Releases fetching
- LLM summarization through an adapter interface
- fallback summaries when fetching or LLM enrichment fails
- text, markdown, and JSON report rendering
- fixture-based tests with golden outputs

Not implemented yet:

- Maven metadata or POM fallback resolution
- caching
- richer risk heuristics
- artifact publishing
- Gradle plugin wrapper

## Extension points

The main seams for reuse are:

- `ReleaseDocumentFetcher` for controlled fetching strategies
- `LlmReportGenerator` for schema-validated model integrations
- `ReleaseSourceResolver` inputs via config mappings
- report renderers in `core` for custom output formats

## Development notes

The sample outputs in [samples/basic/output](/Users/tomashugec/my%20projects/dependency-upgrade-report/samples/basic/output) document the intended artifact shape for the first iteration. Tests use fixtures and golden files under [dependency-report-core/src/test/resources/fixtures/basic](/Users/tomashugec/my%20projects/dependency-upgrade-report/dependency-report-core/src/test/resources/fixtures/basic).
