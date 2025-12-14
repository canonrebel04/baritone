#!/usr/bin/env python3

import argparse
import json
import math
import statistics
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Optional


def _as_float(v: Any) -> Optional[float]:
    try:
        if v is None:
            return None
        return float(v)
    except (TypeError, ValueError):
        return None


def _as_int(v: Any) -> Optional[int]:
    try:
        if v is None:
            return None
        return int(v)
    except (TypeError, ValueError):
        return None


def _pct(n: int, d: int) -> str:
    if d <= 0:
        return "-"
    return f"{(100.0 * n / d):.1f}%"


def _median(values: list[float]) -> Optional[float]:
    if not values:
        return None
    return statistics.median(values)


def _p95(values: list[float]) -> Optional[float]:
    if not values:
        return None
    values = sorted(values)
    # nearest-rank (inclusive)
    k = max(0, min(len(values) - 1, math.ceil(0.95 * len(values)) - 1))
    return values[k]


def _fmt_num(v: Optional[float], unit: str = "") -> str:
    if v is None or (isinstance(v, float) and (math.isnan(v) or math.isinf(v))):
        return "-"
    if abs(v) >= 100:
        return f"{v:.0f}{unit}"
    if abs(v) >= 10:
        return f"{v:.1f}{unit}"
    return f"{v:.2f}{unit}"


def _scenario_from_goal(goal_class: str) -> str:
    s = (goal_class or "").lower()
    if "follow" in s or "entity" in s:
        return "follow"
    if "goal" in s:
        return "goto"
    return "other"


@dataclass
class PathEnd:
    scenario: str
    segment: str
    result_type: str
    success: bool
    time_ms: Optional[float]
    nodes: Optional[int]
    path_len: Optional[int]
    path_cost: Optional[int]
    goal_class: str
    effective_goal_class: str
    simplified: Optional[bool]
    dimension: str
    creative: Optional[bool]
    path_attempt_id: str
    mark: str
    command: str
    command_variant: str
    player_on_ground: Optional[bool]
    player_flying: Optional[bool]
    start_walkable: Optional[bool]


@dataclass
class ElytraEnd:
    success: bool
    overshoot: Optional[bool]
    reason: str
    state_end: str
    lost_control_source: str
    lost_control_to: str
    time_ms: Optional[float]
    ticks: Optional[int]
    glide_ticks: Optional[int]
    min_dist: Optional[float]
    min_dist_xz: Optional[float]
    end_dist: Optional[float]
    end_dist_xz: Optional[float]
    avg_speed: Optional[float]
    max_speed: Optional[float]
    dimension: str
    creative: Optional[bool]
    mark: str


@dataclass
class ElytraLandingSelect:
    path_complete: Optional[bool]
    safety_landing: Optional[bool]
    landing_found: Optional[bool]
    search_origin: str
    player_dist_xz: Optional[float]
    last_to_dest_dist_xz: Optional[float]
    landing_to_dest_dist_xz: Optional[float]
    dimension: str
    creative: Optional[bool]
    mark: str


@dataclass
class Mark:
    label: str
    ts: Optional[int]
    dimension: str
    creative: Optional[bool]


@dataclass
class SessionStart:
    session_id: str
    ts: Optional[int]
    mc_version: str
    baritone_version: str


def _dim_creative_key(dimension: str, creative: Optional[bool]) -> str:
    c = "?" if creative is None else ("true" if creative else "false")
    dim = dimension or "-"
    return f"{dim} creative={c}"


def _iter_jsonl(path: Path) -> Iterable[dict[str, Any]]:
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for _line_no, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(obj, dict):
                yield obj


def _group_stats(values: list[float]) -> str:
    if not values:
        return "median=- p95=-"
    return (
        f"median={_fmt_num(_median(values), 'ms')} p95={_fmt_num(_p95(values), 'ms')}"
    )


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Summarize Baritone metrics.jsonl (pathing + elytra)."
    )
    ap.add_argument("file", type=Path, help="Path to metrics.jsonl")
    ap.add_argument(
        "--by-mark",
        action="store_true",
        help="Show per-mark breakdowns (label comes from the most recent mark in the same session).",
    )
    ap.add_argument(
        "--top",
        type=int,
        default=8,
        help="How many items to show in " "top-N breakdowns (default: 8).",
    )
    ap.add_argument(
        "--examples",
        type=int,
        default=0,
        help="Print up to N example failed path attempts per top failure result_type (0=off).",
    )
    args = ap.parse_args()

    path = args.file
    if not path.exists():
        raise SystemExit(f"File not found: {path}")

    counts: Counter[str] = Counter()
    path_ends: list[PathEnd] = []
    elytra_ends: list[ElytraEnd] = []
    elytra_landing_selects: list[ElytraLandingSelect] = []
    marks: list[Mark] = []
    session_starts: list[SessionStart] = []
    session_ids: set[str] = set()
    server_type_counts: Counter[str] = Counter()
    integrated_counts: Counter[str] = Counter()
    path_attempt_ids: set[str] = set()
    total_lines = 0

    # Mark segmentation is per-session. The active mark label is the latest mark event seen
    # for that session_id while iterating the JSONL in file order.
    active_mark_by_session: dict[str, str] = {}

    # Correlate path_end back to path_start via path_attempt_id so we can attribute failures
    # to commands like #goto and #follow.
    attempt_to_context: dict[str, dict[str, Any]] = {}

    # Legacy compatibility: an older bug overwrote the event "type" field inside path_end
    # with the PathCalculationResult.Type string (e.g. "SUCCESS_SEGMENT"). Detect those
    # by their shape and treat them as path_end.
    legacy_path_end_types = {
        "SUCCESS_TO_GOAL",
        "SUCCESS_SEGMENT",
        "FAILURE",
        "CANCELLATION",
        "EXCEPTION",
    }

    for obj in _iter_jsonl(path):
        total_lines += 1
        raw_type = str(obj.get("type", ""))

        sid = str(obj.get("session_id", ""))
        if sid:
            session_ids.add(sid)

        session_key = sid or "-"
        if session_key not in active_mark_by_session:
            active_mark_by_session[session_key] = "-"

        st = str(obj.get("server_type", ""))
        if st:
            server_type_counts[st] += 1
        if "is_integrated_server" in obj:
            integrated_counts[
                "true" if bool(obj.get("is_integrated_server")) else "false"
            ] += 1

        is_legacy_path_end = (
            raw_type in legacy_path_end_types
            and "id" in obj
            and "segment" in obj
            and "success" in obj
            and "time_ms" in obj
        )

        typ = "path_end" if is_legacy_path_end else raw_type
        counts[typ] += 1

        if typ == "session_start":
            # Start a fresh mark context for the new session.
            active_mark_by_session[session_key] = "-"
        elif typ == "mark":
            label = str(obj.get("label", "")) or "-"
            active_mark_by_session[session_key] = label

        active_mark = active_mark_by_session.get(session_key, "-")

        if typ == "path_start":
            attempt_id = str(obj.get("path_attempt_id", ""))
            if attempt_id and attempt_id != "-":
                cmd_obj = obj.get("command")
                cmd = "-"
                cmd_variant = "-"
                if isinstance(cmd_obj, dict):
                    cmd = str(cmd_obj.get("command", "")) or "-"
                    cmd_variant = str(cmd_obj.get("variant", "")) or "-"
                attempt_to_context[attempt_id] = {
                    "command": cmd,
                    "command_variant": cmd_variant,
                    "mark": active_mark,
                    "player_on_ground": (
                        obj.get("player_on_ground")
                        if isinstance(obj.get("player_on_ground"), bool)
                        else None
                    ),
                    "player_flying": (
                        obj.get("player_flying")
                        if isinstance(obj.get("player_flying"), bool)
                        else None
                    ),
                    "start_walkable": (
                        obj.get("start_walkable")
                        if isinstance(obj.get("start_walkable"), bool)
                        else None
                    ),
                }

        if typ == "path_end":
            goal_class = str(obj.get("goal_class", ""))
            scenario = _scenario_from_goal(goal_class)

            # In legacy logs, the result type was stored in the event 'type' field.
            result_type = str(obj.get("result_type", ""))
            if not result_type and is_legacy_path_end:
                result_type = raw_type
            if not result_type:
                result_type = "-"

            attempt_id = str(obj.get("path_attempt_id", "")) or "-"
            if attempt_id != "-":
                path_attempt_ids.add(attempt_id)

            cmd, cmd_variant, mark_label = ("-", "-", "-")
            player_on_ground: Optional[bool] = None
            player_flying: Optional[bool] = None
            start_walkable: Optional[bool] = None
            if attempt_id != "-" and attempt_id in attempt_to_context:
                ctx0 = attempt_to_context[attempt_id]
                cmd = str(ctx0.get("command", "-"))
                cmd_variant = str(ctx0.get("command_variant", "-"))
                mark_label = str(ctx0.get("mark", "-"))
                player_on_ground = ctx0.get("player_on_ground")
                player_flying = ctx0.get("player_flying")
                start_walkable = ctx0.get("start_walkable")
            else:
                mark_label = active_mark

            path_ends.append(
                PathEnd(
                    scenario=scenario,
                    segment=str(obj.get("segment", "")) or "-",
                    result_type=result_type,
                    success=bool(obj.get("success", False)),
                    time_ms=_as_float(obj.get("time_ms")),
                    nodes=_as_int(obj.get("nodes_considered")),
                    path_len=_as_int(obj.get("path_len")),
                    path_cost=_as_int(obj.get("path_cost_ticks")),
                    goal_class=goal_class,
                    effective_goal_class=str(obj.get("effective_goal_class", "")),
                    simplified=(
                        obj.get("goal_simplified")
                        if isinstance(obj.get("goal_simplified"), bool)
                        else None
                    ),
                    dimension=str(obj.get("dimension", "")) or "-",
                    creative=(
                        obj.get("creative")
                        if isinstance(obj.get("creative"), bool)
                        else None
                    ),
                    path_attempt_id=attempt_id,
                    mark=mark_label,
                    command=cmd,
                    command_variant=cmd_variant,
                    player_on_ground=player_on_ground,
                    player_flying=player_flying,
                    start_walkable=start_walkable,
                )
            )
        elif typ == "elytra_end":
            elytra_ends.append(
                ElytraEnd(
                    success=bool(obj.get("success", False)),
                    overshoot=(
                        obj.get("overshoot")
                        if isinstance(obj.get("overshoot"), bool)
                        else None
                    ),
                    reason=str(obj.get("reason", "")) or "-",
                    state_end=str(obj.get("state_end", "")) or "-",
                    lost_control_source=str(obj.get("lost_control_source", "")) or "-",
                    lost_control_to=str(obj.get("lost_control_to", "")) or "-",
                    time_ms=_as_float(obj.get("time_ms")),
                    ticks=_as_int(obj.get("ticks")),
                    glide_ticks=_as_int(obj.get("glide_ticks")),
                    min_dist=_as_float(obj.get("min_dist")),
                    min_dist_xz=_as_float(obj.get("min_dist_xz")),
                    end_dist=_as_float(obj.get("end_dist")),
                    end_dist_xz=_as_float(obj.get("end_dist_xz")),
                    avg_speed=_as_float(obj.get("avg_speed")),
                    max_speed=_as_float(obj.get("max_speed")),
                    dimension=str(obj.get("dimension", "")) or "-",
                    creative=(
                        obj.get("creative")
                        if isinstance(obj.get("creative"), bool)
                        else None
                    ),
                    mark=active_mark,
                )
            )
        elif typ == "elytra_landing_select":
            elytra_landing_selects.append(
                ElytraLandingSelect(
                    path_complete=(
                        obj.get("path_complete")
                        if isinstance(obj.get("path_complete"), bool)
                        else None
                    ),
                    safety_landing=(
                        obj.get("safety_landing")
                        if isinstance(obj.get("safety_landing"), bool)
                        else None
                    ),
                    landing_found=(
                        obj.get("landing_found")
                        if isinstance(obj.get("landing_found"), bool)
                        else None
                    ),
                    search_origin=str(obj.get("search_origin", "")) or "-",
                    player_dist_xz=_as_float(obj.get("player_dist_xz")),
                    last_to_dest_dist_xz=_as_float(obj.get("last_to_dest_dist_xz")),
                    landing_to_dest_dist_xz=_as_float(
                        obj.get("landing_to_dest_dist_xz")
                    ),
                    dimension=str(obj.get("dimension", "")) or "-",
                    creative=(
                        obj.get("creative")
                        if isinstance(obj.get("creative"), bool)
                        else None
                    ),
                    mark=active_mark,
                )
            )
        elif typ == "mark":
            marks.append(
                Mark(
                    label=str(obj.get("label", "")) or "-",
                    ts=_as_int(obj.get("ts")),
                    dimension=str(obj.get("dimension", "")) or "-",
                    creative=(
                        obj.get("creative")
                        if isinstance(obj.get("creative"), bool)
                        else None
                    ),
                )
            )
        elif typ == "session_start":
            session_starts.append(
                SessionStart(
                    session_id=str(obj.get("session_id", "")) or "-",
                    ts=_as_int(obj.get("ts")),
                    mc_version=str(obj.get("mc_version", "")) or "-",
                    baritone_version=str(obj.get("baritone_version", "")) or "-",
                )
            )

    print(f"File: {path}")
    print(f"Parsed events: {total_lines}")
    if session_ids:
        print(f"Distinct session_id: {len(session_ids)}")
    if server_type_counts:
        top = ", ".join(f"{k}={v}" for k, v in server_type_counts.most_common())
        print(f"Server type (observed): {top}")
    if integrated_counts:
        top = ", ".join(f"{k}={v}" for k, v in integrated_counts.most_common())
        print(f"Integrated server (observed): {top}")
    print()

    print("Event counts:")
    for k, v in counts.most_common():
        print(f"  {k}: {v}")
    print()

    if session_starts:
        print("Sessions (session_start):")
        print(f"  total={len(session_starts)}")
        recent = sorted(session_starts, key=lambda s: (s.ts or 0), reverse=True)[:5]
        print("  Most recent:")
        for s in recent:
            ts = "-" if s.ts is None else str(s.ts)
            print(
                f"    ts={ts}  session_id={s.session_id}  mc_version={s.mc_version}  baritone_version={s.baritone_version}"
            )
        print()

    if marks:
        print("Marks (mark):")
        total = len(marks)
        labels = [m.label for m in marks if m.label and m.label != "-"]
        top = Counter(labels)
        print(f"  total={total} distinct_labels={len(top)}")
        if top:
            print("  Top labels:")
            for lbl, cnt in top.most_common(12):
                print(f"    {cnt}  {lbl}")
        print()

    if path_ends:
        print("Pathing (path_end):")
        total = len(path_ends)
        ok = sum(1 for e in path_ends if e.success)
        times = [e.time_ms for e in path_ends if e.time_ms is not None]
        times_f = [float(t) for t in times if t is not None]
        extra = ""
        if path_attempt_ids:
            extra = f"  distinct_attempts={len(path_attempt_ids)}"
        print(
            f"  total={total} success={_pct(ok, total)} ({ok}/{total})  {_group_stats(times_f)}{extra}"
        )

        # scenario breakdown
        by_scenario: dict[str, list[PathEnd]] = defaultdict(list)
        for e in path_ends:
            by_scenario[e.scenario].append(e)
        for scenario in sorted(by_scenario.keys()):
            rows = by_scenario[scenario]
            total_s = len(rows)
            ok_s = sum(1 for e in rows if e.success)
            t = [e.time_ms for e in rows if e.time_ms is not None]
            t_f = [float(x) for x in t if x is not None]
            nodes = [e.nodes for e in rows if e.nodes is not None]
            nodes_f = [float(n) for n in nodes if n is not None]
            print(
                f"  {scenario}: n={total_s} success={_pct(ok_s, total_s)}  "
                f"time({ _group_stats(t_f) })  nodes(median={_fmt_num(_median(nodes_f))})"
            )
        print()

        # dimension/creative breakdown (helps segment creative tests and Nether runs)
        by_dim: dict[str, list[PathEnd]] = defaultdict(list)
        for e in path_ends:
            by_dim[_dim_creative_key(e.dimension, e.creative)].append(e)
        if len(by_dim) > 1:
            print("  By dimension/creative (path_end):")
            for key in sorted(by_dim.keys()):
                rows = by_dim[key]
                total_d = len(rows)
                ok_d = sum(1 for e in rows if e.success)
                t = [e.time_ms for e in rows if e.time_ms is not None]
                t_f = [float(x) for x in t if x is not None]
                print(
                    f"    {key}: n={total_d} success={_pct(ok_d, total_d)}  time({ _group_stats(t_f) })"
                )
            print()

        # top goal classes
        top_goals = Counter(e.goal_class for e in path_ends if e.goal_class)
        print("  Top goal_class (path_end):")
        for goal, cnt in top_goals.most_common(args.top):
            print(f"    {cnt}  {goal}")
        print()

        # result_type breakdown (especially useful for failures)
        top_result = Counter(
            e.result_type for e in path_ends if e.result_type and e.result_type != "-"
        )
        if top_result:
            print("  Top result_type (path_end):")
            for rt, cnt in top_result.most_common(args.top):
                print(f"    {cnt}  {rt}")
            print()

        fail_rows = [e for e in path_ends if not e.success]
        if fail_rows:
            top_fail = Counter(e.result_type for e in fail_rows if e.result_type)
            print("  Top result_type (failures):")
            for rt, cnt in top_fail.most_common(args.top):
                print(f"    {cnt}  {rt}")
            print()

            if args.examples > 0:
                print("  Examples (failures):")
                for rt, _cnt in top_fail.most_common(args.top):
                    shown = 0
                    seen_attempts: set[str] = set()
                    for e in fail_rows:
                        if e.result_type != rt:
                            continue
                        aid = e.path_attempt_id or "-"
                        if aid == "-" or aid in seen_attempts:
                            continue
                        seen_attempts.add(aid)
                        cmd = (
                            e.command
                            if e.command_variant == "-"
                            else f"{e.command}:{e.command_variant}"
                        )
                        ts = _fmt_num(e.time_ms, "ms") if e.time_ms is not None else "-"
                        fly = (
                            "?"
                            if e.player_flying is None
                            else ("true" if e.player_flying else "false")
                        )
                        on_ground = (
                            "?"
                            if e.player_on_ground is None
                            else ("true" if e.player_on_ground else "false")
                        )
                        walkable = (
                            "?"
                            if e.start_walkable is None
                            else ("true" if e.start_walkable else "false")
                        )
                        print(
                            f"    {rt}: attempt_id={aid}  mark={e.mark}  command={cmd}  dim={e.dimension}  segment={e.segment}  time={ts}  flying={fly}  on_ground={on_ground}  start_walkable={walkable}"
                        )
                        shown += 1
                        if shown >= args.examples:
                            break
                print()

        # command attribution (via path_attempt_id -> path_start.command)
        cmd_rows = [e for e in path_ends if e.command and e.command != "-"]
        if cmd_rows:
            by_cmd: dict[str, list[PathEnd]] = defaultdict(list)
            for e in cmd_rows:
                key = (
                    e.command
                    if e.command_variant == "-"
                    else f"{e.command}:{e.command_variant}"
                )
                by_cmd[key].append(e)
            print("  By command (path_end, correlated):")
            for key, rows in sorted(
                by_cmd.items(), key=lambda kv: len(kv[1]), reverse=True
            )[: args.top]:
                total_c = len(rows)
                ok_c = sum(1 for e in rows if e.success)
                t = [e.time_ms for e in rows if e.time_ms is not None]
                t_f = [float(x) for x in t if x is not None]
                print(
                    f"    {key}: n={total_c} success={_pct(ok_c, total_c)}  time({ _group_stats(t_f) })"
                )
            print()

        if args.by_mark:
            mark_rows = [e for e in path_ends if e.mark and e.mark != "-"]
            if mark_rows:
                by_mark: dict[str, list[PathEnd]] = defaultdict(list)
                for e in mark_rows:
                    by_mark[e.mark].append(e)
                print("  By mark label (path_end):")
                for key, rows in sorted(
                    by_mark.items(), key=lambda kv: len(kv[1]), reverse=True
                )[: max(args.top, 12)]:
                    total_m = len(rows)
                    ok_m = sum(1 for e in rows if e.success)
                    t = [e.time_ms for e in rows if e.time_ms is not None]
                    t_f = [float(x) for x in t if x is not None]
                    print(
                        f"    {key}: n={total_m} success={_pct(ok_m, total_m)}  time({ _group_stats(t_f) })"
                    )
                print()

    if elytra_ends:
        print("Elytra (elytra_end):")
        arrived_xz_threshold = 12.0
        total = len(elytra_ends)
        ok = sum(1 for e in elytra_ends if e.success)
        arrived_xz = sum(
            1
            for e in elytra_ends
            if e.min_dist_xz is not None and e.min_dist_xz <= arrived_xz_threshold
        )
        overs = sum(1 for e in elytra_ends if e.overshoot is True)
        times = [e.time_ms for e in elytra_ends if e.time_ms is not None]
        times_f = [float(t) for t in times if t is not None]
        ticks = [e.ticks for e in elytra_ends if e.ticks is not None]
        ticks_f = [float(t) for t in ticks if t is not None]
        print(
            f"  total={total} success={_pct(ok, total)} ({ok}/{total})  arrived_xz≤{arrived_xz_threshold:g}={_pct(arrived_xz, total)} ({arrived_xz}/{total})  overshoot={_pct(overs, total)} ({overs}/{total})\n"
            f"  time({ _group_stats(times_f) })  ticks(median={_fmt_num(_median(ticks_f))})"
        )

        # dimension/creative breakdown
        by_dim: dict[str, list[ElytraEnd]] = defaultdict(list)
        for e in elytra_ends:
            by_dim[_dim_creative_key(e.dimension, e.creative)].append(e)
        if len(by_dim) > 1:
            print("  By dimension/creative (elytra_end):")
            for key in sorted(by_dim.keys()):
                rows = by_dim[key]
                total_d = len(rows)
                ok_d = sum(1 for e in rows if e.success)
                overs_d = sum(1 for e in rows if e.overshoot is True)
                t = [e.time_ms for e in rows if e.time_ms is not None]
                t_f = [float(x) for x in t if x is not None]
                print(
                    f"    {key}: n={total_d} success={_pct(ok_d, total_d)}  overshoot={_pct(overs_d, total_d)}  time({ _group_stats(t_f) })"
                )

        # failure reason/state breakdown (if failures present)
        fails = [e for e in elytra_ends if not e.success]
        if fails:
            top_state = Counter(e.state_end for e in fails if e.state_end)
            top_reason = Counter(e.reason for e in fails if e.reason)
            top_lost_to = Counter(
                e.lost_control_to
                for e in fails
                if e.lost_control_to and e.lost_control_to != "-"
            )
            top_lost_src = Counter(
                e.lost_control_source
                for e in fails
                if e.lost_control_source and e.lost_control_source != "-"
            )
            print()
            print("  Top state_end (failures):")
            for state, cnt in top_state.most_common(args.top):
                print(f"    {cnt}  {state}")
            print("\n  Top reason (failures):")
            for reason, cnt in top_reason.most_common(args.top):
                print(f"    {cnt}  {reason}")
            if top_lost_src:
                print("\n  Top lost_control_source (failures):")
                for src, cnt in top_lost_src.most_common(args.top):
                    print(f"    {cnt}  {src}")
            if top_lost_to:
                print("\n  Top lost_control_to (failures):")
                for who, cnt in top_lost_to.most_common(args.top):
                    print(f"    {cnt}  {who}")

        if args.by_mark:
            mark_rows = [e for e in elytra_ends if e.mark and e.mark != "-"]
            if mark_rows:
                by_mark: dict[str, list[ElytraEnd]] = defaultdict(list)
                for e in mark_rows:
                    by_mark[e.mark].append(e)
                print()
                print("  By mark label (elytra_end):")
                for key, rows in sorted(
                    by_mark.items(), key=lambda kv: len(kv[1]), reverse=True
                )[: max(args.top, 12)]:
                    total_m = len(rows)
                    ok_m = sum(1 for e in rows if e.success)
                    arrived_xz_m = sum(
                        1
                        for e in rows
                        if e.min_dist_xz is not None
                        and e.min_dist_xz <= arrived_xz_threshold
                    )
                    overs_m = sum(1 for e in rows if e.overshoot is True)
                    t = [e.time_ms for e in rows if e.time_ms is not None]
                    t_f = [float(x) for x in t if x is not None]
                    print(
                        f"    {key}: n={total_m} success={_pct(ok_m, total_m)}  arrived_xz={_pct(arrived_xz_m, total_m)}  overshoot={_pct(overs_m, total_m)}  time({ _group_stats(t_f) })"
                    )

    if elytra_landing_selects:
        print()
        print("Elytra landing selection (elytra_landing_select):")
        total = len(elytra_landing_selects)
        found = sum(1 for e in elytra_landing_selects if e.landing_found is True)

        player_d = [
            e.player_dist_xz
            for e in elytra_landing_selects
            if e.player_dist_xz is not None
        ]
        last_d = [
            e.last_to_dest_dist_xz
            for e in elytra_landing_selects
            if e.last_to_dest_dist_xz is not None
        ]
        land_d = [
            e.landing_to_dest_dist_xz
            for e in elytra_landing_selects
            if e.landing_to_dest_dist_xz is not None
        ]

        print(
            f"  total={total} landing_found={_pct(found, total)} ({found}/{total})\n"
            f"  player_dist_xz(median={_fmt_num(_median(player_d))})  last_to_dest_dist_xz(median={_fmt_num(_median(last_d))})  landing_to_dest_dist_xz(median={_fmt_num(_median(land_d))})"
        )

        by_trigger: Counter[str] = Counter()
        for e in elytra_landing_selects:
            pc = (
                "?"
                if e.path_complete is None
                else ("true" if e.path_complete else "false")
            )
            sl = (
                "?"
                if e.safety_landing is None
                else ("true" if e.safety_landing else "false")
            )
            by_trigger[
                f"path_complete={pc} safety_landing={sl} origin={e.search_origin}"
            ] += 1
        if by_trigger:
            print("  Top triggers:")
            for key, cnt in by_trigger.most_common(args.top):
                print(f"    {cnt}  {key}")

        if args.by_mark:
            mark_rows = [e for e in elytra_landing_selects if e.mark and e.mark != "-"]
            if mark_rows:
                by_mark: dict[str, list[ElytraLandingSelect]] = defaultdict(list)
                for e in mark_rows:
                    by_mark[e.mark].append(e)
                print("  By mark label (elytra_landing_select):")
                for key, rows in sorted(
                    by_mark.items(), key=lambda kv: len(kv[1]), reverse=True
                )[: max(args.top, 12)]:
                    total_m = len(rows)
                    found_m = sum(1 for e in rows if e.landing_found is True)
                    player_d_m = [
                        e.player_dist_xz for e in rows if e.player_dist_xz is not None
                    ]
                    land_d_m = [
                        e.landing_to_dest_dist_xz
                        for e in rows
                        if e.landing_to_dest_dist_xz is not None
                    ]
                    print(
                        f"    {key}: n={total_m} landing_found={_pct(found_m, total_m)}  player_dist_xz(med={_fmt_num(_median(player_d_m))})  landing_to_dest_dist_xz(med={_fmt_num(_median(land_d_m))})"
                    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
