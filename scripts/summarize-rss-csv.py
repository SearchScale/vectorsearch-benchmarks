#!/usr/bin/env python3
"""Print min/median/max RSS (MiB) from mem-trace-solr-rss.sh CSV."""
import csv, statistics, sys

path = sys.argv[1]
rows = []
with open(path, newline="") as f:
    for row in csv.DictReader(f):
        if not row.get("rss_kib"):
            continue
        try:
            rows.append(float(row["rss_kib"]) / 1024.0)
        except ValueError:
            pass
if not rows:
    print("no RSS samples")
    sys.exit(0)
print(
    f"samples={len(rows)}  RSS_MiB min/median/max = "
    f"{min(rows):.0f} / {statistics.median(rows):.0f} / {max(rows):.0f}"
)
