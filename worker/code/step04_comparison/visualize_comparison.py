"""Plot saved IF/LOF/ECOD scores without fitting models or modifying source files."""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path

import numpy as np
from scipy.stats import spearmanr

os.environ.setdefault("MPLCONFIGDIR", "/private/tmp/wonday-weather-matplotlib")
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.backends.backend_pdf import PdfPages
from matplotlib.colors import LogNorm

ROOT = Path(__file__).resolve().parents[2] / "data" / "gyeonggi_processed"
FOLDERS = {"IF": "step04_official_daily", "LOF": "step04_lof", "ECOD": "step04_ecod"}
COLORS = {"IF": "#2864ad", "LOF": "#d47522", "ECOD": "#17866c"}
FEATURES = ("work_max_temp_c", "work_mean_humidity_pct", "work_heat_minutes_ge31")
LABELS = {"AI1": "AI1 · 강수·강풍", "AI2": "AI2 · 온열"}


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def load_data(period):
    datasets, protected, identities = {}, {}, {}
    for algorithm, folder in FOLDERS.items():
        directory = ROOT / folder
        for path in directory.iterdir():
            if path.is_file():
                protected[str(path)] = sha(path)
        metadata_path = directory / "model_metadata.json"
        metadata = read_json(metadata_path)
        score_path = directory / "weather_ai_scores.csv"
        score_metadata = read_json(score_path.with_suffix(".metadata.json"))
        if score_metadata["models_metadata_sha256"] != sha(metadata_path):
            raise ValueError(f"{algorithm}: stale score/model metadata")
        if "scores_sha256" in score_metadata and score_metadata["scores_sha256"] != sha(score_path):
            raise ValueError(f"{algorithm}: score checksum mismatch")
        identities[algorithm] = dict(model_id=metadata["model_id"], features_sha256=score_metadata["features_sha256"],
                                     training_hashes={m: metadata["models"][m]["training_matrix_sha256"] for m in LABELS})
        records = {}
        with score_path.open(encoding="utf-8-sig", newline="") as stream:
            for row in csv.DictReader(stream):
                if row["period"] != period:
                    continue
                key = row["station_code"], row["work_date"]
                if key in records:
                    raise ValueError(f"duplicate key: {key}")
                record = {k: row[k] for k in (*FEATURES, "station_name", "ai1_eligible", "ai2_eligible")}
                for model in LABELS:
                    value = row["S_" + model]
                    expected = row[model.lower() + "_eligible"].lower() in ("true", "1")
                    if bool(value) != expected:
                        raise ValueError(f"{algorithm}/{model}: eligibility mismatch")
                    record[model] = float(value) if value else np.nan
                    if value and not 0 <= record[model] <= 100:
                        raise ValueError("nonfinite or out-of-range score")
                records[key] = record
        datasets[algorithm] = records
    if len({v["features_sha256"] for v in identities.values()}) != 1:
        raise ValueError("algorithms scored different input files")
    for model in LABELS:
        if len({v["training_hashes"][model] for v in identities.values()}) != 1:
            raise ValueError("algorithms used different training matrices")
    keys = sorted(datasets["IF"])
    if not keys:
        raise ValueError("no rows in selected period")
    for algorithm, records in datasets.items():
        if set(keys) != set(records):
            raise ValueError("algorithms have different target rows")
        for key in keys:
            for field in (*FEATURES, "station_name", "ai1_eligible", "ai2_eligible"):
                if records[key][field] != datasets["IF"][key][field]:
                    raise ValueError(f"algorithm feature mismatch: {key}/{field}")
    scores = {}
    weather = {}
    for model in LABELS:
        chosen = [key for key in keys if np.isfinite(datasets["IF"][key][model])]
        scores[model] = {a: np.array([datasets[a][key][model] for key in chosen]) for a in FOLDERS}
        if not chosen:
            raise ValueError(f"no eligible {model} rows")
        if model == "AI2":
            weather = {f: np.array([float(datasets["IF"][key][f]) for key in chosen]) for f in FEATURES}
    return scores, weather, protected, identities, len(keys)


def configure_style():
    candidates = [Path("/System/Library/Fonts/Supplemental/AppleGothic.ttf"),
                  Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")]
    for path in candidates:
        if path.exists():
            font_manager.fontManager.addfont(str(path))
            plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(path)).get_name()
            break
    plt.rcParams.update({"font.size": 13, "axes.titlesize": 15, "axes.labelsize": 12,
                         "xtick.labelsize": 11, "ytick.labelsize": 11, "legend.fontsize": 11,
                         "axes.unicode_minus": False, "pdf.fonttype": 42,
                         "axes.spines.top": False, "axes.spines.right": False,
                         "axes.edgecolor": "#9ca3af", "axes.linewidth": .7,
                         "figure.facecolor": "white", "axes.facecolor": "white",
                         "savefig.facecolor": "white"})


def footer(fig, text):
    fig.text(.5, .017, text, ha="center", va="bottom", fontsize=10, color="#555b65")


def overview(scores, title):
    fig, axes = plt.subplots(2, 3, figsize=(14.5, 8.7))
    for i, model in enumerate(LABELS):
        arrays = scores[model]
        n = len(arrays["IF"])
        ax = axes[i, 0]
        edges = np.arange(0, 101, 5)
        for algorithm, values in arrays.items():
            counts, _ = np.histogram(values, edges)
            ax.stairs(counts * 100 / n, edges, label=algorithm, color=COLORS[algorithm], linewidth=2)
        ax.axvline(95, color="#757575", linewidth=1, linestyle="--")
        ax.set(xlim=(0, 100), xlabel="AI 이상지수 (점)", ylabel="구간별 관측 비율 (%)",
               title=f"{LABELS[model]} · 점수 분포")
        ax.grid(axis="y", alpha=.15)
        ax.legend(frameon=False, loc="upper left")
        ax = axes[i, 1]
        counts = np.array([np.count_nonzero(v >= 95) for v in arrays.values()])
        rates = counts * 100 / n
        ax.bar(list(arrays), rates, color=list(COLORS.values()), width=.58)
        for j, (count, rate) in enumerate(zip(counts, rates)):
            ax.text(j, rate + max(rates) * .035, f"{rate:.2f}%\n{count:,}건", ha="center", va="bottom", fontsize=12)
        ax.set(ylim=(0, max(rates) * 1.35), ylabel="95점 이상 비율 (%)",
               title=f"95점 이상 · 비교 대상 {n:,}건")
        ax.grid(axis="y", alpha=.15)
        ax.set_axisbelow(True)
        ax = axes[i, 2]
        corr = np.array([[spearmanr(x, y).statistic for y in arrays.values()] for x in arrays.values()])
        image = ax.imshow(corr, vmin=-1, vmax=1, cmap="RdBu_r")
        ax.set(xticks=range(3), xticklabels=list(arrays), yticks=range(3), yticklabels=list(arrays),
               title="전체 점수 순위의 유사도")
        for r in range(3):
            for c in range(3):
                ax.text(c, r, f"{corr[r,c]:.3f}", ha="center", va="center", fontsize=15,
                        color="white" if abs(corr[r,c]) > .65 else "#222222")
        cb = fig.colorbar(image, ax=ax, fraction=.047, pad=.045, ticks=[-1, 0, 1])
        cb.set_label("Spearman 상관계수", fontsize=10)
    fig.suptitle(f"IF · LOF · 고정형 ECOD 비교  |  {title}", y=.982, fontsize=19)
    footer(fig, "같은 관측소·작업일과 모델별 적격 행을 비교 · 95점은 이상점수 비교 기준이며 지급 기준이 아님")
    fig.subplots_adjust(top=.88, bottom=.11, left=.065, right=.96, hspace=.53, wspace=.43)
    return fig


def pairwise(scores, title):
    fig, axes = plt.subplots(2, 3, figsize=(14.5, 8.6))
    pairs = (("IF", "LOF"), ("IF", "ECOD"), ("LOF", "ECOD"))
    collections = []
    for i, model in enumerate(LABELS):
        for j, (a, b) in enumerate(pairs):
            ax = axes[i, j]
            x, y = scores[model][a], scores[model][b]
            collection = ax.hexbin(x, y, gridsize=42, extent=(0, 100, 0, 100), mincnt=1,
                                   cmap="viridis", linewidths=0, rasterized=True)
            collections.append(collection)
            ax.plot([0, 100], [0, 100], color="#777777", linewidth=.8, linestyle=":")
            ax.axhline(95, color="#888888", linewidth=.7, linestyle="--")
            ax.axvline(95, color="#888888", linewidth=.7, linestyle="--")
            ax.set(xlim=(0, 100), ylim=(0, 100), xlabel=f"{a} 점수", ylabel=f"{b} 점수",
                   title=f"{LABELS[model]} · {a} / {b}", aspect="equal",
                   xticks=[0, 25, 50, 75, 100], yticks=[0, 25, 50, 75, 100])
            rho = spearmanr(x, y).statistic
            ax.text(.04, .96, f"순위 상관 {rho:.3f}  ·  n={len(x):,}", transform=ax.transAxes,
                    ha="left", va="top", fontsize=11,
                    bbox=dict(facecolor="white", edgecolor="none", alpha=.9, pad=3))
    norm = LogNorm(1, max(float(c.get_array().max()) for c in collections))
    for c in collections:
        c.set_norm(norm)
    fig.subplots_adjust(left=.06, right=.89, bottom=.11, top=.89, hspace=.42, wspace=.35)
    cax = fig.add_axes((.925, .2, .016, .60))
    fig.colorbar(collections[0], cax=cax).set_label("같은 구간의 관측 건수 (로그 눈금)", fontsize=11)
    fig.suptitle(f"같은 관측소·날짜에서 모델 점수가 얼마나 다른가  |  {title}", y=.98, fontsize=18)
    footer(fig, "대각선에 가까울수록 점수가 유사함 · 점선은 95점 기준 · 육각형 색은 점수 크기가 아닌 관측 건수")
    return fig


def weather_relationship(scores, weather, title):
    fig, axes = plt.subplots(3, 3, figsize=(14.5, 11))
    temperature_limits = (min(-12, float(np.floor(weather[FEATURES[0]].min()))),
                          max(42, float(np.ceil(weather[FEATURES[0]].max()))))
    definitions = [(FEATURES[0], "작업 중 최고기온 (℃)", temperature_limits),
                   (FEATURES[1], "작업 중 평균습도 (%)", (0, 100)),
                   (FEATURES[2], "체감온도 31℃ 이상 누적시간 (분)", (0, 540))]
    collections = []
    for r, (feature, label, limits) in enumerate(definitions):
        for c, algorithm in enumerate(FOLDERS):
            ax = axes[r, c]
            x, y = weather[feature], scores["AI2"][algorithm]
            collection = ax.hexbin(x, y, gridsize=42, mincnt=1, extent=(*limits, 0, 100),
                                   cmap="viridis", linewidths=0, rasterized=True)
            collections.append(collection)
            ax.axhline(95, color="#888888", linewidth=.7, linestyle="--")
            ax.set(xlim=limits, ylim=(0, 100), xlabel=label, ylabel=f"{algorithm} 온열 이상점수",
                   title=algorithm if r == 0 else None, yticks=[0, 25, 50, 75, 100])
    norm = LogNorm(1, max(float(c.get_array().max()) for c in collections))
    for c in collections:
        c.set_norm(norm)
    fig.subplots_adjust(left=.065, right=.89, bottom=.085, top=.90, hspace=.42, wspace=.32)
    cax = fig.add_axes((.925, .2, .016, .60))
    fig.colorbar(collections[0], cax=cax).set_label("같은 구간의 관측 건수 (로그 눈금)", fontsize=11)
    fig.suptitle(f"실제 기상값과 온열 AI2 점수  |  {title} · {len(weather[FEATURES[0]]):,}건", y=.975, fontsize=18)
    footer(fig, "평가자료 전체 관측을 표시 · 다른 기상변수를 고정한 인과효과가 아닌 관측된 관계 · 점선: 95점")
    return fig


def heat_duration(scores, weather, title):
    heat = weather[FEATURES[2]]
    masks = [heat == 0, (heat > 0) & (heat <= 120), (heat > 120) & (heat <= 300),
             (heat > 300) & (heat <= 480), (heat > 480) & (heat < 540), heat == 540]
    names = ["0", "10~120", "130~300", "310~480", "490~530", "540"]
    counts = [int(mask.sum()) for mask in masks]
    labels = [f"{name}\n(n={count:,})" for name, count in zip(names, counts)]
    fig, axes = plt.subplots(1, 2, figsize=(14.5, 5.5))
    x = np.arange(6)
    statistics = []
    for j, algorithm in enumerate(FOLDERS):
        values = scores["AI2"][algorithm]
        median = [float(np.median(values[mask])) if mask.any() else np.nan for mask in masks]
        rates = [float(np.mean(values[mask] >= 95) * 100) if mask.any() else np.nan for mask in masks]
        axes[0].plot(x, median, marker=("o", "s", "^")[j], color=COLORS[algorithm], label=algorithm, linewidth=2)
        bars = axes[1].bar(x + (j-1)*.25, rates, width=.23, color=COLORS[algorithm], label=algorithm)
        for bar, rate in zip(bars, rates):
            if np.isfinite(rate) and bar.get_x() > 4.5:
                axes[1].annotate(f"{rate:g}%", (bar.get_x()+bar.get_width()/2, rate),
                                 xytext=(0, 4), textcoords="offset points", ha="center", fontsize=10)
        for name, mask, count, med, rate in zip(names, masks, counts, median, rates):
            statistics.append(dict(heat_duration_bin=name, n=count, algorithm=algorithm, score_median=med,
                                   score_ge95_count=int(np.count_nonzero(values[mask] >= 95)), score_ge95_percent=rate))
    for ax in axes:
        ax.set(xticks=x, xticklabels=labels, ylim=(0, 110), xlabel="체감온도 31℃ 이상 누적시간 (분)")
        ax.grid(axis="y", alpha=.15)
        ax.set_axisbelow(True)
        ax.legend(frameon=False, loc="upper left")
    axes[0].set(ylabel="AI2 점수 중앙값", title="고온 지속시간 구간별 점수")
    axes[0].axhline(95, color="#777777", linewidth=.8, linestyle="--")
    axes[1].set(ylabel="95점 이상 비율 (%)", title="같은 기상자료에 대한 95점 이상 판정")
    fig.suptitle(f"고온이 오래 지속될 때 세 모델은 어떻게 반응하는가  |  {title}", y=.98, fontsize=18)
    fig.subplots_adjust(left=.06, right=.98, bottom=.22, top=.80, wspace=.22)
    footer(fig, "각 구간의 실제 관측 요약 · 540분은 작업시간 9시간 전체 · 95점은 비교용 이상점수 기준")
    return fig, statistics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--period", choices=("validation", "train", "after_validation"), default="validation")
    parser.add_argument("--output", type=Path, default=ROOT / "step04_comparison" / "visualizations")
    args = parser.parse_args()
    destination = args.output.resolve()
    allowed = (ROOT / "step04_comparison" / "visualizations").resolve()
    if destination != allowed and allowed not in destination.parents:
        raise ValueError("plots must stay inside the separate comparison/visualizations folder")
    if args.period != "validation":
        destination = destination / args.period
    scores, weather, protected, identities, total = load_data(args.period)
    configure_style()
    destination.mkdir(parents=True, exist_ok=True)
    title = {"validation": "평가기간 2024~2025", "train": "학습기간 2016~2023", "after_validation": "추가기간 2026"}[args.period]
    outputs = []
    with PdfPages(destination / "weather_model_comparison.pdf") as pdf:
        figures = [("01-model-overview", overview(scores, title)),
                   ("02-pairwise-scores", pairwise(scores, title)),
                   ("03-heat-weather-relationship", weather_relationship(scores, weather, title))]
        heat_figure, statistics = heat_duration(scores, weather, title)
        figures.append(("04-heat-duration-response", heat_figure))
        for name, fig in figures:
            png = destination / f"{name}.png"
            fig.savefig(png, dpi=180)
            pdf.savefig(fig, dpi=180)
            outputs.append(str(png))
            plt.close(fig)
    with (destination / "heat_duration_plot_values.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(statistics[0]))
        writer.writeheader()
        writer.writerows(statistics)
    for filename, checksum in protected.items():
        if sha(filename) != checksum:
            raise ValueError(f"source changed while plotting: {filename}")
    manifest = dict(period=args.period, target_rows=total, eligible_rows={m:len(v["IF"]) for m,v in scores.items()},
                    source_models=identities, protected_sources=protected, png_files=outputs,
                    pdf_file=str(destination / "weather_model_comparison.pdf"),
                    matplotlib_version=matplotlib.__version__, retraining=False,
                    notes=["All eligible period rows used; no random downsampling", "Hexbin colors count observations on a logarithmic scale",
                           "Anomaly ranks are not heat severity or cancellation probabilities", "95 is an illustrative comparison threshold, not a payout rule"])
    (destination / "visualization_metadata.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k:manifest[k] for k in ("period", "eligible_rows", "png_files", "pdf_file")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
