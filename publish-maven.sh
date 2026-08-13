#!/usr/bin/env bash
set -euo pipefail

KOTLIN_VERSION="${KOTLIN_VERSION:-2.1.21}"
BASE_VERSION="${BASE_VERSION:-0.1.0}"
export ORG_GRADLE_PROJECT_MAVEN_VERSION="${BASE_VERSION}-${KOTLIN_VERSION}"

if [[ "${1:-local}" == "local" ]]; then
  ./gradlew :KuiklyKLineChart:publishToMavenLocal
else
  ./gradlew :KuiklyKLineChart:publish
fi
