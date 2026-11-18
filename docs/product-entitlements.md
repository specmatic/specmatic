# Product identity in shared engines

OSS invocation remains Open Source. A commercial distribution supplies Core as its default through `LicensedProductProvider`. License owns grant resolution and the shared usage pool.

Hosts such as Studio use `SpecmaticLicense.withProduct(LicensedProduct.STUDIO) { ... }` when calling embedded engines. This changes attribution without introducing a charge. Independent entry points must preflight their own product before entering that scope; MCP tools do this separately for each invocation.

Coroutines carry `SpecmaticLicense.coroutineContext(product)` in their owned scope. Long-lived mocks and proxies capture the host when created, so future request threads retain it. JUnit runners similarly capture the host before deferred test execution. A synchronous product scope restores the previous identity even when work throws; it does not change a process-wide product.

Existing utilization calls route through `SpecmaticLicense`, which delegates entitlement and budget enforcement to License. Each existing charge point remains responsible for consumption. Help and license-management calls must not acquire a usage charge or require a product entitlement.

The [enterprise adoption plan](../../enterprise/docs/product-entitlements-implementation-plan.md) tracks the current two-repository scope, MCP behavior, and deferred repositories. Local snapshot artifacts must be published before enterprise can consume this change.

Validation: `./gradlew clean test publishToMavenLocal -PenableConformanceTests=false --max-workers=1 --no-parallel` passed 9,541 tests (14 skipped, zero failures). The user agreed to skip the optional conformance corpus. CLI `--help` was smoke-tested from the assembled executable jar on Java 17.
