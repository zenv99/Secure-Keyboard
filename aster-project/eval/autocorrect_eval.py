#!/usr/bin/env python3
"""Deterministic noisy-typing evaluation harness.

SymSpell is a reference baseline only; it is not the HeliBoard engine.
Use --engine native with a host CLI adapter to measure the real correction engine.
"""
from __future__ import annotations
import argparse, json, random, subprocess, sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Protocol

ROWS = ("qwertyuiop", "asdfghjkl", "zxcvbnm")
ROW_OFFSET = (0.0, 0.5, 1.0)
KEY_POS = {ch: (float(col) + ROW_OFFSET[row], float(row))
           for row, chars in enumerate(ROWS) for col, ch in enumerate(chars)}
KEYS = tuple(KEY_POS)

def nearest_key(x: float, y: float) -> str:
    return min(KEY_POS, key=lambda k: (KEY_POS[k][0]-x)**2 + (KEY_POS[k][1]-y)**2)

def touch_noise(word: str, rng: random.Random, sigma: float = 0.38,
                p_swap: float = 0.01, p_drop: float = 0.01,
                p_insert: float = 0.01) -> str:
    out, i = [], 0
    while i < len(word):
        ch = word[i].lower()
        if ch not in KEY_POS:
            out.append(word[i]); i += 1; continue
        roll = rng.random()
        if roll < p_drop:
            i += 1; continue
        if roll < p_drop+p_swap and i+1 < len(word) and word[i+1].lower() in KEY_POS:
            out.extend((word[i+1], word[i])); i += 2; continue
        x, y = KEY_POS[ch]
        out.append(nearest_key(rng.gauss(x, sigma), rng.gauss(y, sigma*1.1)))
        if rng.random() < p_insert: out.append(rng.choice(KEYS))
        i += 1
    return "".join(out)

class Corrector(Protocol):
    def correct(self, typed: str, context: list[str]) -> str: ...

class SymSpellBaseline:
    def __init__(self) -> None:
        try:
            from symspellpy import SymSpell, Verbosity
            from importlib.resources import files
        except ImportError as exc:
            raise SystemExit("Install aster-project/requirements-dev.txt") from exc
        self.verbosity = Verbosity
        self.sym = SymSpell(max_dictionary_edit_distance=2, prefix_length=7)
        dictionary = files("symspellpy").joinpath("frequency_dictionary_en_82_765.txt")
        if not self.sym.load_dictionary(str(dictionary), term_index=0, count_index=1):
            raise RuntimeError(f"Could not load dictionary: {dictionary}")
    def correct(self, typed: str, context: list[str]) -> str:
        hits = self.sym.lookup(typed, self.verbosity.TOP, max_edit_distance=2)
        return hits[0].term if hits else typed

class NativeCliCorrector:
    """CLI protocol: one context<TAB>typed line in, one corrected token out."""
    def __init__(self, command: list[str]) -> None:
        if not command: raise ValueError("--cmd is required for --engine native")
        self.proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=sys.stderr, text=True, bufsize=1)
    def correct(self, typed: str, context: list[str]) -> str:
        assert self.proc.stdin and self.proc.stdout
        self.proc.stdin.write(" ".join(context[-8:]) + "\t" + typed + "\n")
        self.proc.stdin.flush()
        result = self.proc.stdout.readline()
        if result == "": raise RuntimeError("Native correction CLI exited without output")
        return result.rstrip("\r\n")
    def close(self) -> None:
        if self.proc.stdin: self.proc.stdin.close()
        code = self.proc.wait(timeout=5)
        if code: raise RuntimeError(f"Native correction CLI exited with status {code}")

@dataclass
class Metrics:
    noisy_tokens: int = 0
    restored_tokens: int = 0
    clean_tokens: int = 0
    false_corrections: int = 0
    @property
    def fix_rate(self) -> float:
        return self.restored_tokens/self.noisy_tokens if self.noisy_tokens else 0.0
    @property
    def false_correction_rate(self) -> float:
        return self.false_corrections/self.clean_tokens if self.clean_tokens else 0.0
    def as_json(self) -> dict[str, int | float]:
        return {**asdict(self), "fix_rate": self.fix_rate,
                "false_correction_rate": self.false_correction_rate}

def run(lines: list[str], corrector: Corrector, seed: int = 1, sigma: float = 0.38) -> Metrics:
    rng, metrics = random.Random(seed), Metrics()
    for line in lines:
        context: list[str] = []
        for raw in line.split():
            token = "".join(c for c in raw if c.isalpha() or c == "'")
            if not token: continue
            typed = touch_noise(token, rng, sigma=sigma)
            output = corrector.correct(typed, context)
            if typed == token.lower():
                metrics.clean_tokens += 1
                metrics.false_corrections += output.lower() != typed
            else:
                metrics.noisy_tokens += 1
                metrics.restored_tokens += output.lower() == token.lower()
            context.append(token.lower())
    return metrics

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sentences", type=Path)
    parser.add_argument("--engine", choices=("baseline", "native"), default="baseline")
    parser.add_argument("--cmd", nargs="+")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--sigma", type=float, default=0.38)
    parser.add_argument("--min-fix", type=float, default=0.0)
    parser.add_argument("--max-false", type=float, default=1.0)
    args = parser.parse_args()
    corrector = SymSpellBaseline() if args.engine == "baseline" else NativeCliCorrector(args.cmd or [])
    try:
        lines = args.sentences.read_text(encoding="utf-8").splitlines()
        result = run(lines, corrector, seed=args.seed, sigma=args.sigma)
        print(json.dumps(result.as_json(), indent=2))
        return int(not (result.fix_rate >= args.min_fix and result.false_correction_rate <= args.max_false))
    finally:
        if isinstance(corrector, NativeCliCorrector): corrector.close()

if __name__ == "__main__":
    raise SystemExit(main())
