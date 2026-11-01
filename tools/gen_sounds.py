#!/usr/bin/env python3
"""Synthesizes the game's chiptune sound effects into app/src/main/res/raw/.

Pure Python (no numpy). 22050 Hz, 16-bit mono WAV. Run from the repo root:
    python3 tools/gen_sounds.py
"""
import math
import os
import struct
import wave

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def osc(wave_kind, phase):
    """One sample of a waveform at the given phase in [0,1)."""
    if wave_kind == "square":
        return 1.0 if phase < 0.5 else -1.0
    if wave_kind == "pulse":  # 25% duty, thinner/brighter than square
        return 1.0 if phase < 0.25 else -1.0
    if wave_kind == "triangle":
        return 4.0 * abs(phase - 0.5) - 1.0
    if wave_kind == "saw":
        return 2.0 * phase - 1.0
    return math.sin(2 * math.pi * phase)


def render(duration, freq_at, wave_kind="square", vol=0.5, attack=0.003, release=0.02, env=None):
    """freq_at(t) -> Hz. env(t) -> 0..1 multiplier, optional. Returns list of floats."""
    n = int(duration * SR)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / SR
        f = freq_at(t)
        phase = (phase + f / SR) % 1.0
        a = 1.0
        if t < attack:
            a = t / attack
        if duration - t < release:
            a = min(a, (duration - t) / release)
        if env:
            a *= env(t)
        out.append(osc(wave_kind, phase) * vol * a)
    return out


def silence(duration):
    return [0.0] * int(duration * SR)


def write(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = bytearray()
        for s in samples:
            s = max(-1.0, min(1.0, s))
            frames += struct.pack("<h", int(s * 32767))
        w.writeframes(bytes(frames))
    print(f"{name:16s} {len(samples) / SR:5.2f}s  {os.path.getsize(path) // 1024:4d} KB")


def note(freq, dur, wave_kind="square", vol=0.45, decay=True):
    env = (lambda t: max(0.0, 1.0 - 0.6 * t / dur)) if decay else None
    return render(dur, lambda t: freq, wave_kind, vol, env=env)


# Note frequencies (equal temperament, A4 = 440)
def hz(name):
    names = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3, "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}
    pitch, octave = name[:-1], int(name[-1])
    return 440.0 * 2 ** ((names[pitch] + (octave - 4) * 12) / 12)


def main():
    # Alternating chomp: a quick downward chirp, then its upward twin.
    write("spark_a", render(0.07, lambda t: 560 - 3600 * t, "pulse", 0.35, release=0.015))
    write("spark_b", render(0.07, lambda t: 300 + 3600 * t, "pulse", 0.35, release=0.015))

    # Surge pickup: fast rising arpeggio.
    write("surge_pickup", sum((note(hz(n), 0.055, "square", 0.4) for n in ["C5", "E5", "G5", "C6", "E6"]), []))

    # Surge loop: fast warble between two pitches. 0.4 s = 2 full wobble cycles so it loops cleanly.
    write("surge_loop", render(0.4, lambda t: 420 + 220 * math.sin(2 * math.pi * 5 * t), "triangle", 0.28,
                               attack=0.004, release=0.004))

    # Siren loop: slow rise-and-fall hum under normal play. 0.8 s = one cycle.
    write("siren_loop", render(0.8, lambda t: 200 + 70 * math.sin(2 * math.pi * 1.25 * t - math.pi / 2), "triangle", 0.16,
                               attack=0.004, release=0.004))

    # Sentinel caught: upward zap with vibrato.
    write("sentinel_eaten", render(0.22, lambda t: 220 + 4500 * t + 60 * math.sin(2 * math.pi * 40 * t), "square", 0.4))

    # Death: long falling warble, then two low thuds.
    death = render(0.9, lambda t: 820 * (1 - t / 0.9) ** 1.6 + 90 + 40 * math.sin(2 * math.pi * 14 * t), "square", 0.4,
                   env=lambda t: 1.0 - 0.3 * t / 0.9)
    death += silence(0.06)
    for _ in range(2):
        death += render(0.09, lambda t: 70 - 300 * t, "saw", 0.45, release=0.04) + silence(0.07)
    write("death", death)

    # Start jingle: a short original tune.
    tune = [("E5", .12), ("G5", .12), ("A5", .12), ("E5", .12), ("G5", .12), ("B5", .24),
            ("A5", .12), ("G5", .12), ("E5", .12), ("D5", .12), ("E5", .36)]
    jingle = []
    for n, d in tune:
        jingle += note(hz(n), d * 0.92, "square", 0.4) + silence(d * 0.08)
    write("start_jingle", jingle)

    # Bonus collected: two bright rising notes.
    write("bonus", note(hz("E6"), 0.07, "pulse", 0.4) + note(hz("A6"), 0.14, "pulse", 0.4))

    # Extra life: a quick bright fanfare.
    write("extra_life", sum((note(hz(n), 0.06, "square", 0.42) for n in ["C6", "E6", "G6", "C7", "E7", "G7"]), []) + note(hz("C7"), 0.25, "square", 0.42))

    # Level clear: quick ascending run with a held top note.
    run = sum((note(hz(n), 0.09, "pulse", 0.42) for n in ["C5", "D5", "E5", "G5", "A5"]), [])
    run += note(hz("C6"), 0.5, "square", 0.42)
    write("level_clear", run)


if __name__ == "__main__":
    main()
