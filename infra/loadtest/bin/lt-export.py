#!/usr/bin/env python3
"""Prometheus에서 회차 구간의 지표를 뽑아 CSV로 남긴다(외부 라이브러리 없음).

  ./bin/lt-export.py <testid>                                   # 회차 전체를 한 구간으로(앞 2분, 끝 1분 버림)
  ./bin/lt-export.py <testid> --step 5m30s --discard 1m30s      # 같은 길이 계단마다 한 줄(T2-WS conn/scan)
  ./bin/lt-export.py <testid> --bounds 7m,10m,10m,10m,10m       # 길이가 다른 구간(T4 stress)
  ./bin/lt-export.py <testid> --corebank                        # trading(corebank Prometheus)도 함께

시간 구간은 results/<testid>/meta.env 의 START/END(lt-run.sh · lt-fetch.sh가 쓴다)를 쓴다.
k6는 setup(로그인 수천 건)이 끝난 뒤에 부하를 걸므로, 구간의 기준 시각은 k6_vus가 처음 0보다 커진 시각이다
(--no-align이면 START 그대로).
먼저 Prometheus 포트포워드를 연다(런북 §3.4):
  kubectl --context lt-channel -n monitoring port-forward svc/ls-observe-prometheus 9090:9090
  kubectl --context lt-corebank -n monitoring port-forward svc/ls-observe-prometheus 9091:9090

결과:
  results/<testid>/series.csv  15초 간격 시계열(열 = 지표[라벨])
  results/<testid>/steps.csv   구간(계단)마다 정상 구간 평균 + job flow balance(서버 완료 / 발생기 송신)
"""
import argparse
import csv
import json
import os
import re
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.environ.get("LT_RESULTS", os.path.join(HERE, "..", "results"))

# 이름 -> PromQL. $ID는 testid. by(...)가 있으면 라벨마다 열이 하나씩 생긴다.
CHANNEL = {
    # 발생기(k6 remote write). Counter는 _total, Rate는 _rate, Trend는 _p95 등(K6_PROMETHEUS_RW_TREND_STATS)
    "k6_vus": 'sum(k6_vus{testid="$ID"})',
    # 발생기 유효성(계획서 §6.4): 실제 시작한 반복 수와, VU가 모자라 시작하지 못한 반복 수
    "k6_iter_per_s": 'sum(rate(k6_iterations_total{testid="$ID"}[1m]))',
    "k6_dropped_per_s": 'sum(rate(k6_dropped_iterations_total{testid="$ID"}[1m]))',
    "k6_req_per_s": 'sum(rate(k6_http_reqs_total{testid="$ID"}[1m]))',
    # 주문이 적으면 k6 시계열이 듬성듬성해 1m 창은 비므로 5m 창으로 본다(판정은 건수로 따로 한다)
    "k6_order_req_per_s": 'sum(rate(k6_http_reqs_total{testid="$ID",kind="order",name="POST /api/trading/orders"}[5m]))',
    "k6_req_per_s_by_kind": 'sum by (kind) (rate(k6_http_reqs_total{testid="$ID"}[1m]))',
    "k6_req_failed_rate": 'avg(k6_http_req_failed_rate{testid="$ID"})',
    "k6_p95_ms_by_kind": 'max by (kind) (k6_http_req_duration_p95{testid="$ID"})',
    # 지금 열린 연결 = 누적 열림 - 누적 닫힘. k6 카운터는 값이 바뀔 때만 오고(5분 뒤 stale), 닫힘은 끝날 때까지
    # 아예 없을 수 있어 last_over_time과 "or vector(0)"로 센다.
    "k6_ws_open": 'sum(last_over_time(k6_ws_opened_total{testid="$ID"}[2h])) - (sum(last_over_time(k6_ws_closed_total{testid="$ID"}[2h])) or vector(0))',
    "k6_ws_new_per_s": 'sum(rate(k6_ws_opened_total{testid="$ID"}[1m]))',
    "k6_ws_msg_per_s": 'sum(rate(k6_ws_msgs_total{testid="$ID"}[1m]))',
    "k6_ws_lag_p95_ms": 'max(k6_ws_quote_lag_ms_p95{testid="$ID"})',
    "k6_order_biz_reject_per_s": 'sum(rate(k6_order_biz_reject_total{testid="$ID"}[1m]))',
    "gen_cpu": 'sum(rate(container_cpu_usage_seconds_total{namespace="loadtest",container="k6"}[1m]))',
    "gen_cpu_per_pod_max": 'max(sum by (pod) (rate(container_cpu_usage_seconds_total{namespace="loadtest",container="k6"}[1m])))',
    "gen_mem_mib": 'sum(container_memory_working_set_bytes{namespace="loadtest",container="k6"}) / 1048576',
    # 서버(wts): 자원
    # WTS 파드는 컨테이너 이름이 모두 "app"이라 컨테이너로 묶으면 서비스가 구분되지 않는다. 파드 이름
    # (<서비스>-<ReplicaSet 해시>-<파드 해시>)에서 서비스 이름을 뽑아 svc 라벨로 묶는다.
    "cpu": 'sum by (svc) (label_replace(rate(container_cpu_usage_seconds_total{namespace="wts",container!="",container!="POD"}[1m]), "svc", "$1", "pod", "(.+)-[a-z0-9]+-[a-z0-9]+"))',
    "mem_mib": 'sum by (svc) (label_replace(container_memory_working_set_bytes{namespace="wts",container!="",container!="POD"}, "svc", "$1", "pod", "(.+)-[a-z0-9]+-[a-z0-9]+")) / 1048576',
    "cpu_per_pod_max": 'max by (svc) (label_replace(sum by (pod) (rate(container_cpu_usage_seconds_total{namespace="wts",container!="",container!="POD"}[1m])), "svc", "$1", "pod", "(.+)-[a-z0-9]+-[a-z0-9]+"))',
    "throttle_ratio": 'sum by (svc) (label_replace(rate(container_cpu_cfs_throttled_periods_total{namespace="wts",container!=""}[1m]), "svc", "$1", "pod", "(.+)-[a-z0-9]+-[a-z0-9]+")) / sum by (svc) (label_replace(rate(container_cpu_cfs_periods_total{namespace="wts",container!=""}[1m]), "svc", "$1", "pod", "(.+)-[a-z0-9]+-[a-z0-9]+"))',
    "replicas": 'sum by (deployment) (kube_deployment_status_replicas_available{namespace="wts"})',
    "node_cpu_ratio": '1 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m]))',
    # 서버(wts): RED, JVM, 풀. /actuator(헬스 프로브 · Prometheus 수집)는 사용자 요청이 아니라 뺀다.
    "server_rps": 'sum by (job) (rate(http_server_requests_seconds_count{namespace="wts",uri!~"/actuator.*"}[1m]))',
    "server_p95_s": 'histogram_quantile(0.95, sum by (le, job) (rate(http_server_requests_seconds_bucket{namespace="wts",uri!~"/actuator.*"}[1m])))',
    "server_5xx_per_s": 'sum by (job) (rate(http_server_requests_seconds_count{namespace="wts",uri!~"/actuator.*",status=~"5.."}[1m]))',
    "gc_pause_ratio": 'sum by (job) (rate(jvm_gc_pause_seconds_sum{namespace="wts"}[1m]))',
    "hikari_active": 'sum by (job) (hikaricp_connections_active{namespace="wts"})',
    "hikari_pending": 'sum by (job) (hikaricp_connections_pending{namespace="wts"})',
    # 서버 WS 지표(계획서 §6.5 #1, 앱에 들어간 뒤에만 값이 있다)
    "server_ws_sessions": 'sum by (job) (wts_ws_sessions{namespace="wts"})',
    "server_ws_sent_per_s": 'sum by (job) (rate(wts_ws_messages_sent_total{namespace="wts"}[1m]))',
}

COREBANK = {
    "cb_cpu": CHANNEL["cpu"],
    "cb_mem_mib": CHANNEL["mem_mib"],
    "cb_cpu_per_pod_max": CHANNEL["cpu_per_pod_max"],
    "cb_replicas": CHANNEL["replicas"],
    "cb_server_rps": CHANNEL["server_rps"],
    "cb_order_per_s": 'sum(rate(http_server_requests_seconds_count{namespace="wts",uri="/api/trading/orders",method="POST"}[1m]))',
    "cb_server_p95_s": CHANNEL["server_p95_s"],
    "cb_hikari_active": CHANNEL["hikari_active"],
    "cb_hikari_pending": CHANNEL["hikari_pending"],
    "cb_node_cpu_ratio": CHANNEL["node_cpu_ratio"],
}


KST = __import__("datetime").timezone(__import__("datetime").timedelta(hours=9))


def kst(ts):
    return __import__("datetime").datetime.fromtimestamp(ts, KST).strftime("%H:%M:%S")


# 정상 상태 판정용 카운트(계획서 §6.3). 구간 [lo, hi] 안의 "건수"를 양쪽에서 센다.
# - k6 쪽: k6는 태그 조합마다 시계열을 따로 보내고, 부하가 적으면 시계열마다 표본이 1~2개뿐이라 rate/increase로
#   셀 수 없다. k6 카운터는 테스트 시작부터의 누적값이므로 "hi 시점 누적 - lo 시점 누적"으로 센다.
# - 서버 쪽: Prometheus가 15s마다 긁는 연속 카운터라 increase(구간)로 센다.
K6_CUM = {
    "k6_http": 'sum(last_over_time(k6_http_reqs_total{testid="$ID"}[2h]))',
    "k6_ws_opened": 'sum(last_over_time(k6_ws_opened_total{testid="$ID"}[2h]))',
    "k6_order": 'sum(last_over_time(k6_http_reqs_total{testid="$ID",kind="order",name="POST /api/trading/orders"}[2h]))',
    "k6_ws_msgs": 'sum(last_over_time(k6_ws_msgs_total{testid="$ID"}[2h]))',
    "k6_iterations": 'sum(last_over_time(k6_iterations_total{testid="$ID"}[2h]))',
    "k6_dropped": 'sum(last_over_time(k6_dropped_iterations_total{testid="$ID"}[2h]))',
    # 페르소나별 세션 시작 수(계획서 §4.2 λᵢ와 비교). 듬성듬성한 카운터라 rate가 아니라 누적 차이로 센다.
    "sessions_checker": 'sum(last_over_time(k6_sessions_started_total{testid="$ID",persona="checker"}[2h]))',
    "sessions_watcher": 'sum(last_over_time(k6_sessions_started_total{testid="$ID",persona="watcher"}[2h]))',
    "sessions_trader": 'sum(last_over_time(k6_sessions_started_total{testid="$ID",persona="trader"}[2h]))',
    "sessions_daytrader": 'sum(last_over_time(k6_sessions_started_total{testid="$ID",persona="daytrader"}[2h]))',
}
SERVER_INC = {  # $W = 구간 길이(초)
    "gw_reqs": ("channel", 'sum(increase(http_server_requests_seconds_count{namespace="wts",job="gateway-service",uri!~"/actuator.*"}[$Ws]))'),
    "ws_sent": ("channel", 'sum(increase(wts_ws_messages_sent_total{namespace="wts",job="market-service"}[$Ws]))'),
    "cb_orders": ("corebank", 'sum(increase(http_server_requests_seconds_count{namespace="wts",uri="/api/trading/orders",method="POST"}[$Ws]))'),
}
MIN_COUNT = 100  # 이보다 적으면 한두 건 차이로 비율이 크게 흔들린다 → 판정하지 않는다
# 허용 오차: 비율 차이 TOL 이내 "또는" 건수 차이 ABS_SLACK 이내. 구간 경계(k6 5s 전송 · Prometheus 15s 수집)와
# 공유 환경의 다른 사용자 요청 때문에 몇 건은 늘 어긋난다(T0-20261020-2: 154 vs 158). 진짜 적체는 부하에 비례해 커진다.
TOL = {"flow_balance_gateway": 0.01, "flow_balance_order": 0.01, "flow_balance_ws": 0.05}
ABS_SLACK = 5
# 구간 경계 오차: k6는 5s마다 보내고 Prometheus는 15s마다 긁어, 양 끝에서 "처리율 × 몇 초"만큼 어긋난다.
# 고정 5건으로는 높은 처리율에서 정상 구간도 "아님"이 된다(T2-ORDER-20261020-r200: 초당 160건, 차이 438~913건).
EDGE_S = 7.5
PAIRS = {"flow_balance_gateway": ("n_gw_reqs", "n_k6_rest"), "flow_balance_order": ("n_cb_orders", "n_k6_order"),
         "flow_balance_ws": ("n_k6_ws_msgs", "n_ws_sent")}


# 발생기 유효성(계획서 §6.4): 발생기 파드 CPU가 요청(1.5코어)의 70%를 넘으면 k6가 메시지를 제때 읽지 못해
# 지연 · 처리량이 발생기 탓으로 왜곡된다(T2-WSCHURN-20261020-r20 · r50: 1.8~1.9코어, 지연 23초~3분).
GEN_CPU_LIMIT = float(os.environ.get("LT_GEN_CPU_LIMIT", "1.05"))


def steady_verdict(row):
    """'ok' · 'no' · '판정 불가'와 이유 목록."""
    reasons, judged = [], False
    g = row.get("gen_cpu_per_pod_max")
    if isinstance(g, float) and g > GEN_CPU_LIMIT:
        reasons.append(f"발생기 CPU {g:.2f}코어 > {GEN_CPU_LIMIT} → 무효(발생기를 늘려 다시)")
        judged = True
    for col, tol in TOL.items():
        v = row.get(col)
        if not isinstance(v, float):
            continue
        judged = True
        a, b = PAIRS[col]
        diff = abs((row.get(a) or 0.0) - (row.get(b) or 0.0))
        window = row.get("_window") or 0
        slack = max(ABS_SLACK, (row.get(b) or 0.0) / window * EDGE_S) if window else ABS_SLACK
        if abs(v - 1) > tol and diff > slack:
            reasons.append(f"{col} {v:.3f}(차이 {diff:.0f}건 > 허용 {slack:.0f}건)")
    dr = row.get("dropped_ratio")
    if isinstance(dr, float) and dr > 0:
        reasons.append(f"dropped {dr:.3f} → 무효(발생기 부족)")
        judged = True
    if not judged:
        return "판정 불가", reasons
    return ("no" if reasons else "ok"), reasons


def query_at(base, q, t):
    url = f"{base}/api/v1/query?" + urllib.parse.urlencode({"query": q, "time": t})
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            res = json.load(r)["data"]["result"]
    except Exception:  # noqa: BLE001
        return None
    return float(res[0]["value"][1]) if res else None


def dur(s):
    total = 0.0
    for n, u in re.findall(r"(\d+(?:\.\d+)?)(h|m|s)", s):
        total += float(n) * {"h": 3600, "m": 60, "s": 1}[u]
    return total


def read_meta(testid):
    path = os.path.join(RESULTS, testid, "meta.env")
    meta = {}
    with open(path) as f:
        for line in f:
            if "=" in line:
                k, v = line.rstrip("\n").split("=", 1)
                meta[k] = v.strip("'")
    return meta


def query_range(base, q, start, end, step):
    url = f"{base}/api/v1/query_range?" + urllib.parse.urlencode(
        {"query": q, "start": start, "end": end, "step": step}
    )
    with urllib.request.urlopen(url, timeout=60) as r:
        body = json.load(r)
    if body.get("status") != "success":
        raise RuntimeError(body)
    return body["data"]["result"]


# 값이 없을 때 흔한 이유. "값 없음"은 Prometheus 문제가 아니라 그 지표가 한 번도 생기지 않았다는 뜻이다.
EMPTY_REASON = {
    "k6_dropped_per_s": "도착률(open) 시나리오에서만 생긴다. 없으면 누락 0",
    "k6_order_req_per_s": "주문이 없거나 아주 적다(판정은 건수로 따로 한다)",
    "k6_order_biz_reject_per_s": "업무 거절(INSUFFICIENT_*) 0건",
    "throttle_ratio": "CPU limit이 없어 스로틀링 지표가 없다(계획서 §8.5, 정상)",
    "server_5xx_per_s": "서버 5xx 0건",
    "server_ws_sessions": "앱 WS 지표가 아직 없다(계획서 §6.5 #1, T2 전에 필요)",
    "server_ws_sent_per_s": "앱 WS 지표가 아직 없다(계획서 §6.5 #1, T2 전에 필요)",
    "k6_ws_lag_p95_ms": "WS 구독이 없거나 표본이 없다",
    "hikari_pending": "Hikari 대기 0",
    "cb_hikari_pending": "Hikari 대기 0",
}


def collect(base, queries, testid, start, end, step, table, columns):
    for name, q in queries.items():
        try:
            result = query_range(base, q.replace("$ID", testid), start, end, step)
        except Exception as e:  # noqa: BLE001 - 지표 하나가 없어도 나머지는 뽑는다
            print(f"  건너뜀 {name}: {e}", file=sys.stderr)
            continue
        if not result:
            print(f"  값 없음 {name}: {EMPTY_REASON.get(name, '이 구간에 이 지표가 한 번도 생기지 않았다')}", file=sys.stderr)
        for series in result:
            label = ",".join(f"{v}" for k, v in sorted(series["metric"].items()))
            col = f"{name}[{label}]" if label else name
            columns.append(col)
            for ts, v in series["values"]:
                try:
                    table.setdefault(int(float(ts)), {})[col] = float(v)
                except ValueError:
                    pass


def mean(values):
    vals = [v for v in values if v == v]  # NaN 제외
    return sum(vals) / len(vals) if vals else ""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("testid")
    ap.add_argument("--prom", default=os.environ.get("LT_PROM", "http://localhost:9090"))
    ap.add_argument("--prom-corebank", default=os.environ.get("LT_PROM_COREBANK", "http://localhost:9091"))
    ap.add_argument("--corebank", action="store_true", help="corebank Prometheus(trading)도 뽑는다")
    ap.add_argument("--start", type=int, help="UNIX 초. 없으면 meta.env START")
    ap.add_argument("--end", type=int, help="UNIX 초. 없으면 meta.env END")
    ap.add_argument("--resolution", default="15", help="초. 기본 15")
    ap.add_argument("--step", help="계단 길이(예: 5m30s). 없으면 전체를 한 구간으로")
    ap.add_argument("--bounds", help="구간 길이 목록(예: 7m,10m,10m). --step 대신")
    ap.add_argument("--no-align", action="store_true", help="k6 부하 시작 시각에 맞추지 않는다")
    ap.add_argument("--discard", default="2m", help="구간마다 앞에서 버릴 시간(워밍업 · 오토스케일). 기본 2m")
    ap.add_argument("--tail", default="1m", help="마지막 구간 끝에서 버릴 시간(램프다운). 기본 1m")
    ap.add_argument("--guard", default="30s", help="계단마다 끝에서 버릴 시간(다음 계단 램프업과 겹치지 않게). 기본 30s")
    args = ap.parse_args()

    meta = read_meta(args.testid)
    # --redo로 다시 돈 회차는 k6 지표에 attempt 태그가 있다. 그 회차의 지표만 고른다(첫 회차는 attempt 라벨이 없다).
    sel = f'testid="{args.testid}",attempt="{meta.get("ATTEMPT", "")}"'
    for d in (CHANNEL, K6_CUM):
        for k in d:
            d[k] = d[k].replace('testid="$ID"', sel)
    start = args.start or int(meta["START"])
    end = args.end or int(meta.get("END") or 0)
    if not end:
        sys.exit("END가 없다. lt-fetch.sh를 먼저 돌리거나 --end를 준다")

    # Prometheus에 붙는지 먼저 본다(포트포워드가 꺼져 있으면 지표 수십 개가 모두 실패한다).
    for name, base in [("channel", args.prom)] + ([("corebank", args.prom_corebank)] if args.corebank else []):
        try:
            urllib.request.urlopen(f"{base}/-/ready", timeout=5).read()
        except Exception as e:  # noqa: BLE001
            sys.exit(
                f"{name} Prometheus({base})에 붙을 수 없다: {e}\n"
                f"포트포워드를 연다: kubectl --context lt-{name} -n monitoring port-forward svc/ls-observe-prometheus "
                f"{base.rsplit(':', 1)[-1]}:9090"
            )

    table, columns = {}, []
    collect(args.prom, CHANNEL, args.testid, start, end, args.resolution, table, columns)
    if args.corebank:
        collect(args.prom_corebank, COREBANK, args.testid, start, end, args.resolution, table, columns)
    columns = list(dict.fromkeys(columns))

    out = os.path.join(RESULTS, args.testid)
    with open(os.path.join(out, "series.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["unix", "kst"] + columns)
        for ts in sorted(table):
            w.writerow([ts, kst(ts)] + [table[ts].get(c, "") for c in columns])

    # 부하 시작 시각: setup(로그인)이 끝나고 VU가 처음 생긴 시각
    if not args.no_align:
        vus = [t for t in sorted(table) if table[t].get("k6_vus", 0) > 0]
        if vus:
            print(f"부하 시작: START보다 {vus[0] - start}초 뒤(k6 setup 시간)")
            start = vus[0]

    # 구간 나누기: 계단마다 [시작+discard, 끝] 평균
    # 부하 시작 시각은 15초 해상도 표본에서 찾으므로 실제보다 최대 15초 늦다. 그래서 계단마다 끝에서 guard만큼
    # 버려 다음 계단의 램프업(연결 · 요청 급증)이 앞 계단 평균에 섞이지 않게 한다(T2-WSCONN-20261020-1에서 확인).
    discard, tail, guard = dur(args.discard), dur(args.tail), dur(args.guard)
    if args.bounds:
        bounds, s = [], start
        for d in args.bounds.split(","):
            bounds.append((s, min(end, s + dur(d))))
            s += dur(d)
    elif args.step:
        step = dur(args.step)
        bounds, s = [], start
        while s + step <= end + 1:
            bounds.append((s, s + step))
            s += step
    else:
        bounds = [(start, end)]
    rows = []
    for i, (a, b) in enumerate(bounds):
        lo, hi = a + discard, (b - tail if i == len(bounds) - 1 else b - guard)
        pts = [table[t] for t in sorted(table) if lo <= t <= hi]
        row = {"step": i, "from_kst": "", "to_kst": "", "points": len(pts)}
        if pts:
            row["from_kst"], row["to_kst"] = kst(lo), kst(hi)
        for c in columns:
            row[c] = mean([p[c] for p in pts if c in p])

        # 정상 상태 판정(계획서 §6.3). 같은 의미의 카운터끼리, 구간 안의 "건수"로 비교한다.
        cnt = {}
        for k, q in K6_CUM.items():
            q = q.replace("$ID", args.testid)
            a, b = query_at(args.prom, q, lo), query_at(args.prom, q, hi)
            cnt[k] = (b or 0.0) - (a or 0.0) if b is not None else None
        for k, (where, q) in SERVER_INC.items():
            if where == "corebank" and not args.corebank:
                cnt[k] = None
                continue
            base = args.prom if where == "channel" else args.prom_corebank
            cnt[k] = query_at(base, q.replace("$W", str(int(hi - lo))), hi)

        def balance(server, client):
            if cnt.get(server) is None or not cnt.get(client):
                return ""
            if cnt[client] < MIN_COUNT:
                return "표본 부족"
            return cnt[server] / cnt[client]

        k6_rest = (cnt["k6_http"] or 0.0) + (cnt["k6_ws_opened"] or 0.0) if cnt["k6_http"] is not None else None
        cnt["k6_rest"] = k6_rest  # 게이트웨이는 WS 핸드셰이크도 요청 1건으로 센다
        # REST: 게이트웨이가 끝낸 요청(/actuator 제외) / 발생기가 보낸 요청 + WS 핸드셰이크(k6는 재시도하지 않는다)
        # WS 위주 회차(HTTP가 거의 없음)는 비교하지 않는다. 게이트웨이는 WS 핸드셰이크를 연결이 끝날 때 기록하는
        # 것으로 보여, 짧은 연결이 많으면 구간 경계에서 수십~수백 건 어긋난다(T2-WSCHURN-20261020).
        if (cnt.get("k6_http") or 0.0) < MIN_COUNT:
            row["flow_balance_gateway"] = "WS 위주(해당 없음)" if (cnt.get("k6_ws_opened") or 0.0) > 0 else ""
        else:
            row["flow_balance_gateway"] = balance("gw_reqs", "k6_rest")
        # 주문: trading이 끝낸 POST /orders / k6가 보낸 주문(업무 거절 400도 양쪽에 똑같이 센다)
        row["flow_balance_order"] = balance("cb_orders", "k6_order")
        # WS: k6가 받은 메시지 / market-service가 보낸 메시지(서버 지표 wts.ws.messages.sent가 있을 때만)
        ws = balance("ws_sent", "k6_ws_msgs")
        row["flow_balance_ws"] = (1 / ws) if isinstance(ws, float) and ws > 0 else ws
        # 발생기 유효성: 시작하지 못한 반복 / 시작한 반복(0이어야 유효)
        it, dr = cnt.get("k6_iterations"), cnt.get("k6_dropped")
        row["dropped_ratio"] = (dr / it) if it and dr is not None else (0.0 if it else "")
        row.update({f"n_{k}": v for k, v in cnt.items() if v is not None})
        for k in [k for k in cnt if k.startswith("sessions_")]:
            if cnt[k] is not None and hi > lo:
                row[f"sessions_per_min[{k[9:]}]"] = cnt[k] / ((hi - lo) / 60)
        row["_window"] = hi - lo
        row["steady"], row["_reasons"] = steady_verdict(row)
        rows.append(row)
    ncols = sorted({k for r in rows for k in r if k.startswith(("n_", "sessions_per_min"))})
    fields = (
        ["step", "from_kst", "to_kst", "points", "steady", "flow_balance_gateway", "flow_balance_order", "flow_balance_ws", "dropped_ratio"]
        + ncols
        + columns
    )
    with open(os.path.join(out, "steps.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)

    print(f"{out}/series.csv  ({len(table)}개 시각, {len(columns)}개 열)")
    print(f"{out}/steps.csv   ({len(rows)}개 구간)")
    fmt = lambda v: f"{v:.3f}" if isinstance(v, float) else (v or "-")  # noqa: E731
    for r in rows:
        fb, fo, fw, dr = r["flow_balance_gateway"], r["flow_balance_order"], r["flow_balance_ws"], r["dropped_ratio"]
        print(
            f"  구간 {r['step']} 건수: k6 요청+WS {r.get('n_k6_rest', '-'):.0f} / 게이트웨이 {r.get('n_gw_reqs') or 0:.0f}, "
            f"k6 주문 {r.get('n_k6_order') or 0:.0f} / trading 주문 {r.get('n_cb_orders') or 0:.0f}"
            if isinstance(r.get("n_k6_rest"), float)
            else f"  구간 {r['step']} 건수: k6 지표 없음"
        )
        if not r["points"]:
            verdict = "데이터 없음(구간 · 포트포워드 · testid 확인) → 판정 불가"
        elif r["steady"] == "ok":
            verdict = "정상 상태"
        elif r["steady"] == "no":
            verdict = "정상 상태 아님: " + " · ".join(r["_reasons"])
        else:
            verdict = "판정 불가(표본 부족 · 지표 없음)"
        print(
            f"  구간 {r['step']}: {r['from_kst']}~{r['to_kst']} REST {fmt(fb)} 주문 {fmt(fo)} WS {fmt(fw)} "
            f"dropped {fmt(dr)}  → {verdict}"
        )
    print(
        f"허용 오차(계획서 §6.3): 비율 차이 REST · 주문 1%, WS 5% 이내, 또는 건수 차이가 max({ABS_SLACK}건, 처리율 × {EDGE_S}s) 이내. dropped 0. 발생기 CPU {GEN_CPU_LIMIT}코어 이하. "
        f"구간 건수가 {MIN_COUNT}건 미만이면 '표본 부족'(판정하지 않음)"
    )


if __name__ == "__main__":
    main()
