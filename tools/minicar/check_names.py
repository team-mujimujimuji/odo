#!/usr/bin/env python3
"""금지어 검사: 실제 차종명(국·영문)·공식 색상명이 소스·문서에 없는지 확인한다.

usage: python3 tools/minicar/check_names.py [--root <repo>] [paths...]
  기본 검사 대상: app/ tools/ docs/ NOTICE.md (저장소 루트 기준, 없는 경로는 건너뜀)
  denylist.txt 자체와 바이너리(png/webp/apk 등)·build/·.git/ 은 건너뛴다.
  영문 항목: 대소문자 무시 + 영숫자 경계, 공백은 [ _-]* 로 유연 매칭.
  한글 항목: 앞뒤가 한글이 아닌 경우만 매칭(두 글자 항목이 더 긴 낱말의 일부로 들어 있으면 걸리지 않음).
종료 코드 0 = 0건, 1 = 발견.
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SKIP_EXT = {".png", ".webp", ".jpg", ".jpeg", ".gif", ".apk", ".aab", ".jar", ".keystore", ".jks", ".ttf", ".otf",
            ".mp3", ".wav", ".ogg", ".zip", ".so", ".class", ".dex", ".webm", ".mp4", ".pdf", ".ico", ".bin"}
SKIP_DIRS = {".git", "build", ".gradle", ".kotlin", ".idea", "node_modules"}
HANGUL = "가-힣"


def load_denylist(path):
    terms = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            t = line.strip()
            if not t or t.startswith("#"):
                continue
            terms.append(t)
    return terms


def compile_terms(terms):
    pats = []
    for t in terms:
        if re.search(f"[{HANGUL}]", t):
            body = re.escape(t).replace("\\ ", "[ _-]*")
            pats.append((t, re.compile(f"(?<![{HANGUL}]){body}(?![{HANGUL}])")))
        else:
            body = re.escape(t).replace("\\ ", "[ _-]*")
            pats.append((t, re.compile(f"(?<![A-Za-z0-9]){body}(?![A-Za-z0-9])", re.IGNORECASE)))
    return pats


def iter_files(root, targets):
    for t in targets:
        p = os.path.join(root, t)
        if os.path.isfile(p):
            yield p
        elif os.path.isdir(p):
            for dp, dns, fns in os.walk(p):
                dns[:] = [d for d in dns if d not in SKIP_DIRS]
                for fn in fns:
                    yield os.path.join(dp, fn)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.abspath(os.path.join(HERE, "..", "..")))
    ap.add_argument("--denylist", default=os.path.join(HERE, "denylist.txt"))
    ap.add_argument("paths", nargs="*", default=["app", "tools", "docs", "NOTICE.md"])
    a = ap.parse_args()
    pats = compile_terms(load_denylist(a.denylist))
    deny_abs = os.path.abspath(a.denylist)
    hits = 0
    scanned = 0
    for fp in iter_files(a.root, a.paths):
        if os.path.abspath(fp) == deny_abs or os.path.splitext(fp)[1].lower() in SKIP_EXT:
            continue
        try:
            with open(fp, encoding="utf-8") as f:
                lines = f.read().split("\n")
        except (UnicodeDecodeError, OSError):
            continue
        scanned += 1
        for i, line in enumerate(lines, 1):
            for term, pat in pats:
                if pat.search(line):
                    hits += 1
                    print(f"{os.path.relpath(fp, a.root)}:{i}: [{term}] {line.strip()[:120]}")
    print(f"scanned {scanned} files, {hits} hits")
    sys.exit(1 if hits else 0)


if __name__ == "__main__":
    main()
