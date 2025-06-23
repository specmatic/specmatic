# Contributing

Thanks for being willing to contribute!

## Prerequisites

* JDK 17 (Please use this version only for the moment)

_Optional:_ Set up [jenv](https://www.jenv.be/) or the equivalent tools to quickly switch between Java versions. 

## Project setup

* Fork and clone the repo
* Run `./gradlew clean build` project root folder to install dependencies
* Create a branch for your PR with `git checkout -b your-branch-name`

> Tip: Keep your `main` branch pointing at the original repository and make
> pull requests from branches on your fork. To do this, run:
>
> ```
> git remote add upstream https://github.com/specmatic/specmatic.git
> git fetch upstream
> git branch --set-upstream-to=upstream/main main
> ```
>
> This will add the original repository as a "remote" called "upstream," Then
> fetch the git information from that remote, then set your local `main`
> branch to use the upstream main branch whenever you run `git pull`. Then you
> can make all of your pull request branches based on this `main` branch.
> Whenever you want to update your version of `main`, do a regular `git pull`.

## Committing and Pushing changes

Please make sure to run the tests before you commit your changes by using the command

```./gradlew clean test```

Generate Fat Jar. The `specmatic.jar` should be available in `<projectRoot>/application/build/libs`

```./gradlew clean build shadowJar```

Run the `specmatic.jar` to verify any commands as part of your changes.

## Docker Image

To build the docker image, run the following command to create fat jar.

```./gradlew clean build```

Create docker image with appropriate tag. Please keep version consistent with the version.properties file.

```docker build --no-cache -t specmatic/specmatic:<version> .```

Docker push is handled by Github workflow `./github/workflows/docker.yml`.

In order to push manually to Docker (should not be necessary, adding this for completeness of documentation), login to docker hub and push the image.
You may need to be added to the specmatic organization in Docker Hub in order to do this.

```docker login -u <username>```

```docker push specmatic/specmatic:<version>```

## Help needed

Please checkout the [the open issues](https://github.com/specmatic/specmatic/issues?q=is%3Aopen+is%3Aissue)

Also, please watch the repo and respond to questions/bug reports/feature
requests! Thanks!

## Analaysing Performance Issues (This need to be revised)

This is an optional section and required for advanced scenarios to debug memory and performance related issues.

1.  Run specmacticJMS.sh. This file runs specmatic with Prometheus Java Agent and publishes metrics on localhost:8089/metrics
2.  To monitor the above metrics in Grafana setup [Perfiz](https://github.com/specmatic/perfiz#detailed-tutorial) (Steps 1 and 2)
3.  Run ```$PERFIZ_HOME/perfiz.sh start``` and navigate to localhost:3000 to view Grafana Dashboard
4.  Run ```$PERFIZ_HOME/perfiz.sh stop``` to stop Grafana and other Docker Containers

