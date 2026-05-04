#!/usr/bin/env bash
# Index 1M then 2M docs (500k javabin batches from wiki-10m base.10M.fbin), skip queries,
# sample Solr JVM RSS into CSVs. Requires wiki-1m-500kbatches / wiki-2m-500kbatches (see prepare-wiki-javabin-subset.sh).
#
# Env (same as solr-benchmarks.sh): SOLR_TGZ_PATH, CUVS_NATIVE_LIB_PATH, SOLR_OPTS, SOLR_HEAP, RAM_BUFFER_SIZE_MB
#
# Usage: ./scripts/run-mem-compare-1m-vs-2m.sh
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO"
mkdir -p "$REPO/tmp"

export SKIP_QUERIES=true
export NPARALLEL="${NPARALLEL:-1}"
CONFIG="$REPO/configs/wiki10m/cagra_hnsw-mem-smoke-ef800.json"

run_one() {
  local label="$1"
  local batches="$2"
  local trace="$REPO/tmp/mem-rss-${label}.csv"
  echo "========== $label docs ($batches) =========="
  rm -f "$trace"
  # Poll until Solr appears, then keep sampling (stop when benchmark process exits is manual — use long window)
  (
    echo "ts_epoch,rss_kib,vsz_kib,pid" >"$trace"
    end=$(( $(date +%s) + 7200 ))
    while [[ $(date +%s) -lt $end ]]; do
      now=$(date +%s)
      pid=$(pgrep -f 'org\.apache\.solr\.solrj\.StartSolrJetty|start\.jar' 2>/dev/null | head -1 || true)
      rss=""; vsz=""
      if [[ -n "${pid:-}" ]] && [[ -r "/proc/$pid/status" ]]; then
        rss=$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status")
        vsz=$(awk '/^VmSize:/ {print $2}' "/proc/$pid/status")
      fi
      echo "$now,$rss,$vsz,$pid" >>"$trace"
      sleep 1
    done
  ) &
  local samp=$!
  trap 'kill '"$samp"' 2>/dev/null || true' EXIT
  sleep 1
  "$REPO/solr-benchmarks.sh" "$CONFIG" "$batches" \
    'http://localhost:8983/solr/test/update?commit=true&overwrite=false' \
    "$REPO/tmp/mem-results-${label}"
  kill "$samp" 2>/dev/null || true
  wait "$samp" 2>/dev/null || true
  trap - EXIT
  echo "--- RSS summary (${label}) ---"
  python3 "$REPO/scripts/summarize-rss-csv.py" "$trace" || true
}

[[ -d "$REPO/wiki-1m-500kbatches" ]] || { echo "Run: ./scripts/prepare-wiki-javabin-subset.sh 1000000 wiki-1m-500kbatches"; exit 1; }
[[ -d "$REPO/wiki-2m-500kbatches" ]] || { echo "Run: ./scripts/prepare-wiki-javabin-subset.sh 2000000 wiki-2m-500kbatches"; exit 1; }

run_one 1m wiki-1m-500kbatches
sleep 3
run_one 2m wiki-2m-500kbatches

echo ""
echo "CSVs: tmp/mem-rss-1m.csv tmp/mem-rss-2m.csv"
echo "Peak RSS is approximate (1 Hz); compare max column via summarize script or a spreadsheet."
