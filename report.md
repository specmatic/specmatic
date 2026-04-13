# 📄 Specmatic Coverage & Reporting Model

## Overview

This document defines the coverage reporting model for Specmatic across Console and Studio.

The goal is to provide a clear, spec-centric view of API contract coverage, while also surfacing runtime deviations and execution evidence.

---

## 🧠 Core Principles

1. Spec-centric reporting
   - Coverage is computed only from behaviours declared in the specification.
2. Separation of concerns
   - Spec-declared behaviours vs runtime-only findings must be distinct but capture in the same report.
3. Truthful coverage
   - Coverage reflects what was actually observed, not what was attempted.
4. WIP affects suite result, not coverage
   - WIP tests do not fail the suite, but still contribute to coverage.
5. Execution evidence is first-class
   - Attempts and Matches must be tracked and displayed.

---

## 🧩 Data Model

### 1. Reporting Row

Each row represents a single reporting unit for an observed or expected contract behaviour:

`(Path, Method, Request Content-Type, Response Status, Response Content-Type)`

---

### 2. Fields per Row

#### 2.1 Status (Required)

Enum:

- Covered
- Not Implemented
- Not Tested (test mode only)
- Not Used (mock mode only)
- Missing in Spec
- Undeclared Response

---

#### 2.2 Decisions (Optional)

Applicable when Status = Not Tested

Enum:

- Excluded
- No Examples
- Generation Disabled
- Unsupported by Specmatic

---

#### 2.3 Qualifiers (Optional, Multiple Allowed)

- WIP
- Filtered

Qualifiers are visual only and do not affect semantic status.

---

#### 2.4 Coverage Eligibility (Required)

Boolean:

- true (default)
- false

---

#### 2.5 Execution Metrics (Required)

- Attempts (integer ≥ 0)
- Matches (integer ≥ 0) - all 5 elements in an operation matched

---

## ⚙️ Status Determination Rules

### Covered

IF Attempts > 0 AND Matches > 0
→ Status = Covered
→ Coverage Eligibility = true

---

### Not Implemented

IF Attempts ≥ 1 AND Matches = 0 AND the expected response (status and content type) is declared in the spec
→ Status = Not Implemented
→ Coverage Eligibility = true

---

### Not Tested

IF Attempts = 0
→ Status = Not Tested
→ Decisions MUST be provided
→ Coverage Eligibility = true unless Decisions = Excluded or Unsupported by Specmatic

---

### Not Used (Mock Mode Only)

IF operation exists but was never invoked during mock execution
→ Status = Not Used
→ Coverage Eligibility = true

---

### Missing in Spec

IF service exposes (Path, Method) not present in spec
→ Report as "Missing in Spec"

→ Coverage Eligibility = false

Also applies when Specmatic generates a negative test expectation for a response that is not declared in the spec.
In such cases, the generated expectation should be reported as Missing in Spec so the developer can first add it to the spec.
This classification takes precedence over Not Implemented.

---

### Undeclared Response

IF service exposes (Path, Method) which is present in spec, but service returns response not defined in spec
→ Report as "Undeclared Response"

Includes:

- Unexpected status codes
- Unexpected content types

→ Coverage Eligibility = false

Also applies when
IF Attempts = 0 AND Matches > 0 AND the response status code is absent from the spec
→ Status = Undeclared Response
→ Coverage Eligibility = false

---

Important

- Missing in Spec and Undeclared Response are part of the main status model
- They are shown along with other rows
- Must NOT influence coverage numerator or denominator

---

## 🎯 Coverage Eligibility Rules

### Included in Coverage

A row is Included if:

- It is not excluded
- It is not unsupported by Specmatic

This includes:

- Covered
- Not Implemented
- Not Tested (unless excluded or unsupported by Specmatic)
- Not Used (mock mode only)

---

### Excluded from Coverage

A row is Excluded if:

- Decisions = Excluded
- It cannot be tested due to:
  - Unsupported by Specmatic
- It is Missing in Spec
- It is an Undeclared Response

---

## 📊 Coverage Calculation

Coverage % = (Number of Covered rows) / (Number of Included rows) × 100

Where:

- Numerator = count of rows where Status = Covered AND Coverage Eligibility = true
- Denominator = count of rows where Coverage Eligibility = true

---

## 🧪 Execution Metrics Semantics

### Attempts

Number of times Specmatic sent a request to validate this expected behaviour.

Includes:

- Generated tests
- Variations (property-based / generative cases)

---

### Matches

Number of times the actual operation matched the expected contract behavior (Path, Method, Request Content-Type, Response Status, Response Content-Type).

---

### Example

| Attempts | Matches | Status          |
|----------|---------|-----------------|
| 10       | 10      | Covered         |
| 10       | 1       | Covered         |
| 10       | 0       | Not Implemented |
| 1        | 0       | Not Implemented |
| 0        | 0       | Not Tested      |
| 20       | 20      | Missing in Spec |

---

## ⚠️ WIP Behaviour

### Rules

- WIP is a qualifier only
- It does NOT change status
- It does NOT affect coverage inclusion

### Suite Execution

IF Status = Not Implemented AND Qualifier = WIP
→ Do NOT fail the test suite

---

## 🧩 Special Scenarios

### Scenario 1: Expected 201, got only 400 (which is missing in spec)

Row: 201

Attempts = 10
Matches = 0
Status = Not Implemented
Coverage = Included

Row: 400

Attempts = 0
Matches = 10
Status = Undeclared Response
Coverage = Excluded

Notes:

- The 201 row is still part of the main spec coverage model, so it remains Not Implemented.
- The observed 400 row is not declared in spec, so it is reported separately and excluded from coverage.

---

### Scenario 2: Expected 201, got only 400 (which is present in spec)

Row: 201

Attempts = 10
Matches = 0
Status = Not Implemented
Coverage = Included

Row: 400

Attempts = 10
Matches = 10
Status = Covered (since 400 is declared in spec)
Coverage = Included

This is true even when the 201 row is Not Implemented. Different response rows for the same `(Path, Method, Request Content-Type)` are evaluated independently.

---

### Scenario 3: WIP endpoint is attempted but does not match

Row: 200 / application/json

Attempts = 1
Matches = 0
Status = Not Implemented
Qualifier = WIP
Coverage = Included

---

### Scenario 4: Filtered operation

Status = Not Tested
Decisions = Excluded
Qualifier = Filtered
Coverage = Excluded
Attempts = 0
Matches = 0

---

### Scenario 5: Missing in Spec row discovered via application endpoint

/orders/{id} GET

Attempts = 0
Matches = 0
Status = Missing in Spec
Coverage = Excluded

This row is allowed even with zero attempts and zero matches when it comes from the application endpoint source.

---

### Scenario 6: Not Tested with decision

Row: 201 / application/json

Attempts = 0
Matches = 0
Status = Not Tested
Decisions = Excluded
Coverage = Excluded

---

### Scenario 7: Missing-in-spec response generated by negative tests

Declared row:

/order POST application/json -> 201 / application/json

Attempts = 10
Matches = 10
Status = Covered
Coverage = Included

Generated negative row:

/order POST application/json -> 400 / application/json

Attempts = 20
Matches = 20
Status = Missing in Spec
Coverage = Excluded

If only some of those generated runs match, for example:

- Attempts = 20
- Matches = 10

the status still remains Missing in Spec. Match count does not override the missing-in-spec classification.

---

### Scenario 8: Declared negative response with generative tests

/order POST application/json -> 400 / application/json

Attempts = 20
Matches = 10
Status = Covered
Coverage = Included

As long as the row is declared in spec and Matches > 0, the row is Covered.

---

### Scenario 9: Response content type matters

Rows are distinct per:

`(Path, Method, Request Content-Type, Response Status, Response Content-Type)`

So these are different rows:

- `400 / application/json`
- `400 / text/plain`
- `422 / application/json`
- `422 / text/plain`
- `500 / application/json`

Implications:

- If `400 / text/plain` is declared and matched, it is Covered.
- `422` and `500` variants must each be evaluated independently.

---

### Scenario 10: Negative test returns undeclared content type, but is reported against best matching declared row

Assume the spec declares these rows for the same operation:

- `201 / application/json`
- `400 / application/json`
- `400 / text/plain`
- `422 / application/json`
- `422 / text/plain`

Now a negative test returns:

- actual response = `400 / application/xml`

In this case:

- `400 / application/xml` does **not** automatically become a new reporting row
- the failed test is attached to the best matching declared row in the spec
- that chosen row gets:
  - `Attempts += 1`
  - `Matches += 0`
- the final status is determined on that declared row using the normal rules

The exact best-match algorithm is out of scope here, but the reporting rule is:

> When an actual response does not exactly match a declared response row, Specmatic may associate that failure to the closest declared row instead of creating a new operation row.

This keeps the report spec-centric while still preserving the failure evidence.

---

### Scenario 11: No examples

Status = Not Tested
Decisions = No Examples
Coverage = Included
Attempts = 0
Matches = 0

---

### Scenario 12: Mock usage

Status = Not Used
Coverage = Included.

---

### Scenario 13: Negative test expects 400, but 400 is not declared in spec

Specmatic generates a negative test expecting 400.
The spec does not declare 400 for this operation.
The service returns 405.

Row: 400

Attempts = 1
Matches = 0
Status = Missing in Spec
Coverage = Excluded

Row: 405

Attempts = 0
Matches = 1
Status = Undeclared Response
Coverage = Excluded

Explanation:

- The expected 400 is reported as Missing in Spec because the response itself is absent from the contract and should be added to the spec first.
- The observed 405 is reported as Undeclared Response if it is also not declared in the spec.

---

## 🎨 Display Guidelines (UI/CLI)

Each row should display:

- Path + Method
- Request Content-Type
- Expected Response Status Code
- Expected Response Content-Type
- Status
- Remarks
  - Qualifiers (WIP, Filtered)
  - Decisions (if Not Tested)
  - Attempts
  - Matches
  - Coverage Indicator (Included / Excluded)

---

## Coverage Summary

Coverage: 56% (7 / 12 behaviours covered)

Excluded:

- 3 Excluded
- 2 Missing in Spec

---

## 🧱 Implementation Notes

- Always prioritise spec-declared behaviour as source of truth
- Ensure deterministic computation of status from Attempts/Matches

---

## 🚀 Final Statement

Specmatic coverage represents how completely the declared API contract has been observed in execution, supported by measurable evidence (Attempts and Matches), while clearly calling out runtime deviations from contract truth.
