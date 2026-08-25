#!/usr/bin/env bash
# Publish KuiklyKLineChart Maven artifacts (KMP and/or Android AAR).
# iOS uses CocoaPods (KuiklyKLineChartIOS); OHOS KMP uses settings.ohos.gradle.kts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

TARGET="${1:-local}"          # local | remote
SCOPE="${2:-all}"             # all | kmp | android | ohos-kmp
KOTLIN_VERSION="${KOTLIN_VERSION:-2.1.21}"
BASE_VERSION="${BASE_VERSION:-0.1.0}"
OHOS_KOTLIN_VERSION="${OHOS_KOTLIN_VERSION:-2.0.21-KBA-010}"

export ORG_GRADLE_PROJECT_MAVEN_VERSION="${BASE_VERSION}-${KOTLIN_VERSION}"
export ORG_GRADLE_PROJECT_GROUP_ID="${GROUP_ID:-com.tencent.kuiklybase}"

PUBLISH_TASK="publishToMavenLocal"
if [[ "$TARGET" == "remote" ]]; then
  PUBLISH_TASK="publish"
fi

echo "==> publish target=$TARGET scope=$SCOPE version=${ORG_GRADLE_PROJECT_MAVEN_VERSION}"

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
    echo "Note: iOS → CocoaPods KuiklyKLineChartIOS; OHOS native → ohpm @kuiklybase/kuikly-kline-chart-ohos"
    echo "Optional OHOS KMP Maven: $0 $TARGET ohos-kmp"
    ;;
  *)
    echo "Usage: $0 [local|remote] [all|kmp|android|ohos-kmp]" >&2
    exit 1
    ;;
esac

echo "==> done"
