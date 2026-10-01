#!/usr/bin/env python3
"""Evaluation figures for the paper and the thesis.

Reads the CSVs under contracts/bench/results and backend/bench/results and
writes every figure to docs/figures as a 600 dpi PNG, a 600 dpi LZW TIFF and a
vector PDF. Single-column size (3.5 in wide), serif 8 pt text.

    python3 -m venv .venv && .venv/bin/pip install -r scripts/eval/requirements.txt
    .venv/bin/python scripts/eval/plots.py
"""
from __future__ import annotations

import csv
import math
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.ticker import FuncFormatter, LogLocator  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
GAS = ROOT / "contracts/bench/results"
SIM = ROOT / "backend/bench/results"
OUT = ROOT / "docs/figures"

WIDTH = 3.5
DPI = 600
SCHEME = {
    "S0": dict(color="#7f7f7f", marker="s", label="S0: per-unit records (v1)"),
    "S1": dict(color="#d62728", marker="^", label="S1: per-unit keys, no batching"),
    "S2": dict(color="#1f77b4", marker="o", label="S2: Merkle batch (proposed)"),
}

plt.rcParams.update(
    {
        "font.family": "serif",
        # TrueType Times metrics, embedded as Type 42: conference PDF checks reject Type 3 fonts.
        "font.serif": ["Times New Roman", "Liberation Serif", "DejaVu Serif"],
        "mathtext.fontset": "stix",
        "pdf.fonttype": 42,
        "ps.fonttype": 42,
        "font.size": 8,
        "axes.titlesize": 8,
        "axes.labelsize": 8,
        "legend.fontsize": 7,
        "xtick.labelsize": 7,
        "ytick.labelsize": 7,
        "axes.linewidth": 0.6,
        "lines.linewidth": 1.1,
        "lines.markersize": 3.5,
        "grid.linewidth": 0.4,
        "grid.alpha": 0.35,
        "legend.frameon": False,
        "savefig.bbox": "tight",
        "savefig.pad_inches": 0.02,
    }
)


def rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="") as f:
        return list(csv.DictReader(f))


def save(fig, name: str) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT / f"{name}.pdf")
    fig.savefig(OUT / f"{name}.png", dpi=DPI)
    fig.savefig(OUT / f"{name}.tiff", dpi=DPI, pil_kwargs={"compression": "tiff_lzw"})
    plt.close(fig)
    print(f"  {name}")


def size_axis(ax) -> None:
    ax.set_xscale("log")
    ax.xaxis.set_major_locator(LogLocator(base=10))
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"$10^{{{int(round(math.log10(v)))}}}$" if v > 1 else "1"))
    ax.set_xlabel("Batch size $n$ (units)")
    ax.grid(True, which="major")


def thousands(v, _):
    return f"{v / 1000:g}k" if abs(v) >= 1000 else f"{v:g}"


# ------------------------------------------------------------------ gas


def fig_registration() -> None:
    data = rows(GAS / "gas-registration.csv")
    fig, ax = plt.subplots(figsize=(WIDTH, 2.3))
    for scheme, style in SCHEME.items():
        pts = sorted((int(r["n"]), float(r["gas_per_unit"]), r["method"]) for r in data if r["scheme"] == scheme)
        ax.plot([p[0] for p in pts], [p[1] for p in pts], color=style["color"], label=style["label"], zorder=2)
        for n, gas, method in pts:
            ax.plot(n, gas, marker=style["marker"], color=style["color"],
                    markerfacecolor="white" if method == "extrapolated" else style["color"], zorder=3)
    size_axis(ax)
    ax.set_yscale("log")
    ax.set_ylim(1, 3e6)
    ax.set_ylabel("Registration gas per unit")
    handles, labels = ax.get_legend_handles_labels()
    handles.append(plt.Line2D([], [], ls="none", marker="o", color="#555", markerfacecolor="white"))
    labels.append("hollow marker: extrapolated")
    ax.legend(handles, labels, loc="lower left")
    save(fig, "fig-gas-registration")


def fig_lifecycle() -> None:
    data = rows(GAS / "gas-lifecycle.csv")
    fig, ax = plt.subplots(figsize=(WIDTH, 2.3))
    series = {}
    for scheme, style in SCHEME.items():
        pts = sorted((int(r["n"]), float(r["gas_per_unit"])) for r in data if r["scheme"] == scheme)
        series[scheme] = dict(pts)
        ax.plot([p[0] for p in pts], [p[1] for p in pts], marker=style["marker"], color=style["color"], label=style["label"])
    size_axis(ax)
    ax.yaxis.set_major_formatter(FuncFormatter(thousands))
    ax.set_ylabel("Lifecycle gas per unit")
    ax.set_ylim(0, 470_000)
    for n in (100, 100_000):
        cut = 1 - series["S2"][n] / series["S0"][n]
        ax.annotate(f"$-${cut:.0%}", (n, series["S2"][n]), xytext=(0, 10), textcoords="offset points",
                    ha="center", fontsize=7, color=SCHEME["S2"]["color"])
    ax.legend(loc="upper right", bbox_to_anchor=(1.0, 0.9))
    save(fig, "fig-gas-lifecycle")


def fig_consume() -> None:
    data = [r for r in rows(GAS / "gas-operations.csv") if r["scheme"] == "S2"]
    s0 = next(float(r["gas"]) for r in rows(GAS / "gas-operations.csv") if r["scheme"] == "S0" and r["operation"] == "consume")
    fig, ax = plt.subplots(figsize=(WIDTH, 2.3))
    for op, style, label in (("consume_cold", "-", "S2 consume, new bitmap word"), ("consume_warm", "--", "S2 consume, warm bitmap word")):
        pts = sorted((int(r["n"]), float(r["gas"])) for r in data if r["operation"] == op)
        ax.plot([p[0] for p in pts], [p[1] for p in pts], style, marker="o", color=SCHEME["S2"]["color"], label=label)
    ax.axhline(s0, color=SCHEME["S0"]["color"], linestyle=":", label="S0 consume (secret reveal)")
    size_axis(ax)
    ax.yaxis.set_major_formatter(FuncFormatter(thousands))
    ax.set_ylabel("Consume gas")
    ax.set_ylim(0, 100_000)
    proof = sorted((int(r["n"]), int(r["proof_length"])) for r in data if r["operation"] == "consume_cold")
    twin = ax.twinx()
    twin.bar([p[0] for p in proof], [p[1] for p in proof], width=[p[0] * 0.35 for p in proof], color="#ff7f0e", alpha=0.25, zorder=0)
    twin.set_ylabel("Proof length (hashes)", color="#c25e00")
    twin.set_ylim(0, 40)
    twin.tick_params(axis="y", colors="#c25e00")
    ax.set_zorder(twin.get_zorder() + 1)
    ax.patch.set_visible(False)
    ax.legend(loc="lower right")
    save(fig, "fig-gas-consume")


def fig_cost() -> None:
    data = rows(GAS / "cost-lifecycle.csv")
    prices = __import__("json").loads((GAS / "cost-prices.json").read_text())
    networks = [("ethereum-median", "Ethereum L1\n(median fee)"), ("ethereum-p90", "Ethereum L1\n(p90 fee)"),
                ("arbitrum", "Arbitrum One"), ("base", "Base")]
    n = 1000
    fig, ax = plt.subplots(figsize=(WIDTH, 2.3))
    width = 0.26
    for i, scheme in enumerate(SCHEME):
        values = [float(next(r for r in data if r["network"] == net and r["scheme"] == scheme and int(r["n"]) == n)["usd_per_unit"])
                  for net, _ in networks]
        xs = [k + (i - 1) * width for k in range(len(networks))]
        ax.bar(xs, values, width, color=SCHEME[scheme]["color"], label=scheme)
        for x, v in zip(xs, values):
            ax.text(x, v * 1.15, f"{v:.3f}" if v >= 0.01 else f"{v:.4f}", ha="center", va="bottom", fontsize=5.2, rotation=90)
    ax.set_yscale("log")
    ax.set_ylim(5e-4, 8)
    ax.set_xticks(range(len(networks)), [label for _, label in networks])
    ax.set_ylabel(f"USD per unit (lifecycle, $n$={n})")
    ax.grid(True, axis="y", which="major")
    ax.legend(loc="upper right", ncol=3)
    date = prices["takenAt"][:10]
    ax.set_title(f"Fee snapshot {date}, ETH = ${prices['ethUsd']['median']:,.0f}", loc="left", fontsize=6.5, color="#444")
    save(fig, "fig-cost-usd")


def fig_offchain() -> None:
    data = rows(GAS / "offchain.csv")
    pts = sorted((int(r["n"]), float(r["keygen_ms"]), float(r["tree_ms"]), int(r["proof_bytes"])) for r in data)
    fig, ax = plt.subplots(figsize=(WIDTH, 2.2))
    ax.plot([p[0] for p in pts], [p[1] / 1000 for p in pts], marker="o", color="#2ca02c", label="Unit key generation")
    ax.plot([p[0] for p in pts], [p[2] / 1000 for p in pts], marker="s", color="#9467bd", label="Merkle tree build")
    size_axis(ax)
    ax.set_yscale("log")
    ax.set_ylabel("Time (s)")
    twin = ax.twinx()
    twin.plot([p[0] for p in pts], [p[3] for p in pts], "--", marker="D", color="#ff7f0e", label="Proof size")
    twin.set_ylabel("Proof size (bytes)", color="#c25e00")
    twin.tick_params(axis="y", colors="#c25e00")
    twin.set_ylim(0, 640)
    lines = ax.get_legend_handles_labels()
    more = twin.get_legend_handles_labels()
    ax.legend(lines[0] + more[0], lines[1] + more[1], loc="upper left")
    save(fig, "fig-offchain")


# ------------------------------------------------------------------ clone detection

DETECTOR_LABELS = {
    ("proposed", "noisy-or, threshold=0.60"): "Proposed (noisy-OR)",
    ("status-only", "any scan after consumption"): "Status only",
    ("scan-count", "total scans > 3"): "Scans > 3",
    ("scan-count", "total scans > 5"): "Scans > 5",
    ("device-count", "distinct devices > 3"): "Devices > 3",
}


def fig_detectors() -> None:
    data = rows(SIM / "scansim_summary.csv")
    detectors = [(r, DETECTOR_LABELS[(r["detector"], r["params"])]) for r in data if (r["detector"], r["params"]) in DETECTOR_LABELS]
    metrics = [("precision", "Precision"), ("recall", "Recall"), ("f1", "F1"), ("fpr", "FPR")]
    colors = ["#1f77b4", "#7f7f7f", "#bcbd22", "#17becf", "#9467bd"]
    fig, ax = plt.subplots(figsize=(WIDTH, 2.3))
    width = 0.16
    for i, (r, label) in enumerate(detectors):
        xs = [k + (i - (len(detectors) - 1) / 2) * width for k in range(len(metrics))]
        ax.bar(xs, [float(r[f"{m}_mean"]) for m, _ in metrics], width, yerr=[float(r[f"{m}_std"]) for m, _ in metrics],
               color=colors[i], label=label, error_kw=dict(lw=0.5, capsize=1.2))
    ax.set_xticks(range(len(metrics)), [label for _, label in metrics])
    ax.set_ylim(0, 1.18)
    ax.set_ylabel("Score (mean of 10 seeds)")
    ax.grid(True, axis="y")
    ax.legend(loc="upper right", ncol=3, fontsize=6.2, columnspacing=0.8, handlelength=1.2)
    save(fig, "fig-clone-detectors")


def fig_ablation() -> None:
    data = rows(SIM / "scansim_summary.csv")
    order = [("proposed", "Full model"), ("without many_devices", "$-$ many devices"),
             ("without scanned_after_consumption", "$-$ after consumption"), ("without region rules", "$-$ region rules")]
    picked = []
    for key, label in order:
        r = next(r for r in data if (r["detector"] == key) or (r["detector"] == "ablation" and r["params"] == key))
        picked.append((label, float(r["f1_mean"]), float(r["f1_std"]), float(r["recall_mean"])))
    fig, ax = plt.subplots(figsize=(WIDTH, 1.8))
    ys = range(len(picked))[::-1]
    ax.barh(list(ys), [p[1] for p in picked], xerr=[p[2] for p in picked], color=["#1f77b4"] + ["#aec7e8"] * 3, height=0.6,
            error_kw=dict(lw=0.5, capsize=1.5))
    for y, p in zip(ys, picked):
        ax.text(p[1] + 0.01, y, f"F1 {p[1]:.3f} (recall {p[3]:.2f})", va="center", fontsize=6.5)
    ax.set_yticks(list(ys), [p[0] for p in picked])
    ax.set_xlim(0.5, 1.05)
    ax.set_xlabel("F1 score")
    ax.grid(True, axis="x")
    save(fig, "fig-clone-ablation")


def fig_threshold() -> None:
    data = sorted(rows(SIM / "scansim_thresholds.csv"), key=lambda r: float(r["threshold"]))
    t = [float(r["threshold"]) for r in data]
    fig, ax = plt.subplots(figsize=(WIDTH, 2.2))
    for key, label, style in (("precision", "Precision", "-"), ("recall", "Recall", "--"), ("f1", "F1", "-"), ("fpr", "FPR", ":")):
        ax.plot(t, [float(r[key]) for r in data], style, label=label, lw=1.4 if key == "f1" else 1.0)
    ax.axvline(0.6, color="#444", lw=0.6)
    ax.text(0.61, 0.45, "deployed\nthreshold 0.6", fontsize=6.3, color="#444")
    ax.set_xlabel("Risk-score threshold")
    ax.set_ylabel("Score")
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1.03)
    ax.grid(True)
    ax.legend(loc="center left")
    save(fig, "fig-clone-threshold")


def fig_copies() -> None:
    data = sorted(rows(SIM / "scansim_by_clones.csv"), key=lambda r: int(r["clones"]))
    k = [int(r["clones"]) for r in data]
    fig, ax = plt.subplots(figsize=(WIDTH, 2.1))
    ax.plot(k, [float(r["recall"]) for r in data], marker="o", color="#1f77b4", label="Recall (cloned units flagged)")
    ax.set_xscale("log")
    ax.set_xticks(k, [str(v) for v in k])
    ax.minorticks_off()
    ax.set_xlabel("Copies of one public label in circulation")
    ax.set_ylabel("Recall")
    ax.set_ylim(0, 1.05)
    ax.grid(True)
    twin = ax.twinx()
    twin.step(k, [float(r["median_clone_scans_to_flag"]) for r in data], where="mid", color="#ff7f0e", ls="--",
              label="Median clone scans until flagged")
    twin.set_ylabel("Clone scans until flagged", color="#c25e00")
    twin.tick_params(axis="y", colors="#c25e00")
    twin.set_ylim(0, 6)
    lines = ax.get_legend_handles_labels()
    more = twin.get_legend_handles_labels()
    ax.legend(lines[0] + more[0], lines[1] + more[1], loc="lower right")
    save(fig, "fig-clone-copies")


# ------------------------------------------------------------------ latency


def fig_latency() -> None:
    path = SIM / "loadgen.csv"
    if not path.exists():
        print("  (skip latency: no loadgen.csv)")
        return
    data = rows(path)
    reads = defaultdict(list)
    for r in data:
        if r["endpoint"] != "consume":
            reads[(r["endpoint"], r["cache"])].append(r)
    fig, ax = plt.subplots(figsize=(WIDTH, 2.3))
    styles = {("verify", "snapshot-cache"): ("#1f77b4", "o", "verify (cached snapshot)"),
              ("verify", "no-cache"): ("#d62728", "s", "verify (no cache)"),
              ("proof", "snapshot-cache"): ("#2ca02c", "^", "proof"),
              ("batch", "snapshot-cache"): ("#9467bd", "D", "batch detail")}
    for key, (color, marker, label) in styles.items():
        pts = sorted((int(r["concurrency"]), float(r["p50_ms"]), float(r["p95_ms"])) for r in reads.get(key, []))
        if not pts:
            continue
        ax.plot([p[0] for p in pts], [p[1] for p in pts], marker=marker, color=color, label=f"{label}, p50")
        ax.plot([p[0] for p in pts], [p[2] for p in pts], marker=marker, color=color, ls="--", markerfacecolor="white", lw=0.8)
    ax.set_xscale("log", base=2)
    ax.set_yscale("log")
    levels = sorted({int(r["concurrency"]) for r in data if r["endpoint"] != "consume"})
    ax.set_xticks(levels, [str(v) for v in levels])
    ax.minorticks_off()
    ax.set_xlabel("Concurrent clients")
    ax.set_ylabel("Latency (ms)")
    ax.grid(True)
    ax.legend(loc="upper left", fontsize=6.2)
    ax.text(0.99, 0.03, "solid: p50, dashed: p95", transform=ax.transAxes, ha="right", fontsize=6.3, color="#444")
    save(fig, "fig-latency-read")

    consume = [r for r in data if r["endpoint"] == "consume"]
    if not consume:
        return
    groups = defaultdict(dict)
    for r in consume:
        block = r["label"].split("block=")[-1].replace("ms", "")
        groups[int(block)][int(r["concurrency"])] = r
    blocks = sorted(groups)
    fig, ax = plt.subplots(figsize=(WIDTH, 2.1))
    width = 0.36
    conc = sorted({c for g in groups.values() for c in g})
    for i, c in enumerate(conc):
        xs = [k + (i - (len(conc) - 1) / 2) * width for k in range(len(blocks))]
        p50 = [float(groups[b][c]["p50_ms"]) if c in groups[b] else 0 for b in blocks]
        p95 = [float(groups[b][c]["p95_ms"]) if c in groups[b] else 0 for b in blocks]
        ax.bar(xs, p50, width, color=["#1f77b4", "#aec7e8"][i % 2], label=f"{c} concurrent buyer{'s' if c > 1 else ''}")
        ax.errorbar(xs, p50, yerr=[[0] * len(p50), [b - a for a, b in zip(p50, p95)]], fmt="none", ecolor="#333", lw=0.6, capsize=1.5)
        for x, v, top in zip(xs, p50, p95):
            text = f"{v:.0f} ms" if v < 1000 else f"{v / 1000:.2f} s"
            ax.text(x, top * 1.25, text, ha="center", fontsize=6)
    labels = {0: "instant\n(automine)", 2000: "2 s blocks\n(L2-like)", 12000: "12 s blocks\n(L1-like)"}
    ax.set_xticks(range(len(blocks)), [labels.get(b, f"{b} ms") for b in blocks])
    ax.set_yscale("log")
    ax.set_ylim(1, 2e5)
    ax.set_ylabel("Consume latency (ms)")
    ax.grid(True, axis="y")
    ax.legend(loc="upper left", fontsize=6.3, title="bar: p50, whisker: p95", title_fontsize=6.3)
    save(fig, "fig-latency-consume")


def main() -> None:
    print(f"writing figures to {OUT.relative_to(ROOT)}")
    fig_registration()
    fig_lifecycle()
    fig_consume()
    fig_cost()
    fig_offchain()
    fig_detectors()
    fig_ablation()
    fig_threshold()
    fig_copies()
    fig_latency()


if __name__ == "__main__":
    main()
