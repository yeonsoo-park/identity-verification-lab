#!/usr/bin/env bash
# 화면설계서(docs/storyboard/storyboard.html)를 화면별 PNG로 렌더링 → docs/assets/screens/
set -euo pipefail
cd "$(dirname "$0")/../.."
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
for id in SC-01 SC-02 SC-03 SC-04 SC-05 SC-06 SC-07 SC-08; do
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1.5 --window-size=1600,1000 \
    --virtual-time-budget=2000 --screenshot="docs/assets/screens/$id.png" "file://$PWD/docs/storyboard/storyboard.html#$id" >/dev/null 2>&1
  echo "생성: docs/assets/screens/$id.png"
done
