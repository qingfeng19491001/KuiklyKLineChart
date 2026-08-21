#!/bin/sh
# 手动签名 + 安装 + 冷启动 FullChartDemo + 截图。
# 用法: ./sign-install-run.sh [pageName] [截图输出路径]
set -e

PAGE=${1:-FullChartDemo}
SHOT=${2:-/tmp/ohos_fullchart_new.jpeg}

ROOT=$(cd "$(dirname "$0")" && pwd)
JAVA=/Users/mac/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home/bin/java
TOOL=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/lib/hap-sign-tool.jar
HDC=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc
BUNDLE=com.kuikly.kuiklychart

cd "$ROOT/signing"
cp ../entry/build/default/outputs/default/entry-default-unsigned.hap .
rm -f entry-default-signed.hap
"$JAVA" -jar "$TOOL" sign-app \
  -mode localSign -keyAlias "kline-app-key" -keyPwd 123456 \
  -appCertFile app-cert.cer -profileFile profile-urlsafe.json -profileSigned 0 \
  -inFile entry-default-unsigned.hap -signAlg SHA256withECDSA \
  -keystoreFile app-key.p12 -keystorePwd 123456 \
  -outFile entry-default-signed.hap -compatibleVersion 20 -signCode 1 >/dev/null

"$HDC" shell aa force-stop $BUNDLE
"$HDC" install entry-default-signed.hap
"$HDC" shell power-shell wakeup
sleep 1

# 注意: 不能带 -W，否则自定义 --ps 参数会被静默丢弃
"$HDC" shell aa start -a EntryAbility -b $BUNDLE --ps pageName "$PAGE"
sleep 12

"$HDC" shell snapshot_display -f /data/local/tmp/shot.jpeg >/dev/null
"$HDC" file recv /data/local/tmp/shot.jpeg "$SHOT" >/dev/null
echo "=== screenshot: $SHOT ==="
