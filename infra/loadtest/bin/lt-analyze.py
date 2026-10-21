#!/usr/bin/env python3
"""T2 단위 비용 회귀(계획서 §7.4 T2, §8 분석 방법). 외부 라이브러리 없음.

부하(x)에 대해 자원 사용(y)을 직선 y = 기울기·x + 절편 으로 맞춘다.
  기울기 = 부하 1단위당 비용(= service demand), 절편 = 유휴 비용.
회귀 유효성 규칙(계획서 §8.2) — 하나라도 어기면 그 자원의 기울기를 단위 비용으로 쓰지 않는다:
  R² ≥ 0.9, 최대 잔차 ≤ 10%(예측 대비), 같은 부하를 반복한 점들의 변동계수(CV) ≤ 10%.
  무릎 = 처음 3점으로 맞춘 직선에서 20% 넘게 벗어나기 시작한 점. 무릎 뒤 점은 비선형 구간이라
  기울기 계산에서 뺀다(--fit-until로 직접 정할 수도 있다).

  # 한 회차 안의 계단(T2-WS conn/scan): steps.csv 하나
  ./bin/lt-analyze.py results/T2-WSCONN-20261021-1/steps.csv --x k6_ws_open

  # 값마다 따로 돌린 회차(T2-ORDER의 RATE 10, 25, 50...), 반복 회차(-r50a, -r50b)까지 한꺼번에
  ./bin/lt-analyze.py results/T2-ORDER-20261021-r*/steps.csv --x k6_req_per_s --y '^cpu\\[' '^cb_cpu\\['

--y는 열 이름의 정규식(여러 개). 기본은 cpu[...] · cb_cpu[...] · mem_mib[...] 열 전부.
단위: cpu는 코어(1.0 = 1000m). 결과 표는 결과 문서에 그대로 붙인다.
"""
import argparse
import csv
import re
import sys
from collections import defaultdict

R2_MIN, RESID_MAX, CV_MAX = 0.9, 0.10, 0.10


def flt(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def load(paths):
    rows = []
    for p in paths:
        with open(p) as f:
            for r in csv.DictReader(f):
                bad = []
                if r.get("steady") == "no":  # lt-export.py의 판정(비율 또는 건수 차이 허용 오차, dropped)
                    bad.append("lt-export 판정: 정상 상태 아님")
                elif "steady" not in r:  # 예전 steps.csv
                    for col, lo, hi in (("flow_balance_gateway", 0.99, 1.01), ("flow_balance_order", 0.99, 1.01)):
                        v = flt(r.get(col))
                        if v is not None and not lo <= v <= hi:
                            bad.append(f"{col} {v:.3f}")
                    d = flt(r.get("dropped_ratio"))
                    if d:
                        bad.append(f"dropped {d:.3f}")
                if bad:
                    print(f"  회귀에서 제외({', '.join(bad)} → 정상 상태 아님 · 무효, 용량 한계 후보): {p} 구간 {r['step']}", file=sys.stderr)
                    continue
                r["_src"] = p
                rows.append(r)
    return rows


def fit(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    icpt = my - slope * mx
    ss_tot = sum((y - my) ** 2 for y in ys)
    ss_res = sum((y - (slope * x + icpt)) ** 2 for x, y in zip(xs, ys))
    r2 = 1 - ss_res / ss_tot if ss_tot > 0 else 1.0
    resid = max((abs(y - (slope * x + icpt)) / abs(slope * x + icpt) for x, y in zip(xs, ys) if slope * x + icpt), default=0.0)
    return slope, icpt, r2, resid


def knee(xs, ys):
    if len(xs) < 4:
        return None
    base = fit(xs[:3], ys[:3])
    if not base:
        return None
    s, b = base[0], base[1]
    for x, y in zip(xs[3:], ys[3:]):
        pred = s * x + b
        if pred > 0 and abs(y - pred) / pred > 0.2:
            return x
    return None


def max_cv(pairs):
    """같은 부하(x를 반올림해 묶음)에서 반복 측정한 y의 변동계수 중 최댓값. 반복이 없으면 None."""
    groups = defaultdict(list)
    for x, y in pairs:
        groups[round(x, -1) if x >= 10 else round(x, 1)].append(y)
    cvs = []
    for ys in groups.values():
        if len(ys) >= 2:
            m = sum(ys) / len(ys)
            sd = (sum((y - m) ** 2 for y in ys) / (len(ys) - 1)) ** 0.5
            if m:
                cvs.append(sd / abs(m))
    return max(cvs) if cvs else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("steps", nargs="+", help="steps.csv 파일들")
    ap.add_argument("--x", required=True, help="부하 열 이름(예: k6_req_per_s, k6_ws_open, k6_ws_msg_per_s)")
    ap.add_argument("--y", nargs="*", default=[r"^cpu\[", r"^cb_cpu\[", r"^mem_mib\["], help="자원 열 정규식")
    ap.add_argument("--fit-until", type=float, help="이 x까지만 직선에 맞춘다(무릎 뒤 비선형 구간 제외)")
    args = ap.parse_args()

    rows = load(args.steps)
    if not rows:
        sys.exit("쓸 수 있는 구간이 없다")
    cols = [c for c in rows[0] if any(re.search(p, c) for p in args.y)]
    pts = [r for r in rows if flt(r.get(args.x)) is not None]
    pts.sort(key=lambda r: float(r[args.x]))
    print(f"x = {args.x}, 점 {len(pts)}개: " + ", ".join(f"{float(r[args.x]):.1f}" for r in pts))
    print()
    print("| 자원 | 기울기(x 1단위당) | 절편(유휴) | R² | 최대 잔차 | 반복 CV | 무릎(x) | 적용 구간(x) | 판정 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for c in cols:
        pairs = [(float(r[args.x]), float(r[c])) for r in pts if flt(r.get(c)) is not None]
        if len(pairs) < 2:
            continue
        xs, ys = [p[0] for p in pairs], [p[1] for p in pairs]
        k = knee(xs, ys)
        limit = args.fit_until if args.fit_until is not None else k
        fitted = [(x, y) for x, y in pairs if limit is None or x < limit] or pairs
        f = fit([p[0] for p in fitted], [p[1] for p in fitted])
        if not f:
            continue
        s, b, r2, resid = f
        cv = max_cv(pairs)
        problems = []
        if r2 < R2_MIN:
            problems.append(f"R²<{R2_MIN}")
        if resid > RESID_MAX:
            problems.append(f"잔차>{RESID_MAX:.0%}")
        if cv is not None and cv > CV_MAX:
            problems.append(f"CV>{CV_MAX:.0%}")
        if len(fitted) < 3:
            problems.append("점<3")
        verdict = "사용 가능" if not problems else "보류(" + ", ".join(problems) + ")"
        span = f"{fitted[0][0]:.1f}~{fitted[-1][0]:.1f}"
        cv_s = "-" if cv is None else f"{cv:.1%}"
        print(
            f"| {c} | {s:.6g} | {b:.4g} | {r2:.3f} | {resid:.1%} | {cv_s} | "
            f"{'' if k is None else f'{k:.1f}'} | {span} | {verdict} |"
        )
    print()
    print("읽는 법: cpu 열이면 기울기 0.002 = 부하 1단위당 2m CPU. 예) x=주문/s → 주문 1건/s당 2mCPU.")
    print("기울기는 '적용 구간' 안에서만 쓴다. 그 밖으로 늘려 예측하지 않는다(계획서 §8.2). 반복 CV는 같은 부하를 2번 이상 돈 경우에만 나온다.")


if __name__ == "__main__":
    main()
