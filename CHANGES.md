# Changes

## 2026-05-11

- Simplified the MVP around one primary workflow: git-backed version catalog diff -> alias detection -> current-catalog usage mapping -> explicit YAML source or Maven POM inference -> fetch -> LLM or deterministic fallback -> render outputs.
- Removed legacy and advanced source-resolution complexity, including Gradle Plugin Portal inference, HTML scraping, and multiple inference modes.
- Simplified configuration to a smaller shape centered on `sources`, `github`, `fetch`, `inference`, and `llm`.
- Reduced rendered artifacts to `report.json`, `commit-body.txt`, `mr-description.txt`, and `jira-description.txt`.
- Unified Jira and MR rendering so both descriptions use the same generated text.
- Simplified GitHub release selection by removing adaptive broad-release behavior and keeping straightforward in-window stable selection.
- Removed the extra `dependency-report-testkit` module and updated tests to work directly from fixture files.
- Rewrote the README to document the smaller deterministic MVP and its intended boundaries.
