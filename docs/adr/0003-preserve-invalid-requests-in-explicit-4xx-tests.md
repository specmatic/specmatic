# Preserve invalid requests in explicit 4xx contract tests

An explicit 4xx contract-test example may deliberately violate the request schema, including supplying an Accept header that excludes the expected response media type. Preserve supplied invalid values through filling, concrete request construction, and default post-interceptor validation so the server's rejection behavior can actually be tested. Continue resolving generation tokens, checking operation identity, and validating the expected response contract; custom validators remain authoritative.

Keep this policy local to contract tests instead of expanding the shared 400/422 invalid-request status list, which would also alter mock registration, serving, and fallback-response selection. Aligning mocks with explicit 4xx contract-test examples is a separate follow-up in the Work in Progress Tracker.

Generated negative tests remain sourced from successful scenarios and may accept a matching declared or default 4xx response. An incompatible Accept value must not skip a negative test derived from an explicit example. Dedicated 406 tests still require explicit examples in the OpenAPI test pipeline.
