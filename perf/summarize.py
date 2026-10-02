#!/usr/bin/env python3
"""Turns the RESULT lines written by compare.sh into a Markdown report.

For each scenario and config: the median over rounds of throughput and of the
latency percentiles, and throughput relative to A (GlassFish as released), to B
(ORB master without these changes) and to Cb (the stack under the change being
measured). When the lines carry the server's CPU use, also its median in cores
and in CPU microseconds per call, the latter relative to Cb or else to B: less
is better, and it depends far less on the runner than throughput.
"""
import re
import statistics
import sys
from collections import defaultdict

CONFIGS = {
    "A": "GlassFish 8.0.4 as released (ORB 5.0.2), 1 KB fragments",
    "A8": "GlassFish 8.0.4 as released (ORB 5.0.2), 8 KB fragments (buffer = fragment)",
    "B": "ORB master, without these changes, 1 KB fragments",
    "B8": "ORB master, 8 KB fragments (buffer = fragment)",
    "B64": "ORB master, 64 KB fragments (buffer = fragment)",
    "C8": "ORB + orb-iiop with these changes, 8 KB fragments, 1 KB initial buffer",
    "C": "ORB + orb-iiop with these changes, 1 KB fragments",
    "C64": "ORB + orb-iiop with these changes, 64 KB fragments, 1 KB initial buffer",
    "Cnq": "as C, but the work queue from master (the monitor)",
    "Bq": "as B, plus only the new work queue",
    "Bltq": "as B, plus the work queue on LinkedTransferQueue (first rewrite)",
    "Cb": "as C, with the ORB built from the baseline ref: the stack under the change being measured",
    "Cnf": "as C, without processing the next fragment inline",
    "Cltq": "as C, with the work queue on LinkedTransferQueue",
    "C64ltq": "as C64, with the work queue on LinkedTransferQueue",
}
SCENARIOS = {
    "small": "greet(\"hi\", 1): a small request and reply",
    "graph": "echoNode: a linked list of 50 Serializable nodes",
    "large": "echoLarge: a 64 KB String each way",
}

line_re = re.compile(r"config=(\S+) round=(\d+) RESULT (?:bean=(\S+) )?scenario=(\S+) .*? calls=(\d+) errors=(\d+) "
                     r"throughput=(\d+)/s p50=([\d.,]+)ms p90=([\d.,]+)ms p99=([\d.,]+)ms")
cpu_re = re.compile(r"serverCores=([\d.]+) serverUsPerCall=([\d.]+)")
io_re = re.compile(r"serverWritesPerCall=([\d.]+) serverReadsPerCall=([\d.]+) "
                   r"serverBytesOutPerCall=(\d+) serverBytesInPerCall=(\d+)")
io_runs = defaultdict(list)


def num(s):
    return float(s.replace(",", "."))


runs = defaultdict(list)
errors = 0
for line in open(sys.argv[1]):
    m = line_re.search(line)
    if not m:
        continue
    config, _, bean, scenario, _, err, tput, p50, p90, p99 = m.groups()
    errors += int(err)
    cpu = cpu_re.search(line)
    cores, us = (float(cpu.group(1)), float(cpu.group(2))) if cpu else (None, None)
    io = io_re.search(line)
    if io:
        io_runs[((bean or "GreeterBean"), scenario, config)].append(tuple(float(g) for g in io.groups()))
    runs[((bean or "GreeterBean"), scenario, config)].append((float(tput), num(p50), num(p90), num(p99), cores, us))

configs = [c for c in CONFIGS if any(k[2] == c for k in runs)]
out = ["## ORB before/after, same runner, same GlassFish install", ""]
out.append("Median of the rounds. Throughput in calls/s (higher is better), latency in ms. "
           "Server CPU, where measured: cores busy on average, and CPU microseconds per call (lower is better).")
out.append("")
for c in configs:
    out.append(f"- **{c}**: {CONFIGS[c]}")
out.append("")

BEANS = {
    "GreeterBean": "stateless, container managed transactions",
    "GreeterBmtBean": "stateless, bean managed transactions: no transaction per call",
    "GreeterSingletonBean": "singleton, bean managed transactions and concurrency: no pool, no lock",
}
for bean, bdesc in BEANS.items():
  for sc, desc in SCENARIOS.items():
    rows = [(c, runs[(bean, sc, c)]) for c in configs if runs.get((bean, sc, c))]
    if not rows:
        continue
    def median(r, i):
        values = [x[i] for x in r if x[i] is not None]
        return statistics.median(values) if values else None
    med = {c: [median(r, i) for i in range(6)] for c, r in rows}
    with_cpu = any(m[5] is not None for m in med.values())
    cpu_ref = "Cb" if "Cb" in med else "B"
    out.append(f"### {bean} ({bdesc}) - {sc}: {desc}")
    out.append("")
    head = "| config | calls/s | vs A | vs B | vs Cb | p50 | p90 | p99 |"
    rule = "|---|---:|---:|---:|---:|---:|---:|---:|"
    if with_cpu:
        head += f" server cores | server us/call | us/call vs {cpu_ref} |"
        rule += "---:|---:|---:|"
    out.append(head + " rounds |")
    out.append(rule + "---:|")
    for c, r in rows:
        t, p50, p90, p99, cores, us = med[c]
        vs = lambda ref: f"{t / med[ref][0]:.2f}x" if ref in med and ref != c else ""
        row = f"| {c} | {t:,.0f} | {vs('A')} | {vs('B')} | {vs('Cb')} | {p50:.3f} | {p90:.3f} | {p99:.3f} |"
        if with_cpu:
            ref_us = med[cpu_ref][5] if cpu_ref in med else None
            rel = f"{(us / ref_us - 1) * 100:+.1f}%" if us is not None and ref_us and c != cpu_ref else ""
            row += f" {cores:.2f} | {us:.1f} | {rel} |" if us is not None else " | | |"
        out.append(row + f" {len(r)} |")
    out.append("")
    io_rows = [(c, io_runs[(bean, sc, c)]) for c in configs if io_runs.get((bean, sc, c))]
    if io_rows:
        out.append("Server I/O per call (median of the rounds): system calls and bytes.")
        out.append("")
        out.append("| config | writes | reads | bytes out | bytes in |")
        out.append("|---|---:|---:|---:|---:|")
        for c, r in io_rows:
            w, rd, bo, bi = (statistics.median(x[i] for x in r) for i in range(4))
            out.append(f"| {c} | {w:.2f} | {rd:.2f} | {bo:,.0f} | {bi:,.0f} |")
        out.append("")

out.append(f"Errors across all runs: {errors}")
print("\n".join(out))
