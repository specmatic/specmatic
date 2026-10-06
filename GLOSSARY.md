# Contract testing

Language for selecting contract tests and schema resiliency tests.

## Language

**Expected response status**:
The response status a contract test expects, as defined by the specification or by the generated resiliency case.

**Status filter**:
A condition selecting tests by their expected response status. An exact code selects explicit response tests for that code and excludes generated negative tests; `4xx` selects explicit 4xx response tests and generated negative tests.

**Schema resiliency test**:
A generated test that varies a request from a success-response scenario to exercise valid or invalid inputs against a contract.

**Explicit error-response test**:
A test backed by an example of an error response in the specification, run as defined without generating further resiliency tests from it. A declared 429 response schema alone does not supply an explicit 429 test.

**Explicit 429 test**:
A test of a spec-defined 429 response, run as defined without generating positive or negative resiliency tests from it.
