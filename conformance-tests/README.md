# Conformance Tests

## Running

```
 ./gradlew :conformance-tests:check -PenableConformanceTests=true
```

Requires `Docker Compose`

```
echo 'enableConformanceTests=true' >> ~/.gradle/gradle.properties
```

## What

Introduce OpenAPI Spec conformance tests. In part 1 we validate and document that Specmatic supports 001-http-methods and 002-path-parameters features from the OpenAPI specification (version 3.0.x) by running a loop test. For example a loop test of the specification 002-path-parameters/007-two-params-with-separator.yaml starts a Specmatic mock for this specification and then points a Specmatic test at it. A successful test validates that Specmatic can load, parse, mock and test such a specification.

## Why

This ensures and documents that Specmatic is and remains compliant with the OpenAPI specification.

## Design

- All of Specmatic is the System Under Test and is treated as a block box. Only the public interface (exit code and request/responses) are used to validate Specmatic's behaviour.
- Since these are long running black box tests it's necessary to run them in parallel. We intend to add several hundreds of tests to this suite in follow up PRs. The tests depend on the docker image of Specmatic only.
- The tests live in an independent gradle project. To run the tests please run ./gradlew :conformance-tests:check -PenableConformanceTests=true. These tests will be wired up to the main build in subsequent PRs.

## Follow up features

- Wire up in the main build and documentation.
- Detailed request/response validation.
- Header and Content-Type validations.
- More features of OpenAPI will be exercised.
