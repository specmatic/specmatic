Specmatic
=========
[![Maven Central](https://img.shields.io/maven-central/v/io.specmatic/specmatic-core.svg)](https://mvnrepository.com/artifact/io.specmatic/specmatic-core) [![GitHub release](https://img.shields.io/github/v/release/specmatic/specmatic.svg)](https://github.com/specmatic/specmatic/releases) ![CI Build](https://github.com/specmatic/specmatic/workflows/Java%20Build%20with%20Gradle/badge.svg) [![Twitter Follow](https://img.shields.io/twitter/follow/specmatic.svg?style=social&label=Follow)](https://twitter.com/specmatic) [![Docker Pulls](https://img.shields.io/docker/pulls/specmatic/specmatic.svg)](https://hub.docker.com/r/specmatic/specmatic)

##### Shift-left API Integration and auto-fix integration drift before integration

Specmatic turns API specifications into executable contracts, continuously verifying that every change made by AI coding agents and engineers stays true to the desired behaviour. Ship AI-ready APIs 10x faster, from intent to implementation, with Spec-Driven API Development.

### Context

In a complex, interdependent ecosystem, where each service is evolving rapidly, esp with AI-coding agents, we want to make the dependencies between them explicit in the form of executable contracts.

Design, build, test and govern high-quality, AI-ready APIs at scale. [Spec-Driven API Development](https://specmatic.io/contract_driven_development.html) drives every stage of the API lifecycle from the same API specifications, turning [OpenAPI/Swagger](https://spec.openapis.org/#openapi-specification), [AsyncAPI](https://www.asyncapi.com/), [GraphQL](https://graphql.org/) SDL files, [gRPC](https://grpc.io/) Proto files, [Arazzo](https://www.openapis.org/arazzo-specification) and WSDL into executable contracts. Scribe is your AI-native API designer, taking you from idea to AI-ready API specifications. Skeptic is your API linter, validating your API specifications and examples. Studio is your visual workspace for spec-driven contract testing and mocking, while Core brings the same capabilities to developer workflows and CI/CD through the command line. Insights is your continuous governance control plane for optimising your API ecosystem. Genie is your agent harness, enabling AI coding agents to work across the entire API development lifecycle.

Learn more at [specmatic.io](https://specmatic.io/#features) 🌐

[Get started now](https://docs.specmatic.io/getting_started.html) 🚀

[![Specmatic - Contract Driven Development - YouTube playlist](https://img.youtube.com/vi/K5BYxoONgXo/0.jpg)](https://www.youtube.com/watch?v=0FpHsCkimto&list=PL9Z-JgiTsOYRERcsy9o3y6nsi5yK3IB_w&index=1)

[YouTube playlist](https://www.youtube.com/watch?v=0FpHsCkimto&list=PL9Z-JgiTsOYRERcsy9o3y6nsi5yK3IB_w&index=1) 📺

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
