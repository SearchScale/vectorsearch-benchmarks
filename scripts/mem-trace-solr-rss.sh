#!/usr/bin/env bash
# Sample Solr JVM resident set size (RSS) to a CSV. Stop with SIGINT or when --duration seconds elapse.
# Usage:
#   ./mem-trace-solr-rss.sh out.csv [--duration 3600] [--interval 0.5]
# Finds PID via pgrep on org.apache.solr (start.jar); override with SOLR_PID=12345.
set -euo pipefail

OUT="${1:?output csv path}"
shift || true
DURATION=""
INTERVAL="0.5"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration) DURATION="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

solr_pid() {
  if [[ -n "${SOLR_PID:-}" ]]; then
    echo "$SOLR_PID"
    return
  fi
  # Prefer Solr main process
  pgrep -f 'org\.apache\.solr\.solrj\.StartSolrJetty|start\.jar' 2>/dev/null | head -1 || true
}

echo "ts_epoch,rss_kib,vsz_kib,pid" >"$OUT"
start_ts=$(date +%s)
end_ts=""
[[ -n "$DURATION" ]] && end_ts=$((start_ts + DURATION))

while true; do
  now=$(date +%s)
  if [[ -n "$end_ts" ]] && [[ "$now" -ge "$end_ts" ]]; then
    break
  fi
  pid="$(solr_pid)"
  rss=""; vsz=""
  if [[ -n "$pid" ]] && [[ -r "/proc/$pid/status" ]]; then
    rss=$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status")
    vsz=$(awk '/^VmSize:/ {print $2}' "/proc/$pid/status")
  fi
  echo "$now,$rss,$vsz,$pid" >>"$OUT"
  sleep "$INTERVAL"
done
