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

## What

OpenAPI Spec conformance tests that validate the Specmatic can load, test and mock OpenAPI with spec compliant requests and responses.

## Why

This ensures and documents that Specmatic is and remains compliant with the OpenAPI specification.

## Design

- All of Specmatic is the System Under Test and is treated as a block box. Only the public interface (exit code and request/responses) are used to validate Specmatic's behaviour.
- Since these are long running black box tests it's necessary to run them in parallel. We intend to add several hundreds of tests to this suite. The tests depend on the docker image of Specmatic only.
