#!/usr/bin/env bash
# Publish KuiklyKLineChart Maven artifacts (KMP and/or Android AAR).
# iOS uses CocoaPods (KuiklyKLineChartIOS); OHOS KMP uses settings.ohos.gradle.kts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

TARGET="${1:-local}"          # local | github | remote
SCOPE="${2:-all}"             # all | kmp | android | ohos-kmp
KOTLIN_VERSION="${KOTLIN_VERSION:-2.1.21}"
BASE_VERSION="${BASE_VERSION:-0.1.0}"
OHOS_KOTLIN_VERSION="${OHOS_KOTLIN_VERSION:-2.0.21-KBA-010}"
GITHUB_MAVEN_OWNER="${GITHUB_MAVEN_OWNER:-qingfeng19491001}"
GITHUB_MAVEN_REPO="${GITHUB_MAVEN_REPO:-KuiklyKLineChart}"
GITHUB_GROUP_ID="${GITHUB_GROUP_ID:-io.github.${GITHUB_MAVEN_OWNER}}"

fill_github_credentials() {
  if [[ -n "${MAVEN_USERNAME:-}" && -n "${MAVEN_PASSWORD:-}" ]]; then
    return
  fi
  if [[ -n "${GITHUB_TOKEN:-}" || -n "${GH_TOKEN:-}" ]]; then
    export MAVEN_USERNAME="${MAVEN_USERNAME:-${GITHUB_ACTOR:-${GITHUB_MAVEN_OWNER}}}"
    export MAVEN_PASSWORD="${MAVEN_PASSWORD:-${GITHUB_TOKEN:-${GH_TOKEN}}}"
    return
  fi
  local cred
  cred="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill)"
  export MAVEN_USERNAME="${MAVEN_USERNAME:-$(printf '%s\n' "$cred" | awk -F= '/^username=/{print substr($0,10); exit}')}"
  export MAVEN_PASSWORD="${MAVEN_PASSWORD:-$(printf '%s\n' "$cred" | awk -F= '/^password=/{print substr($0,10); exit}')}"
}

export ORG_GRADLE_PROJECT_MAVEN_VERSION="${BASE_VERSION}-${KOTLIN_VERSION}"
export ORG_GRADLE_PROJECT_GROUP_ID="${GROUP_ID:-com.tencent.kuiklybase}"

PUBLISH_TASK="publishToMavenLocal"
if [[ "$TARGET" == "github" ]]; then
  fill_github_credentials
  if [[ -z "${MAVEN_USERNAME:-}" || -z "${MAVEN_PASSWORD:-}" ]]; then
    echo "GitHub Packages needs MAVEN_USERNAME/MAVEN_PASSWORD, GITHUB_TOKEN, or a git credential for github.com" >&2
    exit 1
  fi
  export GROUP_ID="${GITHUB_GROUP_ID}"
  export ORG_GRADLE_PROJECT_GROUP_ID="${GITHUB_GROUP_ID}"
  export MAVEN_REPO_URL="https://maven.pkg.github.com/${GITHUB_MAVEN_OWNER}/${GITHUB_MAVEN_REPO}"
  export ORG_GRADLE_PROJECT_MAVEN_REPO_URL="${MAVEN_REPO_URL}"
  export ORG_GRADLE_PROJECT_MAVEN_USERNAME="${MAVEN_USERNAME}"
  export ORG_GRADLE_PROJECT_MAVEN_PASSWORD="${MAVEN_PASSWORD}"
  PUBLISH_TASK="publish"
elif [[ "$TARGET" == "remote" ]]; then
  PUBLISH_TASK="publish"
fi

echo "==> publish target=$TARGET scope=$SCOPE group=${ORG_GRADLE_PROJECT_GROUP_ID} version=${ORG_GRADLE_PROJECT_MAVEN_VERSION}"

publish_kmp() {
  echo "-- :KuiklyKLineChart ($PUBLISH_TASK)"
  ./gradlew ":KuiklyKLineChart:${PUBLISH_TASK}" --stacktrace
}

publish_android() {
  echo "-- :KuiklyKLineChartAndroid ($PUBLISH_TASK)"
  ./gradlew ":KuiklyKLineChartAndroid:${PUBLISH_TASK}" --stacktrace
}

publish_ohos_kmp() {
  local ohos_version="${BASE_VERSION}-${OHOS_KOTLIN_VERSION}"
  echo "-- :KuiklyKLineChart OHOS KMP ($PUBLISH_TASK) version=$ohos_version"
  ./gradlew -c settings.ohos.gradle.kts \
    -PmavenVersion="$ohos_version" \
    ":KuiklyKLineChart:${PUBLISH_TASK}" --stacktrace
}

case "$SCOPE" in
  kmp) publish_kmp ;;
  android) publish_android ;;
  ohos-kmp) publish_ohos_kmp ;;
  all)
    publish_kmp
    publish_android
    echo "Note: iOS → CocoaPods KuiklyKLineChartIOS (git tag); OHOS native → this repo's KuiklyKLineChartOhos until ohpm"
    echo "Optional OHOS KMP Maven: $0 $TARGET ohos-kmp"
    ;;
  *)
    echo "Usage: $0 [local|github|remote] [all|kmp|android|ohos-kmp]" >&2
    exit 1
    ;;
esac

echo "==> done"
