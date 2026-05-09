# Changes

## 2026-05-09

- Restructured the repository into `dependency-report-core`, `dependency-report-cli`, and `dependency-report-testkit`.
- Added a typed core domain model for catalog snapshots, version alias changes, alias usages, upgrade targets, release source resolution, fetched documents, risk assessment, generated report entries, and execution manifest data.
- Implemented version catalog parsing for `[versions]`, `[libraries]`, and `[plugins]` with `version.ref` support.
- Implemented alias diff detection and usage resolution across library aliases, BOM-style aliases, and Gradle plugin aliases.
- Added deterministic source resolution with the fixed strategy order `sourceRegistry` -> `githubRepositories` -> `changelogUrls` -> unresolved.
- Added HTTP-based release-note fetching with GitHub Releases API support and URL fetching support behind `ReleaseDocumentFetcher`.
- Fixed GitHub release fetching so it filters to the actual upgrade window `previousVersion -> currentVersion` instead of summarizing unrelated newer releases.
- Added an explicit LLM adapter boundary with disabled and static implementations for deterministic local behavior and tests.
- Implemented fallback narrative generation so report generation degrades gracefully when sources or LLM enrichment fail.
- Added rendering for `summary.txt`, `commit-body.txt`, `mr-description.txt`, `jira-description.txt`, `reviewer-checklist.md`, `risk-summary.md`, and `report.json`.
- Added a JVM CLI `generate` command that loads YAML config and writes output artifacts to an output directory.
- Fixed the Gradle `:dependency-report-cli:run` task to use the repository root as its working directory so sample commands with repo-relative paths like `samples/basic/...` run correctly.
- Added example config in `dependency-report.example.yaml`.
- Added fixture-based tests and golden outputs for enriched and fallback report paths.
- Added documented sample inputs and sample generated outputs under `samples/basic`.
- Documented architecture, deterministic boundaries, CLI usage, current scope, and extension points in `README.md`.
