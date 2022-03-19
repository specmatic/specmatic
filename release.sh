#!/bin/sh

set -e

BRANCH=`git rev-parse --abbrev-ref HEAD`

if [ $BRANCH != "main" ]
then
	echo Current branch is $BRANCH
	echo
	echo You should only release from main
	exit 1
fi

if [ -z "$1" ]
then
	echo Provide the version to be published as the first argument.
	echo Current version is `cat version.properties`
	exit 1
fi

ACTUAL_VERSION=`cat version.properties | sed s/version=//g`

if [ "$1" != $ACTUAL_VERSION ]
then
	echo The specified version $1 does not match the actual version $ACTUAL_VERSION
	exit 1
fi

echo Building and publishing
./gradlew clean build publish

echo Tagging release $1
git tag $1
git push origin $1

echo Release done

