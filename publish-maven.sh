#!/usr/bin/env bash
# Publish KuiklyKLineChart Maven artifacts (KMP and/or Android AAR).
# iOS uses CocoaPods (KuiklyKLineChartIOS); OHOS KMP uses settings.ohos.gradle.kts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

TARGET="${1:-local}"          # local | pages | github | remote
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

use_community_coordinates() {
  export GROUP_ID="${GITHUB_GROUP_ID}"
  export ORG_GRADLE_PROJECT_GROUP_ID="${GITHUB_GROUP_ID}"
  export ORG_GRADLE_PROJECT_lowercaseMavenArtifacts=true
}

push_github_pages() {
  local src="$1"
  local wt="$ROOT/build/gh-pages-worktree"
  rm -rf "$wt"
  mkdir -p "$src"
  if git show-ref --verify --quiet refs/heads/gh-pages || git ls-remote --exit-code --heads origin gh-pages >/dev/null 2>&1; then
    git fetch origin gh-pages 2>/dev/null || true
    git worktree add "$wt" gh-pages 2>/dev/null || git worktree add "$wt" origin/gh-pages
  else
    git worktree add --detach "$wt"
    git -C "$wt" checkout --orphan gh-pages
    git -C "$wt" rm -rf . >/dev/null 2>&1 || true
  fi
  rsync -a --delete --exclude '.git' "$src/" "$wt/"
  cat > "$wt/index.html" <<EOF
<!doctype html><meta charset="utf-8">
<title>KuiklyKLineChart Maven</title>
<p>Public Maven repo (no credentials):</p>
<pre>maven("https://${GITHUB_MAVEN_OWNER}.github.io/${GITHUB_MAVEN_REPO}")</pre>
<p><code>io.github.${GITHUB_MAVEN_OWNER}:kuiklyklinechart:${BASE_VERSION}-${KOTLIN_VERSION}</code></p>
EOF
  git -C "$wt" add -A
  if git -C "$wt" diff --cached --quiet; then
    echo "GitHub Pages Maven repo unchanged"
  else
    git -C "$wt" -c user.name="$(git log -1 --format='%an')" -c user.email="$(git log -1 --format='%ae')" \
      commit -m "Publish Maven ${ORG_GRADLE_PROJECT_GROUP_ID}:${ORG_GRADLE_PROJECT_MAVEN_VERSION}"
    git -C "$wt" push -u origin gh-pages
  fi
  git worktree remove --force "$wt"
}

PUBLISH_TASK="publishToMavenLocal"
if [[ "$TARGET" == "pages" ]]; then
  PAGES_DIR="$ROOT/build/github-pages-maven"
  rm -rf "$PAGES_DIR"
  mkdir -p "$PAGES_DIR"
  use_community_coordinates
  export MAVEN_REPO_URL="file://${PAGES_DIR}"
  export ORG_GRADLE_PROJECT_MAVEN_REPO_URL="${MAVEN_REPO_URL}"
  MAVEN_USERNAME=""
  MAVEN_PASSWORD=""
  PUBLISH_TASK="publish"
elif [[ "$TARGET" == "github" ]]; then
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
  export ORG_GRADLE_PROJECT_lowercaseMavenArtifacts=true
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
    echo "Usage: $0 [local|pages|github|remote] [all|kmp|android|ohos-kmp]" >&2
    exit 1
    ;;
esac

if [[ "$TARGET" == "pages" ]]; then
  echo "==> pushing GitHub Pages Maven repo"
  push_github_pages "$PAGES_DIR"
fi

echo "==> done"
