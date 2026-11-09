#!/usr/bin/env python3
"""오도 이어콘 3개를 합성한다(설계서 §8). 외부 음원 없이 사인파 + 지수 감쇠 + 짧은 잡음 어택으로 나무토막 질감을 낸다.

    python3 tools/audio/make_earcons.py            # app/src/main/res/raw/earcon_*.ogg 갱신
    python3 tools/audio/make_earcons.py --wav-only # OGG 변환 없이 WAV만(ffmpeg 없을 때)

- earcon_xp.ogg          "톡"      880 Hz 1타
- earcon_levelup.ogg     "톡-톡"   880 → 1175 Hz 2타(120 ms 간격)
- earcon_achievement.ogg 높은 "톡" 1320 Hz 1타

합성은 모노 44.1 kHz, 각 0.35 s 이내, 피크 -3 dBFS. OGG 변환은 ffmpeg로 한다: libvorbis가 있으면 모노 OGG,
없으면 ffmpeg 내장 vorbis 인코더가 2채널만 받으므로 좌우가 같은 2채널 OGG가 된다(저장소의 커밋본은 이 경우).
ffmpeg는 bitexact로 돌리고 메타데이터를 지워 Ogg 일련번호·인코더 태그를 고정한다. 같은 ffmpeg(9.0.2에서 확인)면 다시 돌려도 바이트가 같다.
표준 라이브러리만 쓴다(numpy 없음).
"""
from __future__ import annotations

import argparse
import math
import random
import shutil
import struct
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

RATE = 44_100
PEAK = 10 ** (-3 / 20)  # -3 dBFS
ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "app" / "src" / "main" / "res" / "raw"


def knock(freq: float, length_s: float = 0.16, decay: float = 28.0, seed: int = 1) -> list[float]:
    """나무토막 한 타: 기본음 + 약한 2.4배 배음, 지수 감쇠, 첫 3 ms에 잡음 어택."""
    rng = random.Random(seed)
    n = int(RATE * length_s)
    out = []
    for i in range(n):
        t = i / RATE
        env = math.exp(-decay * t)
        tone = math.sin(2 * math.pi * freq * t) + 0.25 * math.sin(2 * math.pi * freq * 2.4 * t) * math.exp(-decay * 2 * t)
        noise = (rng.random() * 2 - 1) * math.exp(-t / 0.003) * 0.6
        out.append((tone + noise) * env)
    return out


def mix(parts: list[tuple[float, list[float]]]) -> list[float]:
    """(시작 초, 샘플) 목록을 한 버퍼에 더한다."""
    end = max(int(start * RATE) + len(s) for start, s in parts)
    buf = [0.0] * end
    for start, s in parts:
        o = int(start * RATE)
        for i, v in enumerate(s):
            buf[o + i] += v
    return buf


def normalize(buf: list[float]) -> list[float]:
    peak = max(abs(v) for v in buf) or 1.0
    g = PEAK / peak
    return [v * g for v in buf]


def write_wav(path: Path, buf: list[float]) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v)) * 32767)) for v in buf))


def to_ogg(wav: Path, ogg: Path) -> None:
    """libvorbis가 있으면 그것으로, 없으면(homebrew 기본 ffmpeg) 내장 실험용 vorbis 인코더로 변환한다."""
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        sys.exit("ffmpeg가 없습니다. --wav-only로 WAV만 만들거나 ffmpeg를 설치하세요.")
    encoders = subprocess.run([ffmpeg, "-hide_banner", "-encoders"], capture_output=True, text=True, check=False).stdout
    if " libvorbis " in encoders:
        codec = ["-c:a", "libvorbis", "-q:a", "4"]
    else:
        # 내장 vorbis 인코더는 2채널만 받는다 → 모노를 좌우 동일 스테레오로 올려 변환한다.
        codec = ["-ac", "2", "-c:a", "vorbis", "-strict", "-2", "-b:a", "96k"]
    # bitexact + 메타데이터 제거: Ogg 스트림 일련번호(기본은 무작위)와 인코더 버전 태그를 고정해 다시 돌려도 같은 바이트가 나오게 한다.
    bitexact = ["-map_metadata", "-1", "-flags:a", "+bitexact", "-fflags", "+bitexact"]
    subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", str(wav), *codec, *bitexact, str(ogg)], check=True)


EARCONS = {
    "earcon_xp": lambda: knock(880.0, seed=1),
    "earcon_levelup": lambda: mix([(0.0, knock(880.0, seed=2)), (0.12, knock(1174.7, seed=3))]),
    "earcon_achievement": lambda: knock(1318.5, length_s=0.18, decay=24.0, seed=4),
}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--wav-only", action="store_true", help="OGG 변환 없이 WAV만 out 디렉터리에 둔다")
    ap.add_argument("--out", type=Path, default=OUT_DIR, help="출력 디렉터리(기본 app/src/main/res/raw)")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, make in EARCONS.items():
            buf = normalize(make())
            wav = (args.out if args.wav_only else Path(tmp)) / f"{name}.wav"
            write_wav(wav, buf)
            if args.wav_only:
                print(f"{wav}  {len(buf) / RATE:.3f} s")
                continue
            ogg = args.out / f"{name}.ogg"
            to_ogg(wav, ogg)
            print(f"{ogg}  {len(buf) / RATE:.3f} s  {ogg.stat().st_size} B")


if __name__ == "__main__":
    main()
