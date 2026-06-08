Specmatic
=========
[![Maven Central](https://img.shields.io/maven-central/v/io.specmatic/specmatic-core.svg)](https://mvnrepository.com/artifact/io.specmatic/specmatic-core) [![GitHub release](https://img.shields.io/github/v/release/specmatic/specmatic.svg)](https://github.com/specmatic/specmatic/releases) ![CI Build](https://github.com/specmatic/specmatic/workflows/CI%20Build/badge.svg) [![Twitter Follow](https://img.shields.io/twitter/follow/specmatic.svg?style=social&label=Follow)](https://twitter.com/specmatic) [![Docker Pulls](https://img.shields.io/docker/pulls/specmatic/specmatic.svg)](https://hub.docker.com/r/specmatic/specmatic)

##### Ship AI-Ready APIs 10x Faster with Zero Integration Headaches
Eliminate API integration headaches with Specmatic's no-code AI-powered API development suite. Teams ship APIs 10x faster by transforming specifications into executable contracts instantly—no coding required, no integration surprises.

### Context

In a complex, interdependent ecosystem, where each service is evolving rapidly, we want to make the dependencies between them explicit in the form of executable contracts. [Contract Driven Development](https://specmatic.io/contract_driven_development.html) leverages API specifications like [OpenAPI](https://spec.openapis.org/#openapi-specification), [AsyncAPI](https://www.asyncapi.com/), [GraphQL](https://graphql.org/) SDL files, [gRPC](https://grpc.io/) Proto files, etc. as executable contracts allowing teams to get instantaneous feedback while making changes to avoid accidental breakage.

With this ability, we can now independently deploy, at will, any service at any time without having to depend on expensive and fragile integration tests.

Learn more at [specmatic.io](https://specmatic.io/#features) 🌐

[Get started now](https://specmatic.io/getting_started.html) 🚀

[![Specmatic - Contract Driven Development - YouTube playlist](https://img.youtube.com/vi/K5BYxoONgXo/0.jpg)](https://www.youtube.com/watch?v=K5BYxoONgXo&list=PL9Z-JgiTsOYRERcsy9o3y6nsi5yK3IB_w)

[YouTube playlist](https://www.youtube.com/watch?v=K5BYxoONgXo&list=PL9Z-JgiTsOYRERcsy9o3y6nsi5yK3IB_w) 📺

## Specmatic Tests Orchestrator

The existing **Java Build with Gradle** workflow triggers [`specmatic/specmatic-tests-orchestrator`](https://github.com/specmatic/specmatic-tests-orchestrator) after the snapshot publish step on `main`. It can also be run manually from the same workflow.

The caller-owned manifest lives at [`.github/test-executor.json`](./.github/test-executor.json). `gradle.yml` reads that file and embeds its JSON content as `orchestrator_options.test_executor_json`, so the orchestrator does not need to know this repository's file layout.

How it runs:

1. `gradle.yml` builds Specmatic on the configured OS matrix.
2. On `main`, after `Publish Snapshot` completes on Ubuntu, it creates OS-scoped commit statuses and dispatches the orchestrator once per configured runner label.
3. For a manual run, open **Actions -> Java Build with Gradle**, click **Run workflow**, and provide `ENTERPRISE_VERSION` if you want something other than the default `SNAPSHOT` selector.
4. Leave `TEST_EXECUTOR_JSON_PATH` as `.github/test-executor.json` unless you want to embed another manifest from this repository.
5. The orchestrator always dispatches the target repository workflows from the embedded manifest in parallel.

The workflow creates OS-scoped commit statuses such as `Ubuntu - Specmatic Orchestrator Gate` and `Windows - Specmatic Orchestrator Gate`. While the orchestrator is running, those statuses remain pending. After the callback completes, use the repository commit status popover or the workflow summary to open each gate's **Details** link, which points to the exact `specmatic-tests-orchestrator` run. The orchestrator run uploads `outputs/orchestration-summary.json` and `outputs/index.html` as artifacts.
