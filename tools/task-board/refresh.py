#!/usr/bin/env python3
"""Regenerates the derived parts of a task board from the per-track tables.

Two sections of the board are not facts but consequences of the per-track tables: the progress
counts and the "ready to take" list. Kept by hand they drift apart the moment a task is closed —
which is exactly what happened after C1 and C2 landed, leaving seven newly unblocked tasks
invisible.

Run this after every status change:

    python3 tools/task-board/refresh.py

It rewrites the board in place and exits non-zero when it finds an inconsistency the tables
cannot express away — an unknown dependency, a duplicated id, or an "unblocks" entry that the
other task does not list as a dependency.
"""

import argparse
import re
import sys
from pathlib import Path

DEFAULT_BOARD = Path(__file__).resolve().parents[2] / "docs/features-backlog/2026-09-18-okx-tasks.md"

TASK_ROW = re.compile(
    r"^\| \*\*([A-F]\d+)\*\* \| (P\d) \| (\*\*finished\*\*|`open`|`wip`|`blocked`) \| "
    r"([^|]+?) \| ([^|]*?) \| ([^|]*?) \|$",
    re.M,
)
PRIORITIES = ["P0", "P1", "P2", "P3"]
STATUSES = ["finished", "open", "wip", "blocked"]


class Task:
    def __init__(self, id_, priority, status, title, depends_on, unblocks):
        self.id = id_
        self.priority = priority
        self.status = "finished" if "finished" in status else status.strip("`")
        self.title = title.strip()
        self.depends_on = split_ids(depends_on)
        self.unblocks = split_ids(unblocks)
        self.unblocks_raw = unblocks.strip() or "—"


def split_ids(cell):
    cell = cell.strip()
    if not cell or cell == "—":
        return []
    return [part.strip() for part in cell.split(",") if part.strip() and part.strip() != "—"]


def parse(text):
    tasks = {}
    for row in TASK_ROW.finditer(text):
        task = Task(*row.groups())
        if task.id in tasks:
            fail(f"duplicated task id [{task.id}]")
        tasks[task.id] = task
    if not tasks:
        fail("no task rows found - has the table format changed?")
    return tasks


def validate(tasks):
    problems = []
    for task in tasks.values():
        for dep in task.depends_on:
            if dep not in tasks:
                problems.append(f"[{task.id}] depends on unknown task [{dep}]")
        for unblocked in task.unblocks:
            if unblocked not in tasks:
                problems.append(f"[{task.id}] claims to unblock unknown task [{unblocked}]")
            elif task.id not in tasks[unblocked].depends_on:
                problems.append(
                    f"[{task.id}] claims to unblock [{unblocked}], "
                    f"but [{unblocked}] does not depend on it"
                )
        if task.status not in STATUSES:
            problems.append(f"[{task.id}] has unknown status [{task.status}]")
    return problems


def progress_table(tasks):
    counts = {status: {p: 0 for p in PRIORITIES} for status in ("finished", "open")}
    for task in tasks.values():
        bucket = "finished" if task.status == "finished" else "open"
        counts[bucket][task.priority] += 1

    lines = ["| | P0 | P1 | P2 | P3 | razem |", "|---|---|---|---|---|---|"]
    for bucket, label in (("finished", "`finished`"), ("open", "`open`")):
        row = counts[bucket]
        lines.append(
            "| {} | {} | {} | {} | {} | **{}** |".format(
                label, *(row[p] for p in PRIORITIES), sum(row.values())
            )
        )
    totals = [counts["finished"][p] + counts["open"][p] for p in PRIORITIES]
    lines.append("| **razem** | {} | {} | {} | {} | **{}** |".format(*totals, sum(totals)))
    return "\n".join(lines)


def ready_table(tasks):
    done = {t.id for t in tasks.values() if t.status == "finished"}
    ready = [
        t for t in tasks.values()
        if t.status != "finished" and all(dep in done for dep in t.depends_on)
    ]
    ready.sort(key=lambda t: (PRIORITIES.index(t.priority), t.id))

    lines = ["| # | prio | zadanie | odblokowuje |", "|---|---|---|---|"]
    for task in ready:
        lines.append(f"| **{task.id}** | {task.priority} | {task.title} | {task.unblocks_raw} |")
    return "\n".join(lines)


def replace_section(text, heading, next_heading, body):
    start = text.index(heading) + len(heading)
    end = text.index(next_heading)
    head, _, _ = text[start:end].partition("|")
    return text[:start] + head + body + "\n\n" + text[end:]


def fail(message):
    print(f"task-board: {message}", file=sys.stderr)
    sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("board", nargs="?", type=Path, default=DEFAULT_BOARD)
    parser.add_argument("--check", action="store_true",
                        help="fail instead of writing when the board is out of date")
    args = parser.parse_args()

    text = args.board.read_text(encoding="utf-8")
    tasks = parse(text)

    problems = validate(tasks)
    if problems:
        for problem in problems:
            print(f"task-board: {problem}", file=sys.stderr)
        sys.exit(1)

    updated = replace_section(text, "## Postęp\n", "## Gotowe do wzięcia", progress_table(tasks))
    updated = replace_section(updated, "## Gotowe do wzięcia\n", "## Zadania według ścieżek",
                              ready_table(tasks))

    if updated == text:
        print(f"task-board: up to date ({len(tasks)} tasks)")
        return
    if args.check:
        fail("out of date - run without --check to regenerate")
    args.board.write_text(updated, encoding="utf-8")
    done = sum(1 for t in tasks.values() if t.status == "finished")
    print(f"task-board: regenerated - {done}/{len(tasks)} finished")


if __name__ == "__main__":
    main()
