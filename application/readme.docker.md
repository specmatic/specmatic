Specmatic
=========
[![Maven Central](https://img.shields.io/maven-central/v/io.specmatic/specmatic-core.svg)](https://mvnrepository.com/artifact/io.specmatic/specmatic-core) [![GitHub release](https://img.shields.io/github/v/release/specmatic/specmatic.svg)](https://github.com/specmatic/specmatic/releases) ![CI Build](https://github.com/specmatic/specmatic/workflows/CI%20Build/badge.svg) [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=specmatic_specmatic&branch=main&metric=alert_status)](https://sonarcloud.io/dashboard?id=specmatic_specmatic&branch=main) [![Twitter Follow](https://img.shields.io/twitter/follow/specmatic.svg?style=social&label=Follow)](https://twitter.com/specmatic) [![Docker Pulls](https://img.shields.io/docker/pulls/specmatic/specmatic.svg)](https://hub.docker.com/r/specmatic/specmatic)

##### Transform your API Specifications into Executable Contracts with #NoCode in Seconds
Experience the power of Contract-Driven Development to confidently develop and independently deploy your Microservices and Microfrontends faster.

### Quick Start Commands

#### 1. Running Specmatic Contract Tests
```shell
docker run -v "$(pwd)/openapi.yaml:/openapi.yaml" specmatic/specmatic test "/openapi.yaml" --testBaseURL=http://localhost:8080
```
Will use the OpenAPI file `openapi.yaml` to run contract tests against the service running at `http://localhost:8080`.

#### 2. Running Specmatic Service Virtualization
```shell
docker run -v "$(pwd)/openapi.yaml:/openapi.yaml" -p "9000:9000"  specmatic/specmatic virtualize "/openapi.yaml"
```
Will use the OpenAPI file `openapi.yaml` to start a stub server on http://localhost:9000 and will respond to API requests as per the examples in the OpenAPI file.

#### 3. Running Specmatic Backward Compatibility Tests
```shell
docker run -v "$(pwd):/repo:rw" --user $(id -u):$(id -g) specmatic/specmatic backward-compatibility-check --repo-dir=/repo --base-branch=origin/main
```
Will use the OpenAPI files in the current directory to run backward compatibility tests against the spec files which are already on the main branch.

#### 4. Running Specmatic Example Linter/Validation
```shell
docker run -v "./openapi.yaml:/openapi.yaml" specmatic/specmatic examples validate --spec-file="/openapi.yaml" --examples-to-validate=INLINE
```
Will use the OpenAPI file `openapi.yaml` to validate the examples in the OpenAPI file.

#### 5. See full list of commands
```shell
docker run specmatic/specmatic --help
```

Please refer to [Specmatic documentation](https://specmatic.in/documentation/) for more details.

## Sample project to see full usage

[Order BFF Application](https://github.com/specmatic/specmatic-order-bff-java?tab=readme-ov-file#break-down-each-component-to-understand-what-is-happening)

### Context

In a complex, interdependent ecosystem, where each service is evolving rapidly, we want to make the dependencies between them explicit in the form of executable contracts. [Contract Driven Development](https://specmatic.io/contract_driven_development.html) leverages API specifications like [OpenAPI](https://spec.openapis.org/#openapi-specification), [AsyncAPI](https://www.asyncapi.com/), [GraphQL](https://graphql.org/) SDL files, [gRPC](https://grpc.io/) Proto files, etc. as executable contracts allowing teams to get instantaneous feedback while making changes to avoid accidental breakage.

With this ability, we can now independently deploy, at will, any service at any time without having to depend on expensive and fragile integration tests.

Learn more at [specmatic.io](https://specmatic.io/#features) 🌐

[Get started now](https://specmatic.io/getting_started.html) 🚀

[![Specmatic - Contract Driven Development - YouTube playlist](https://img.youtube.com/vi/Bp0wEHffQmA/0.jpg)](https://www.youtube.com/watch?v=Bp0wEHffQmA&list=PL9Z-JgiTsOYRERcsy9o3y6nsi5yK3IB_w&index=1)

[YouTube playlist](https://www.youtube.com/watch?v=3HPgpvd8MGg&list=PL9Z-JgiTsOYRERcsy9o3y6nsi5yK3IB_w) 📺