# Conformance Tests

## Running (requires Docker Compose)

By default the tests depend on the docker image produced by the build.

### All tests
```
./gradlew :conformance-tests:check -P enableConformanceTests=true
```

### A subset of tests
This runs the suite `001-http-methods` in `conformance-tests/src/test/resources/specs`

```
./gradlew :conformance-tests:test --tests "io.specmatic.conformance_tests.S001*" -P enableConformanceTests=true
```

### All tests against a specific version of specmatic

```
./gradlew :conformance-tests:check -P enableConformanceTests=true -P specmaticVersionForConformanceTests=2.39.4
```

### Allowing expected failures

In CI, we allow for expected failures by running the tests with `succeedOnExpectedFailures`:

```
./gradlew :conformance-tests:check -P enableConformanceTests=true -P succeedOnExpectedFailures=true 
```

## Conformance test features:
1. Tests Specmatic's conformance with OpenAPI 3.0.3 and 3.1 specifications by validating:
    1. Loop test: Specmatic can load, mock and test the same OpenAPI spec.
    2. All valid Operations(method, path, requestContentType, statusCode) defined in the OpenAPI specification are
       mocked and tested by Specmatic.
    3. No unspecified operations are performed by Specmatic test i.e. it doesn't send any request that isn't specified as
       an operation in the OpenAPI spec.
    4. All requests sent by Specmatic tests are OpenAPI spec conformant.
    5. All responses returned by the Specmatic mock are OpenAPI spec conformant.
2. By default, targets the unreleased snapshot Docker version of Specmatic by depending on `:specmatic-executable:dockerBuild`.
   Can test any Docker version by setting the `specmaticVersionForConformanceTests` Gradle project property.

## Analysis of the latest report as on 2026-03-27

Report downloaded from https://github.com/specmatic/specmatic/pull/2376. This report was built on specmatic sha `a5337c0`.

### Test categories and counts of specs within:

| Spec Category             | Spec Count |
|---------------------------|------------|
| 001-http-methods          | 5          |
| 002-path-parameters       | 10         |
| 003-query-parameters      | 10         |
| 004-header-parameters     | 10         |
| 005-request-response-body | 10         |
| 006-schema-composition    | 12         |
| 007-formats               | 37         |
| 008-constraints           | 24         |
| 009-forms-and-multipart   | 15         |
| 010-status-codes          | 12         |
| 011-refs-and-components   | 8          |
| 012-content-negotiation   | 6          |
| 013-discriminator         | 5          |
| 014-nullable-and-optional | 7          |
| 015-additional-properties | 6          |
| 016-default-values        | 6          |
| 017-security-schemes      | 6          |
| 018-pagination            | 4          |
| 019-examples              | 6          |
| 020-openapi-3-1           | 26         |

See all in [specs](src/test/resources/specs)

### Failures

| Failures                                                     | Valid Failure | Reason                                                                                                                                                                                    |
|--------------------------------------------------------------|---------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 007-formats/003-time                                         | TRUE          | invalid RFC 3339 time                                                                                                                                                                     |
| 007-formats/004-duration                                     | TRUE          | invalid ISO 8601 duration                                                                                                                                                                 |
| 007-formats/006-idn-email                                    | TRUE          | invalid RFC 6531 Mailbox                                                                                                                                                                  |
| 007-formats/008-idn-hostname                                 | TRUE          | invalid RFC 5890 internationalized hostname                                                                                                                                               |
| 007-formats/009-ipv4                                         | TRUE          | invalid RFC 2673 IP address                                                                                                                                                               |
| 007-formats/011-uri                                          | TRUE          | invalid RFC 3986 URI                                                                                                                                                                      |
| 007-formats/010-ipv6                                         | TRUE          | invalid RFC 4291 IP address                                                                                                                                                               |
| 007-formats/014-iri                                          | TRUE          | invalid RFC 3987 IRI                                                                                                                                                                      |
| 007-formats/016-json-pointer                                 | TRUE          | invalid RFC 6901 JSON Pointer                                                                                                                                                             |
| 007-formats/017-relative-json-pointer                        | TRUE          | invalid IETF Relative JSON Pointer                                                                                                                                                        |
| 007-formats/022-binary                                       | TRUE          | loop test failed. Tests didn't send binary content.                                                                                                                                       |
| 008-constraints/009-integer-multipleof                       | TRUE          | invalid request/response body because integer-multipleof constraint not respected                                                                                                         |
| 008-constraints/019-combined-numeric-constraints             | TRUE          | same as above                                                                                                                                                                             |
| 008-constraints/015-object-minproperties                     | TRUE          | loop test failed because tests sent invalid requests that were rejected by the mock                                                                                                       |
| 008-constraints/021-object-minproperties-with-additional     | TRUE          | same as above                                                                                                                                                                             |
| 008-constraints/024-minproperties-three                      | TRUE          | same as above                                                                                                                                                                             |
| 009-forms-and-multipart/004-multipart-file-upload            | TRUE          | same as above                                                                                                                                                                             |
| 009-forms-and-multipart/003-multipart-basic                  | FALSE         | Limitation of conformance test harness. Cannot validate multipart bodies.                                                                                                                 |
| 009-forms-and-multipart/006-multipart-image-upload           | TRUE          | loop test failed because tests sent invalid requests that were rejected by the mock                                                                                                       |
| 009-forms-and-multipart/004-multipart-file-upload            | TRUE          | same as above                                                                                                                                                                             |
| 009-forms-and-multipart/007-multipart-multiple-files         | TRUE          | same as above                                                                                                                                                                             |
| 009-forms-and-multipart/005-multipart-file-with-metadata     | TRUE          | same as above                                                                                                                                                                             |
| 009-forms-and-multipart/008-multipart-binary-octet-stream    | TRUE          | same as above                                                                                                                                                                             |
| 009-forms-and-multipart/009-multipart-with-json-part         | TRUE          | same as above                                                                                                                                                                             |
| 009-forms-and-multipart/011-raw-image-body                   | FALSE         | Limitation of conformance test harness. Cannot validate image/png bodies.                                                                                                                 |
| 009-forms-and-multipart/010-raw-binary-body                  | FALSE         | same as above                                                                                                                                                                             |
| 009-forms-and-multipart/012-multipart-optional-file          | TRUE          | loop test failed because tests sent invalid requests that were rejected by the mock                                                                                                       |
| 009-forms-and-multipart/015-multipart-multiple-named-files   | TRUE          | same as above                                                                                                                                                                             |
| 009-forms-and-multipart/014-multipart-pdf-upload             | TRUE          | same as above                                                                                                                                                                             |
| 012-content-negotiation/002-xml-response                     | TRUE          | Mock fails to load with error: Could not determine name for an xml node                                                                                                                   |
| 012-content-negotiation/003-multiple-response-types          | TRUE          | same as above                                                                                                                                                                             |
| 012-content-negotiation/004-multiple-request-types           | TRUE          | same as above                                                                                                                                                                             |
| 012-content-negotiation/006-different-request-response-types | TRUE          | same as above                                                                                                                                                                             |
| 014-nullable-and-optional/007-nullable-enum                  | TRUE          | tests fail with error: Enum values must contain null if the enum is marked nullable, adding null value                                                                                    |
| 020-openapi-3-1/005-if-then-else                             | TRUE          | invalid request bodies: error=[: required property 'cardNumber' not found, : required property 'bankAccount' not found]                                                                   |
| 020-openapi-3-1/012-discriminator-const-type                 | TRUE          | Loop tests fail with: Expected "circle", actual was "Circle"                                                                                                                              |
| 020-openapi-3-1/015-prefix-items                             | TRUE          | invalid request/responses that don't respect prefixItems type                                                                                                                             |
| 020-openapi-3-1/018-contains                                 | TRUE          | invalid request/response error=[/members: must contain at least 1 element(s) that passes these validations: {"type":"object","required":["role"],"properties":{"role":{"const":"lead"}}}] |


## Limitations
1. Only `JSON`, `YAML` and `application/x-www-form-urlencoded` request bodies can be validated. Unsupported request bodies
   will fail the test. For example in the current test suite we cannot validate `multipart/form-data`, `application/octet-stream` and
   `image/png` request bodies.
2. The conformance tests report in CI are unusable because the output is interleaved and there's no downloadable report.
3. The conformance tests don't run on the main branch because we are yet to identify a suitable strategy to include
   failing tests.
