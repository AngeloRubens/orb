#!/usr/bin/env python3
"""Turns the RESULT lines written by compare.sh into a Markdown report.

For each scenario and config: the median over rounds of throughput and of the
latency percentiles, and throughput relative to A (GlassFish as released) and
to B (ORB master without these changes).
"""
import re
import statistics
import sys
from collections import defaultdict

CONFIGS = {
    "A": "GlassFish 8.0.4 as released (ORB 5.0.2), 1 KB fragments",
    "B": "ORB master, without these changes, 1 KB fragments",
    "B64": "ORB master, 64 KB fragments (buffer = fragment)",
    "C": "ORB + orb-iiop with these changes, 1 KB fragments",
    "C64": "ORB + orb-iiop with these changes, 64 KB fragments, 1 KB initial buffer",
    "Cnq": "as C, but the work queue from master (every change except the queue)",
    "Bq": "as B, plus only the new work queue",
    "Bltq": "as B, plus the work queue on LinkedTransferQueue (first rewrite)",
    "Cnf": "as C, without processing the next fragment inline",
}
SCENARIOS = {
    "small": "greet(\"hi\", 1): a small request and reply",
    "graph": "echoNode: a linked list of 50 Serializable nodes",
    "large": "echoLarge: a 64 KB String each way",
}

line_re = re.compile(r"config=(\S+) round=(\d+) RESULT scenario=(\S+) .*? calls=(\d+) errors=(\d+) "
                     r"throughput=(\d+)/s p50=([\d.,]+)ms p90=([\d.,]+)ms p99=([\d.,]+)ms")


def num(s):
    return float(s.replace(",", "."))


runs = defaultdict(list)
errors = 0
for line in open(sys.argv[1]):
    m = line_re.search(line)
    if not m:
        continue
    config, _, scenario, _, err, tput, p50, p90, p99 = m.groups()
    errors += int(err)
    runs[(scenario, config)].append((float(tput), num(p50), num(p90), num(p99)))

configs = [c for c in CONFIGS if any(k[1] == c for k in runs)]
out = ["## ORB before/after, same runner, same GlassFish install", ""]
out.append("Median of the rounds. Throughput in calls/s (higher is better), latency in ms.")
out.append("")
for c in configs:
    out.append(f"- **{c}**: {CONFIGS[c]}")
out.append("")

for sc, desc in SCENARIOS.items():
    rows = [(c, runs[(sc, c)]) for c in configs if runs.get((sc, c))]
    if not rows:
        continue
    med = {c: [statistics.median(x[i] for x in r) for i in range(4)] for c, r in rows}
    out.append(f"### {sc}: {desc}")
    out.append("")
    out.append("| config | calls/s | vs A | vs B | p50 | p90 | p99 | rounds |")
    out.append("|---|---:|---:|---:|---:|---:|---:|---:|")
    for c, r in rows:
        t, p50, p90, p99 = med[c]
        vs = lambda ref: f"{t / med[ref][0]:.2f}x" if ref in med and ref != c else ""
        out.append(f"| {c} | {t:,.0f} | {vs('A')} | {vs('B')} | {p50:.3f} | {p90:.3f} | {p99:.3f} | {len(r)} |")
    out.append("")

out.append(f"Errors across all runs: {errors}")
print("\n".join(out))
