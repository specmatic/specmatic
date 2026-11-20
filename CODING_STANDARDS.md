# Coding standards

## User-facing diagnostics

When writing or reviewing an error message, ask: could someone understand what went wrong and
correct their input from this message?

- Explain the rule and why the input breaks it. Say what each number means:
  “maxProperties 1 allows at most 1 property, but the schema requires 2 properties (id, name),
  which must always be present” is more useful than “maxProperties 1 cannot be less than 2”.
- Point to the schema field or value that caused the error.
- Use plain language and correct grammar, such as “1 property” and “2 properties”. Leave out internal
  code terms. Describe the issue in language that helps the user understand it clearly.
- Test that the message explains the problem, points to the right location, and accompanies the
  expected success or failure. Check the whole message only when its exact wording matters;
  otherwise check the important facts so harmless formatting changes do not break tests.
