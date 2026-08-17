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

## Unreleased

### Added
- Added support for column level filters in the HTML report


## 2.53.0 (2026-08-14)
### Added

- Added support for loading OpenAPI `multipart/form-data` request examples, including multipart content and external file references for mock.

### Changed

- Fixed loading of OpenAPI examples referenced through internal or external references, with consistent handling of scalar, structured, array, null, and Specmatic values.
- Fixed OpenAPI `multipart/form-data` and `application/x-www-form-urlencoded` examples so their requests use multipart parts or form fields instead of a generic request body, allowing inline and external examples to match request patterns during test generation and mocking.
- Fixed multipart mock request parsing to preserve each part's `Content-Encoding`, so multipart examples declaring an encoding can match the incoming request.
- Fixed request examples for operations with an empty response body so path parameter examples are retained when a request body is present.
- Fixed backward-compatibility change tracking for path parameters defined through `$ref`, preventing unchanged OpenAPI operations from being reported as changed.
- Backward-compatibility checks now report newly added specifications as compatible when no corresponding specification exists on the base branch, and explain the verdict using the base branch name.
- Synchronized the bundled multipart-form-data example schemas with the supported example structure.

## 2.52.0 (2026-08-10)

### Added

- Added support for OpenAPI `openIdConnect` security schemes as bearer-token authentication.

### Changed

- Contract tests now retry documented and undocumented `429 Too Many Requests` responses. Retry delays honor `Retry-After` values expressed as seconds or HTTP dates while retaining compatibility with ISO-8601 timestamps, and every request and response attempt remains available to reporting integrations such as Studio.
- Improved multipart handling across contract tests and mocks. Specification-derived multipart parts no longer assume a filename, example-supplied filenames remain enforceable, and each part's declared content type and encoding are validated consistently.
- Nullable OpenAPI JSON response bodies now accept the literal JSON value `null`, while `null` in scalar parameters and non-JSON payloads remains a string value.
- Strict-mode OpenAPI loading now supports response examples for operations that have no request parameters or request body in the spec. Specmatic uses the first named example from the first `2xx` response and reports clearer warnings for examples it cannot select.
- Coverage reports now retain the OpenAPI `default` response designation when it matches an undeclared response status, so the owning operation is reported as covered.
- Updated the MCP backward compatibility tool to handle Docker-based executions. When required files are unavailable inside the container, the tool returns a complete Docker backward compatibility command to run.
- The bundled CTRF report API now requires `timestamp` and `generatedBy` when constructing reports and exposes `reportId` as a UUID.

## 2.51.1 (2026-07-31)

### Changed

- Fixed OpenAPI `allOf` conversion so a main-schema property overrides a matching member property without generating both optional and required keys.
- Fixed multi-spec stubs to route requests to the most specific matching base-URL path.

## 2.51.0 (2026-07-25)

## Added

- Added load-time validation for `before` and `after` fixtures in external examples, with fuzzy matching for clearer errors.

### Changed

- Fixed: Multipart schema being used to generate filename
- Improved backward compatibility check logs for changed externalised examples.
- Fixed the backward compatibility check file count to exclude externalised examples that are not checked directly.

## 2.50.1 (2026-07-17)

### Added

- Added per-spec run options for Swagger/OpenAPI, actuator, and Swagger UI.

### Changed

- Ability to mock exact values of security parameters using the example requests
- Added MCP mock fixes for spec-file path resolution and gRPC/GraphQL `specmatic.yaml` serialization.

## 2.50.0 (2026-07-06)

### Added

- Added support the following WSDL features
  - substitution groups
  - polymorphic type extension matching
  - abstract types and elements
- WSDL parser performance was improved. Loading of complicated WSDLs should now be much snappier
- Fixes to handle SOAPAction header case insensitively in mock, and fixes to other edge cases

## 2.49.1 (2026-06-30)

### Changed

- Response assertions in contract tests should not run for negative tests

## 2.49.0 (2026-06-29)

### Added

- Added example preprocessor hooks so loaded examples can be transformed before validation and can attach derived data to the active stub scenario when needed.
- Added support for interpolated substitution expressions, allowing values to be filled in and extracted from substrings.
- WSDL parser optimisations
- Improvements to xsi:type handling (WSDL/SOAP)

### Changed

- Fixed a regression in negative test generation so undeclared `4xx` response variants such as `405 Method Not Allowed` and `415 Unsupported Media Type` continue to be exercised instead of being filtered out.
- Improved URL handling across Postman import, proxy routing, remote spec loading, and web-source caching so mixed-case schemes, authorities with underscores, preserved user-info, and explicit ports behave more reliably.
- Improved config-driven target resolution so Specmatic preserves scheme, host, port, path-prefix, and certificate details separately instead of flattening them into a base URL, which keeps more run and mock configurations intact.
- Improved bundled CTRF reporting so coverage execution details now include spec-level coverage metrics and match operations back to the correct spec more reliably across absolute, relative, and normalized paths.
- Improved bundled backward-compatibility HTML reporting so breakages in shared specs are attributed to the shared spec that actually changed, instead of being misreported against a referring spec when only one consumer is affected.
- Substitutions to be lenient by default, i.e. missing variables now use auto-generated values, while enabling `strictMode` restores the previous behavior of failing instead of generating.
- Substitutions stored values and data lookups can reuse composite JSON objects and arrays, and unresolved substitutions now fall back to dictionary-backed generation when available.

## 2.48.0 (2026-06-18)

### Added

- Added support for running tests and mocks using examples with HTTP status `405 Method Not Allowed` and `415 Unsupported Media Type`, including examples loaded from externalized example files.
- Added MCP tool-provider hooks so enterprise runs can expose their shipped tools through `specmatic mcp`.

### Changed

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
