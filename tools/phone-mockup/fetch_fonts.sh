#!/bin/sh
# 폰 목업용 글꼴 4개를 태그 고정 URL에서 내려받고 sha256을 확인한다.
# 이미 맞는 파일은 건너뛴다. 받은 글꼴은 tools/phone-mockup/fonts/ 에만 두고 저장소에는 넣지 않는다(.gitignore).
#   Noto Sans CJK KR (Sans2.004): SIL Open Font License 1.1
#   Roboto (roboto-2 v2.138):     Apache License 2.0
set -eu

DIR="$(cd "$(dirname "$0")" && pwd)/fonts"
mkdir -p "$DIR"

if command -v shasum >/dev/null 2>&1; then
  sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
  sha() { sha256sum "$1" | cut -d' ' -f1; }
fi

fetch() { # 파일명 URL sha256
  out="$DIR/$1"
  if [ -f "$out" ] && [ "$(sha "$out")" = "$3" ]; then
    echo "ok   $1"
    return 0
  fi
  curl -fsSL --retry 3 -o "$out.part" "$2"
  got="$(sha "$out.part")"
  if [ "$got" != "$3" ]; then
    rm -f "$out.part"
    echo "sha256이 달라요: $1 ($got)" >&2
    exit 1
  fi
  mv "$out.part" "$out"
  echo "got  $1"
}

NOTO=https://raw.githubusercontent.com/notofonts/noto-cjk/Sans2.004/Sans/OTF/Korean
ROBOTO=https://raw.githubusercontent.com/googlefonts/roboto-2/v2.138/src/hinted

fetch NotoSansCJKkr-Medium.otf "$NOTO/NotoSansCJKkr-Medium.otf" 15c12166634936c31307b91bd049d95b4ba30c248e8c389b5388f75ae8ba90d6
fetch NotoSansCJKkr-Bold.otf   "$NOTO/NotoSansCJKkr-Bold.otf"   26d0c6748500a0444844280b308f5b62c7ae92ac6c6ac88148e502dd211eb52a
fetch Roboto-Medium.ttf        "$ROBOTO/Roboto-Medium.ttf"      2879a5ecb7fbfa13a7fc3e2cdd7fecbf73aa45e91b541dfdfa2c442eed0aac21
fetch Roboto-Bold.ttf          "$ROBOTO/Roboto-Bold.ttf"        61f89f8db49261c2f6106e8dccc35df7b2f7ed909020db40a3fc905e95f99334
