# Changelog

All notable customer-facing changes are documented here.

Each release section should stand on its own and describe the behavior shipped in this repo.

## Release Notes Instructions

1. Inspect the release window with `git log`, `git diff --stat`, and PR metadata from `gh pr view`.
2. Summarize only customer-facing runtime behavior, CLI behavior, contract semantics, reporting behavior, or tooling that ships from this repo.
3. Do not call out noise such as workflow edits, `ci skip`, raw version bumps, dependency churn, or minor refactors unless they materially change product behavior.
4. Keep each section standalone. Do not tell readers to look in another repo for the real notes.
5. When this repo rolls in bundled reporting or licensing changes, describe the shipped effect here instead of pointing to another changelog.

## Dependency Fold Instructions

- When generating notes for this repo, fold in customer-facing changes from:
  - `specmatic-reporter`, bumped in `specmatic/gradle.properties` via `specmaticReporterVersion`
- Because `specmatic-reporter` itself folds in:
  - `specmatic-license`, bumped in `specmatic-reporter/gradle.properties` via `specmaticLicenseVersion`
  - `specmatic-html-reporter`, copied into `specmatic-reporter/specmatic-reporter/src/main/resources/templates/ctrf-report/`
- When generating notes for downstream repos, this repo is consumed by:
  - `enterprise`, bumped in `enterprise/gradle.properties` via `specmaticVersion`

## Unreleased (2.46.6)

### Changed

- Improved nested object query validation and example diagnostics so failure paths now point to the actual serialized query keys, including bracketed and array-style segments, instead of less accurate synthesized breadcrumbs.
- Improved OpenAPI source-location tracking for referenced schemas so validation failures can retain the original JSON pointer from the source file that defined the schema.
- Improved `specmatic config upgrade` output for legacy configurations by keeping global mock and test settings under top-level `specmatic.settings` instead of moving them into dependency or system-under-test sections.
- Updated bundled reporting and licensing flows so license-aware CLI operations can use `--debug` for trace logging while retaining backward-compatible support for the older `--log-level` flag.
- Improved bundled license CLI output with clearer success, warning, and error cues, including colorized level labels on ANSI-capable terminals.

## 2.46.5 (2026-06-11)

### Added

- Added an MCP server with tools for mock servers, contract-test execution, and backward-compatibility runs.

### Changed

- Improved backward-compatibility output for external `$ref` changes by preserving clearer source locations and more structured breakage details.
- Improved CTRF and filesystem-based report metadata handling so specification paths remain more reliable in federated and local-file reporting flows.
- Reduced delays and timeout risk during DNS resolution when shipped reporting flows connect to Specmatic Insights.

## 2.46.4 (2026-06-03)

### Changed

- Removed legacy stub-usage, test-coverage, and older HTML coverage report outputs in favor of the newer CTRF and HTML reporting flow.
- Improved backward-compatibility CTRF spec-path handling so reported paths are recorded relative to the repository root and remain stable across platforms.
- Improved externalized example loading coverage and diagnostics for command and test flows.

## 2.46.3 (2026-06-01)

### Changed

- Improved backward-compatibility diagnostics by surfacing source locations for a wider range of breaking changes, including XML, composed schemas, params, headers, request bodies, and external references.
- Improved OpenAPI example handling for object query parameters, form-urlencoded payloads, parameterized media types, and XML `oneOf` examples across validation, mocks, and command flows.
- Added stronger backward-compatibility coverage for composition-heavy and XML-heavy API definitions.

## 2.46.2 (2026-05-28)

### Added

- Added CTRF backward-compatibility report generation with richer per-operation change tracking and status details.

### Changed

- Improved backward-compatibility reporting for multi-spec runs so all checked specs are retained in generated BCC output.
- Added WIP-aware backward-compatibility handling so WIP scenarios can appear in reports and console output without being treated like ordinary breaking failures.
- Improved change tracking for referenced and recursive schemas so operation-level status is calculated more accurately.
