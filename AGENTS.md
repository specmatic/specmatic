# Instructions

## Running gradle commands
- Run gradle tests one at a time. Many tests listen on hard-coded ports. So if tests run in parallel, they will collide on the ports and run into bind errors.
- When running a focused test, be sure to use the fully qualified test class name in the gradle command, e.g. `./gradlew test --tests "com.example.TestClass"`

## Project structure

The following is a list of directories and the gradle modules that they contain:
- directory: 'core', module: 'specmatic-core'
- directory: 'application', module: 'specmatic-executable'
- directory: 'junit5-support', module: 'junit5-support'
