#!/usr/bin/env bash
# Sample GPU MiB and Solr JVM RSS (KiB) during indexing + KNN search for cuvsQuantization none vs scalar.
# For ~2M vectors (wiki-2m-500kbatches) use mem-profile-cuvs-quant-2m.sh instead (streaming curl -T, solrconfig aligned with solr-benchmarks.sh).
# Usage:
#   ./mem-profile-cuvs-quant.sh [num_batches]
# Env:
#   SOLR_URL (default http://localhost:8983)
#   REPO     (default: parent dir containing wiki-all-1m_batches)

set -euo pipefail

NUM_BATCHES="${1:-5}"
SOLR_URL="${SOLR_URL:-http://localhost:8983}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"
BATCHES_DIR="$REPO/wiki-all-1m_batches"
STAMP="$(date +%Y%m%d_%H%M%S)"
OUT_DIR="$REPO/tmp/mem-profile-$STAMP"
DIM=768

mkdir -p "$OUT_DIR"

solr_pid() {
  pgrep -af -- 'Dsolr.solr.home' | grep -v grep | awk '{print $1}' | head -1
}

sample_loop() {
  local out_csv="$1"
  local pid="$2"
  echo "ts_epoch,gpu_mem_mib,gpu_util_pct,rss_kb" >"$out_csv"
  while true; do
    ts=$(date +%s)
    gmu=$(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits 2>/dev/null | tr -d ' ' || echo "")
    gut=$(nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>/dev/null | tr -d ' ' || echo "")
    rss=""
    if [[ -n "$pid" ]] && [[ -r "/proc/$pid/status" ]]; then
      rss=$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status")
    fi
    echo "$ts,$gmu,$gut,$rss" >>"$out_csv"
    sleep 0.5
  done
}

summarize_csv() {
  local csv="$1"
  python3 - "$csv" <<'PY'
import sys, csv, statistics as st
path = sys.argv[1]
rows = []
with open(path) as f:
    r = csv.DictReader(f)
    for row in r:
        try:
            rows.append({
                "gpu": float(row["gpu_mem_mib"] or 0),
                "rss": float(row["rss_kb"] or 0) / 1024,
            })
        except ValueError:
            pass
if not rows:
    print("(no rows)")
    sys.exit(0)
g = [x["gpu"] for x in rows]
m = [x["rss"] for x in rows]
print(f"  samples={len(rows)}  gpu_MiB min/med/max={min(g):.0f}/{st.median(g):.0f}/{max(g):.0f}  RSS_MiB min/med/max={min(m):.0f}/{st.median(m):.0f}/{max(m):.0f}")
PY
}

make_configset() {
  local tmp="$1"
  local quant="$2"
  mkdir -p "$tmp"
  cat >"$tmp/solrconfig.xml" <<'EOF'
<?xml version="1.0" ?>
<config>
  <luceneMatchVersion>10.3.2</luceneMatchVersion>
  <dataDir>${solr.data.dir:}</dataDir>
  <directoryFactory name="DirectoryFactory" class="${solr.directoryFactory:solr.NRTCachingDirectoryFactory}"/>
  <updateHandler class="solr.DirectUpdateHandler2">
    <updateLog><str name="dir">${solr.ulog.dir:}</str></updateLog>
  </updateHandler>
  <codecFactory name="CuVSCodecFactory" class="org.apache.solr.cuvs.CuVSCodecFactory"/>
  <requestHandler name="/select" class="solr.SearchHandler"/>
  <requestHandler name="/update" class="solr.UpdateRequestHandler"/>
</config>
EOF
  # shellcheck disable=SC2086
  cat >"$tmp/managed-schema" <<EOF
<?xml version="1.0" ?>
<schema name="memprof" version="1.7">
  <fieldType name="string" class="solr.StrField" multiValued="false"/>
  <fieldType name="knn_vector" class="solr.DenseVectorField"
             vectorDimension="$DIM"
             knnAlgorithm="cagra_hnsw"
             similarityFunction="euclidean"
             cuvsWriterThreads="8"
             cuvsIntGraphDegree="64"
             cuvsGraphDegree="32"
             cuvsHnswLayers="1"
             cuvsHnswM="16"
             cuvsHnswEfConstruction="100"
             cuvsQuantization="$quant"/>
  <fieldType name="plong" class="solr.LongPointField" useDocValuesAsStored="false"/>
  <field name="id" type="string" indexed="true" stored="true" required="true"/>
  <field name="article_vector" type="knn_vector" indexed="true" stored="false"/>
  <field name="_version_" type="plong" indexed="true" stored="true" multiValued="false"/>
  <uniqueKey>id</uniqueKey>
</schema>
EOF
}

run_variant() {
  local quant="$1"
  local coll="memprof_${quant}_$$"
  local cfg="${coll}_cfg"
  local tmpcfg="$OUT_DIR/cfg_$quant"
  local pid
  pid="$(solr_pid)"
  if [[ -z "$pid" ]]; then
    echo "ERROR: Could not find Solr Java PID (Dsolr.solr.home)."
    exit 1
  fi

  echo ""
  echo "========== variant: cuvsQuantization=$quant =========="

  make_configset "$tmpcfg" "$quant"
  curl -sS "$SOLR_URL/solr/admin/collections?action=DELETE&name=$coll" >/dev/null 2>&1 || true
  curl -sS "$SOLR_URL/solr/admin/configs?action=DELETE&name=$cfg" >/dev/null 2>&1 || true
  (cd "$tmpcfg" && zip -qr - *) | curl -sS -X POST --header "Content-Type:application/octet-stream" \
    --data-binary @- "$SOLR_URL/solr/admin/configs?action=UPLOAD&name=$cfg" | python3 -c "import sys,json; r=json.load(sys.stdin); assert r['responseHeader']['status']==0, r"
  CRE=$(curl -sS "$SOLR_URL/solr/admin/collections?action=CREATE&name=$coll&numShards=1&collection.configName=$cfg")
  python3 -c "import sys,json; r=json.loads(sys.argv[1]); assert r['responseHeader']['status']==0, r" "$CRE"
  echo "Collection $coll created."

  # Baseline snapshot
  nvidia-smi --query-gpu=memory.used,memory.total,utilization.gpu --format=csv >"$OUT_DIR/baseline_${quant}.txt"
  echo "PID=$pid" >"$OUT_DIR/pid_${quant}.txt"

  echo "--- indexing ($NUM_BATCHES batches) ---"
  local idx_csv="$OUT_DIR/index_${quant}.csv"
  sample_loop "$idx_csv" "$pid" &
  local sampler_pid=$!
  sleep 1
  local i=0
  for f in $(ls -1 "$BATCHES_DIR"/batch.* | sort -V | head -n "$NUM_BATCHES"); do
    R=$(curl -sS -X POST -H "Content-Type:application/javabin" --data-binary "@$f" \
      "$SOLR_URL/solr/$coll/update?commit=true&overwrite=false")
    python3 -c "import sys,json; r=json.loads(sys.argv[1]); assert r['responseHeader']['status']==0, r" "$R"
    i=$((i + 1))
    echo "  batch $i/$NUM_BATCHES OK $(basename "$f")"
  done
  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true

  local nf
  nf=$(curl -sS "$SOLR_URL/solr/$coll/select?q=*:*&rows=0&wt=json" | python3 -c "import sys,json; print(json.load(sys.stdin)['response']['numFound'])")
  echo "numFound=$nf"
  du -sb "$SOLR_HOME/${coll}_shard1_replica_n1/data/index" 2>/dev/null | awk '{print "index_bytes_on_disk", $1}' || true

  echo "--- search (warm KNN) ---"
  local q_csv="$OUT_DIR/search_${quant}.csv"
  sample_loop "$q_csv" "$pid" &
  sampler_pid=$!
  sleep 0.5
  (cd "$REPO" && python3 run_queries.py \
    --ef-search 100 \
    --warmup-queries 20 \
    --num-queries 60 \
    --query-file "$REPO/datasets/wiki-all-1m/wiki_all_1M/queries.fbin" \
    --neighbors-file "$REPO/datasets/wiki-all-1m/wiki_all_1M/groundtruth.1M.neighbors.ibin" \
    --solr-url "$SOLR_URL" \
    --collection "$coll" \
    --vector-field article_vector \
    --top-k 10 \
    --output-file "$OUT_DIR/queries_${quant}.json")
  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true

  echo "Index phase:"
  summarize_csv "$idx_csv"
  echo "Search phase:"
  summarize_csv "$q_csv"

  # On-disk breakdown by extension
  python3 - "$coll" "$OUT_DIR" "$quant" <<'PY'
import json, os, sys
from pathlib import Path

coll = sys.argv[1]
out = Path(sys.argv[2])
quant = sys.argv[3]
base = Path(os.environ.get("SOLR_HOME", ""))
if not base:
    print("SOLR_HOME not set; skip on-disk extension breakdown")
    sys.exit(0)
# Guess shard path
candidates = list(base.glob(f"{coll}_shard1_replica_n1/data/index"))
if not candidates:
    print(f"No index dir glob for {coll}")
    sys.exit(0)
idx = candidates[0]
ext_tot = {}
for p in idx.rglob("*"):
    if p.is_file():
        suf = p.suffix or "(noext)"
        ext_tot[suf] = ext_tot.get(suf, 0) + p.stat().st_size
lines = sorted(ext_tot.items(), key=lambda x: -x[1])
report = {"quant": quant, "index_dir": str(idx), "by_suffix_bytes": {k: v for k, v in lines}}
(out / f"disk_breakdown_{quant}.json").write_text(json.dumps(report, indent=2))
print(f"Wrote disk_breakdown_{quant}.json")
top = lines[:6]
for suf, b in top:
    print(f"  {suf}: {b/1024/1024:.1f} MiB")
PY

  echo "Cleanup collection $coll"
  curl -sS "$SOLR_URL/solr/admin/collections?action=DELETE&name=$coll" >/dev/null 2>&1 || true
  curl -sS "$SOLR_URL/solr/admin/configs?action=DELETE&name=$cfg" >/dev/null 2>&1 || true
}

if [[ ! -d "$BATCHES_DIR" ]]; then
  echo "Missing $BATCHES_DIR"
  exit 1
fi

# solr.home parent for shard path guess in Python
if [[ -z "${SOLR_HOME:-}" ]]; then
  SOLR_HOME="$(curl -sf "$SOLR_URL/solr/admin/info/system?wt=json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('solr_home',''))" 2>/dev/null || true)"
  export SOLR_HOME
fi
echo "Output: $OUT_DIR"
echo "Solr PID will be detected automatically; SOLR_HOME=$SOLR_HOME"
echo "Batches: $NUM_BATCHES (~$(( NUM_BATCHES * 100000 )) docs, 100k per batch)"

run_variant none
sleep 3
run_variant scalar

echo ""
echo "========== DONE =========="
echo "Artifacts under: $OUT_DIR"
ls -la "$OUT_DIR"
