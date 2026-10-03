# Instructions

## Pull requests
The PR body is this repository's `.github/PULL_REQUEST_TEMPLATE.md` with the blanks filled. Keep the HTML comments and template headings. Do not add extra `##` sections. Append `N/A` to irrelevant checklist items.

## Running gradle commands
- Run gradle tests one at a time. Many tests listen on hard-coded ports. So if tests run in parallel, they will collide on the ports and run into bind errors.
- When running a focused test, be sure to use the fully qualified test class name in the gradle command, e.g. `./gradlew test --tests "com.example.TestClass"`

## Project structure

The following is a list of directories and the gradle modules that they contain:
- directory: 'core', module: 'specmatic-core'
- directory: 'application', module: 'specmatic-executable'
- directory: 'junit5-support', module: 'junit5-support'

Create temp files locally in the "temp" directory as it is ignored by git.

## Transient HTTP stubs
`HttpExpectations.withMatchingStub` must pass the queued `HttpStubData` instance to `utilizeMock`. `matches()` stages matcher utilization state on that object's `stubMatcher`; `utilize()` commits it. One-shot removal is referential (`===`) on the same instance. Fill or copy the HTTP response only for serving (`withServedPartialResponse` / `withResponse`), not as the object given to `utilizeMock`.

## Examples

- Whenever ScenarioStub structure is modified, make sure the necessary changes are propagated to ExampleForFile, which is supposed to be a thin wrapper over ScenarioStub.
- When ScenarioStub structure is modified, update the schema files named `external_example.yaml` and `external_examples.schema.json`.
