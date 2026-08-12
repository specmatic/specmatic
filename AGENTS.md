# Instructions

## Running gradle commands
- Run gradle tests one at a time. Many tests listen on hard-coded ports. So if tests run in parallel, they will collide on the ports and run into bind errors.
- When running a focused test, be sure to use the fully qualified test class name in the gradle command, e.g. `./gradlew test --tests "com.example.TestClass"`

## Project structure

The following is a list of directories and the gradle modules that they contain:
- directory: 'core', module: 'specmatic-core'
- directory: 'application', module: 'specmatic-executable'
- directory: 'junit5-support', module: 'junit5-support'

Create temp files locally in the "temp" directory as it is ignored by git.

## Examples

- Whenever ScenarioStub structure is modified, make sure the necessary changes are propagated to ExampleForFile, which is supposed to be a thin wrapper over ScenarioStub.
- When ScenarioStub structure is modified, update the schema files named `external_example.yaml` and `external_examples.schema.json`.
