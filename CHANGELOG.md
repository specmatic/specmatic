# Changelog

All notable customer-facing changes are documented here.

Each release section should stand on its own and describe the behavior shipped in this repo.

## Release Notes Instructions

1. Inspect the release window with `git log`, `git diff --stat`, and PR metadata from `gh pr view`.
2. Summarize only customer-facing runtime behavior, CLI behavior, contract semantics, reporting behavior, or tooling that ships from this repo.
3. Do not call out noise such as workflow edits, `ci skip`, raw version bumps, dependency churn, or minor refactors unless they materially change product behavior.
4. Keep each section standalone. Do not tell readers to look in another repo for the real notes.
5. Before drafting the section, compare the bundled dependency versions in the current `gradle.properties` against the last release tag's `gradle.properties` and identify every bundled repo version that changed.
6. For each changed bundled repo version, inspect that repo's changelog or release notes for the exact version delta being shipped, then fold only the customer-facing effect into this section.
7. If a bundled repo version changed but its release notes explicitly say there were no user-facing changes, do not add a bullet for it.
8. When this repo rolls in bundled reporting or licensing changes, describe the shipped effect here instead of pointing to another changelog.

## Dependency Fold Instructions

- Required check for this repo before writing the top release section:
  - Compare `specmaticReporterVersion` in `gradle.properties` at `HEAD` vs the previous release tag.
  - If that version changed, read the shipped `specmatic-reporter` release notes for the intervening version(s) and fold those customer-facing effects into this changelog.
  - Then check which `specmatic-license` and `specmatic-html-reporter` version(s) were newly pulled in by that reporter version, and fold their customer-facing effects too.
  - If any bundled repo explicitly reports no user-facing changes for the shipped version, omit it rather than adding dependency-noise bullets.
- When generating notes for this repo, fold in customer-facing changes from:
  - `specmatic-reporter`, bumped in `specmatic/gradle.properties` via `specmaticReporterVersion`
- Because `specmatic-reporter` itself folds in:
  - `specmatic-license`, bumped in `specmatic-reporter/gradle.properties` via `specmaticLicenseVersion`
  - `specmatic-html-reporter`, copied into `specmatic-reporter/specmatic-reporter/src/main/resources/templates/ctrf-report/`
- When generating notes for downstream repos, this repo is consumed by:
  - `enterprise`, bumped in `enterprise/gradle.properties` via `specmaticVersion`

## Unreleased (2.48.1)

### Added

- Added example preprocessor hooks so loaded examples can be transformed before validation and can attach derived data to the active stub scenario when needed.
- Added support for interpolated substitution expressions, allowing values to be filled in and extracted from substrings.

### Changed

- Fixed a regression in negative test generation so undeclared `4xx` response variants such as `405 Method Not Allowed` and `415 Unsupported Media Type` continue to be exercised instead of being filtered out.
- Improved URL handling across Postman import, proxy routing, remote spec loading, and web-source caching so mixed-case schemes, authorities with underscores, preserved user-info, and explicit ports behave more reliably.
- Improved config-driven target resolution so Specmatic preserves scheme, host, port, path-prefix, and certificate details separately instead of flattening them into a base URL, which keeps more run and mock configurations intact.
- Improved bundled CTRF reporting so coverage execution details now include spec-level coverage metrics and match operations back to the correct spec more reliably across absolute, relative, and normalized paths.
- Improved bundled backward-compatibility HTML reporting so breakages in shared specs are attributed to the shared spec that actually changed, instead of being misreported against a referring spec when only one consumer is affected.

## 2.48.0 (2026-06-18)

### Added

- Added support for running tests and mocks using examples with HTTP status `405 Method Not Allowed` and `415 Unsupported Media Type`, including examples loaded from externalized example files.
- Added MCP tool-provider hooks so enterprise runs can expose their shipped tools through `specmatic mcp`.

### Changed

- Improved validation and diagnostics for `405` and `415` rejection examples so Specmatic now fails more clearly when examples are missing, unreachable, or tied to the wrong request method or media type.
- Improved OpenAPI handling for undeclared request variants by using valid XML placeholders for unsupported media types and by correctly handling schemas wrapped in a single `allOf`.

## 2.47.0 (2026-06-16)

### Changed

- Improved nested object query validation and example diagnostics so failure paths now point to the actual serialized query keys, including bracketed and array-style segments, instead of less accurate synthesized breadcrumbs.
- Improved OpenAPI source-location tracking for referenced schemas so validation failures can retain the original JSON pointer from the source file that defined the schema.
- Improved `specmatic config upgrade` output for legacy configurations by keeping global mock and test settings under top-level `specmatic.settings` instead of moving them into dependency or system-under-test sections.
- Updated bundled reporting and licensing flows so license-aware CLI operations can use `--debug` for trace logging while retaining backward-compatible support for the older `--log-level` flag.
- Improved bundled license CLI output with clearer success, warning, and error cues, including colorized level labels on ANSI-capable terminals.
- Load bad examples where the response is 422
- Make handling of collisions between scalar query param and query param object property names more pragmatic. No complaint if the type is the same, else a warning is printed, and the last declared parameter type is what is honored by Specmatic.
- Added support for nested objects and arrays in a query param object

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
