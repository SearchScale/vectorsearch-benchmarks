#!/usr/bin/env bash
# None vs scalar (cuvsQuantization) while indexing ~2M vectors (500k x 4 batches from wiki-10m/base.10M.fbin).
# Mirrors mem-profile-cuvs-quant.sh for 1M, but:
#   - uses wiki-2m-500kbatches (run scripts/prepare-wiki-javabin-subset.sh 2000000 wiki-2m-500kbatches)
#   - streams javabin with curl -T (large batches)
#   - solrconfig/schema aligned with solr-benchmarks.sh CAGRA + cagra_hnsw-mem-smoke graph params
#
# Prereq: Solr 11 + cuVS already running (SolrCloud), same as for mem-profile-cuvs-quant.sh
#
# Usage:
#   ./scripts/mem-profile-cuvs-quant-2m.sh
# Env:
#   SOLR_URL (default http://localhost:8983)
#   REPO      benchmark repo root (default: parent of scripts/)
#   BATCHES_DIR (default wiki-2m-500kbatches under REPO)
#   NUM_BATCHES (default 4 = full 2M)
#   SKIP_SEARCH=true  — indexing + RSS only (no run_queries.py)
#   RAM_BUFFER_SIZE_MB (default 4096) — substituted into generated solrconfig
#   CUVS_WRITER_THREADS, INT_GRAPH_DEGREE, GRAPH_DEGREE, HNSW_LAYERS, MAX_CONN, BEAM_WIDTH — codec + field defaults
#
# Large-batch uploads (curl 56 / reset by peer):
#   COMMIT_EACH_BATCH=false (default) — commit=false on intermediate batches, commit=true only on the last batch (less fsync load).
#   COMMIT_EACH_BATCH=true — each batch uses commit=true (like solr-benchmarks.sh).
#   CURL_CONNECT_TIMEOUT (default 600), CURL_MAX_TIME (default 14400) — per-batch wall-clock cap for curl.
#   BATCH_UPLOAD_RETRIES (default 3), BATCH_RETRY_SLEEP_SEC (default 15) — retry on transfer/HTTP failures.
#   DEBUG_CURL=true — adds curl -v (messages end up in WARN curl stderr / temp capture).
#   CURL_EXTRA_ARGS — extra words for curl (space-separated, e.g. -k ).

set -euo pipefail

SOLR_URL="${SOLR_URL:-http://localhost:8983}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"
BATCHES_DIR="${BATCHES_DIR:-$REPO/wiki-2m-500kbatches}"
NUM_BATCHES="${NUM_BATCHES:-4}"
SKIP_SEARCH="${SKIP_SEARCH:-false}"
DIM=768

RAM_BUFFER_SIZE_MB="${RAM_BUFFER_SIZE_MB:-4096}"
CUVS_WRITER_THREADS="${CUVS_WRITER_THREADS:-8}"
INT_GRAPH_DEGREE="${INT_GRAPH_DEGREE:-32}"
GRAPH_DEGREE="${GRAPH_DEGREE:-64}"
HNSW_LAYERS="${HNSW_LAYERS:-1}"
MAX_CONN="${MAX_CONN:-16}"
BEAM_WIDTH="${BEAM_WIDTH:-100}"
COMMIT_EACH_BATCH="${COMMIT_EACH_BATCH:-false}"
CURL_CONNECT_TIMEOUT="${CURL_CONNECT_TIMEOUT:-600}"
CURL_MAX_TIME="${CURL_MAX_TIME:-14400}"
BATCH_UPLOAD_RETRIES="${BATCH_UPLOAD_RETRIES:-3}"
BATCH_RETRY_SLEEP_SEC="${BATCH_RETRY_SLEEP_SEC:-15}"
DEBUG_CURL="${DEBUG_CURL:-false}"

STAMP="$(date +%Y%m%d_%H%M%S)"
OUT_DIR="$REPO/tmp/mem-profile-2m-quant-$STAMP"
mkdir -p "$OUT_DIR"

QUERY_FILE="${QUERY_FILE:-$REPO/datasets/wiki-10m/queries.fbin}"
NEIGHBORS_FILE="${NEIGHBORS_FILE:-$REPO/datasets/wiki-10m/groundtruth.10M.neighbors.ibin}"

# Solr JVM pid (same idea as mem-profile-cuvs-quant.sh, with Jetty fallback)
_solr_java_pid() {
  local p
  p=$(pgrep -af 'Dsolr\.solr\.home' 2>/dev/null | grep -v grep | awk '{print $1}' | head -1 || true)
  if [[ -n "$p" ]]; then
    echo "$p"
    return
  fi
  p=$(pgrep -f 'org\.apache\.solr\.solrj\.StartSolrJetty' 2>/dev/null | head -1 || true)
  if [[ -n "$p" ]]; then
    echo "$p"
    return
  fi
  pgrep -f 'start\.jar' 2>/dev/null | head -1 || true
}

sample_loop_pid_refresh() {
  local out_csv="$1"
  echo "ts_epoch,gpu_mem_mib,gpu_util_pct,rss_kb,pid" >"$out_csv"
  while true; do
    ts=$(date +%s)
    gmu=$(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits 2>/dev/null | tr -d ' ' || echo "")
    gut=$(nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>/dev/null | tr -d ' ' || echo "")
    pid="$(_solr_java_pid)"
    rss=""
    if [[ -n "$pid" ]] && [[ -r "/proc/$pid/status" ]]; then
      rss=$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status")
    fi
    echo "$ts,$gmu,$gut,$rss,$pid" >>"$out_csv"
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
            rk = row.get("rss_kb") or row.get("rss")
            if rk is None or str(rk).strip() == "":
                continue
            rows.append({
                "gpu": float(row.get("gpu_mem_mib") or 0),
                "rss": float(rk) / 1024,
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

emit_solrconfig() {
  local dest="$1"
  cat >"$dest" <<EOF
<?xml version="1.0" ?>
<config>
    <luceneMatchVersion>10.0.0</luceneMatchVersion>
    <dataDir>\${solr.data.dir:}</dataDir>
    <directoryFactory name="DirectoryFactory" class="\${solr.directoryFactory:solr.NRTCachingDirectoryFactory}"/>
    <indexConfig>
        <ramBufferSizeMB>$RAM_BUFFER_SIZE_MB</ramBufferSizeMB>
        <maxBufferedDocs>-1</maxBufferedDocs>
        <useCompoundFile>false</useCompoundFile>
        <mergePolicyFactory class="org.apache.solr.index.NoMergePolicyFactory" />
    </indexConfig>
    <updateHandler class="solr.DirectUpdateHandler2">
        <autoCommit>
            <maxTime>\${solr.autoCommit.maxTime:1500000}</maxTime>
            <openSearcher>false</openSearcher>
        </autoCommit>
        <autoSoftCommit>
            <maxTime>\${solr.autoSoftCommit.maxTime:1500000}</maxTime>
        </autoSoftCommit>
    </updateHandler>
    <codecFactory name="CuVSCodecFactory" class="org.apache.solr.cuvs.CuVSCodecFactory">
        <str name="cuvsWriterThreads">$CUVS_WRITER_THREADS</str>
        <str name="intGraphDegree">$INT_GRAPH_DEGREE</str>
        <str name="graphDegree">$GRAPH_DEGREE</str>
        <str name="hnswLayers">$HNSW_LAYERS</str>
        <str name="maxConn">$MAX_CONN</str>
        <str name="beamWidth">$BEAM_WIDTH</str>
    </codecFactory>
    <requestHandler name="/select" class="solr.SearchHandler">
        <lst name="defaults">
            <str name="echoParams">explicit</str>
            <int name="rows">10</int>
        </lst>
    </requestHandler>
    <requestHandler name="/update" class="solr.UpdateRequestHandler" />
</config>
EOF
}

make_configset() {
  local tmp="$1"
  local quant="$2"
  mkdir -p "$tmp"
  emit_solrconfig "$tmp/solrconfig.xml"
  cat >"$tmp/managed-schema" <<EOF
<?xml version="1.0" ?>
<schema name="memprof-2m" version="1.7">
  <fieldType name="string" class="solr.StrField" multiValued="false"/>
  <fieldType name="knn_vector" class="solr.DenseVectorField"
             vectorDimension="$DIM"
             knnAlgorithm="cagra_hnsw"
             similarityFunction="euclidean"
             cuvsQuantization="$quant"/>
  <fieldType name="plong" class="solr.LongPointField" useDocValuesAsStored="false"/>
  <field name="id" type="string" indexed="true" stored="true" required="true"/>
  <field name="article_vector" type="knn_vector" indexed="true" stored="false"/>
  <field name="_version_" type="plong" indexed="true" stored="true" multiValued="false"/>
  <uniqueKey>id</uniqueKey>
</schema>
EOF
}

# POST one javabin file; optional commit. Retries on curl failure or Solr status != 0.
# Does not use curl -f so we always capture Solr error bodies. Disables Expect: 100-continue (large uploads).
post_javabin() {
  local coll="$1"
  local file="$2"
  local want_commit="$3"
  local url="${SOLR_URL}/solr/${coll}/update?overwrite=false"
  if [[ "$want_commit" == "true" ]]; then
    url="${url}&commit=true"
  else
    url="${url}&commit=false"
  fi
  local attempt=1
  local body=""
  local http_code=""
  local curl_ec=0
  local resp_file=""
  local cerr_file=""
  local -a curl_cmd=()
  while [[ "$attempt" -le "$BATCH_UPLOAD_RETRIES" ]]; do
    resp_file="$(mktemp)"
    cerr_file="${resp_file}.err"
    curl_cmd=(curl -sS --connect-timeout "$CURL_CONNECT_TIMEOUT" --max-time "$CURL_MAX_TIME"
      -X POST
      -H 'Expect:'
      -H "Content-Type:application/javabin"
      -T "$file"
      -o "$resp_file"
      -w '%{http_code}'
    )
    if [[ "$DEBUG_CURL" == "true" ]]; then
      curl_cmd+=(-v)
    fi
    if [[ -n "${CURL_EXTRA_ARGS:-}" ]]; then
      # shellcheck disable=SC2206
      curl_cmd+=($CURL_EXTRA_ARGS)
    fi
    curl_cmd+=("$url")

    curl_ec=0
    http_code="$("${curl_cmd[@]}" 2>"$cerr_file")" || curl_ec=$?

    body="$(cat "$resp_file" 2>/dev/null || true)"
    local cerr=""
    cerr="$(cat "$cerr_file" 2>/dev/null || true)"
    rm -f "$resp_file" "$cerr_file"

    if [[ "$curl_ec" -eq 0 ]] && [[ "$http_code" == "200" ]] && [[ -n "$body" ]]; then
      if python3 -c "import json,sys; r=json.loads(sys.argv[1]); sys.exit(0 if r.get('responseHeader',{}).get('status')==0 else 1)" "$body" 2>/dev/null; then
        echo "$body"
        return 0
      fi
    fi

    echo "  WARN upload attempt $attempt/$BATCH_UPLOAD_RETRIES  curl_ec=${curl_ec} http=${http_code:-?} body_bytes=${#body}" >&2
    if [[ -n "$body" ]]; then
      echo "  WARN response snippet: ${body:0:1200}" >&2
    fi
    if [[ -n "$cerr" ]]; then
      echo "  WARN curl stderr: ${cerr:0:800}" >&2
    fi
    if [[ "$attempt" -lt "$BATCH_UPLOAD_RETRIES" ]]; then
      echo "  ... retry in ${BATCH_RETRY_SLEEP_SEC}s" >&2
      sleep "$BATCH_RETRY_SLEEP_SEC"
    fi
    attempt=$((attempt + 1))
  done
  return 1
}

run_variant() {
  local quant="$1"
  local coll="memprof2m_${quant}_$$"
  local cfg="${coll}_cfg"
  local tmpcfg="$OUT_DIR/cfg_$quant"

  echo ""
  echo "========== cuvsQuantization=$quant (2M docs, $NUM_BATCHES batches) =========="

  make_configset "$tmpcfg" "$quant"
  curl -sS "$SOLR_URL/solr/admin/collections?action=DELETE&name=$coll" >/dev/null 2>&1 || true
  curl -sS "$SOLR_URL/solr/admin/configs?action=DELETE&name=$cfg" >/dev/null 2>&1 || true
  (cd "$tmpcfg" && zip -qr - *) | curl -sS -X POST --header "Content-Type:application/octet-stream" \
    --data-binary @- "$SOLR_URL/solr/admin/configs?action=UPLOAD&name=$cfg" | python3 -c "import sys,json; r=json.load(sys.stdin); assert r['responseHeader']['status']==0, r"
  CRE=$(curl -sS "$SOLR_URL/solr/admin/collections?action=CREATE&name=$coll&numShards=1&collection.configName=$cfg")
  python3 -c "import sys,json; r=json.loads(sys.argv[1]); assert r['responseHeader']['status']==0, r" "$CRE"
  echo "Collection $coll created."

  nvidia-smi --query-gpu=memory.used,memory.total,utilization.gpu --format=csv >"$OUT_DIR/baseline_${quant}.txt" 2>/dev/null || true

  echo "--- indexing (COMMIT_EACH_BATCH=$COMMIT_EACH_BATCH) ---"
  local idx_csv="$OUT_DIR/index_${quant}.csv"
  sample_loop_pid_refresh "$idx_csv" &
  local sampler_pid=$!
  sleep 1
  local i=0
  while IFS= read -r -d '' f; do
    i=$((i + 1))
    [[ "$i" -gt "$NUM_BATCHES" ]] && break
    local cmt="false"
    if [[ "$COMMIT_EACH_BATCH" == "true" ]] || [[ "$i" -eq "$NUM_BATCHES" ]]; then
      cmt="true"
    fi
    R=$(post_javabin "$coll" "$f" "$cmt") || {
      echo "ERROR: batch upload failed after retries: $(basename "$f")" >&2
      kill "$sampler_pid" 2>/dev/null || true
      return 1
    }
    python3 -c "import sys,json; r=json.loads(sys.argv[1]); assert r['responseHeader']['status']==0, r" "$R"
    echo "  batch $i/$NUM_BATCHES OK $(basename "$f") commit=$cmt"
  done < <(find "$BATCHES_DIR" -maxdepth 1 -name 'batch.*' -print0 | sort -z -V)
  kill "$sampler_pid" 2>/dev/null || true
  wait "$sampler_pid" 2>/dev/null || true

  nf=$(curl -sS "$SOLR_URL/solr/$coll/select?q=*:*&rows=0&wt=json" | python3 -c "import sys,json; print(json.load(sys.stdin)['response']['numFound'])")
  echo "numFound=$nf"
  if [[ -n "${SOLR_HOME:-}" ]]; then
    du -sb "$SOLR_HOME/${coll}_shard1_replica_n1/data/index" 2>/dev/null | awk '{print "index_bytes_on_disk", $1}' || true
  fi

  if [[ "$SKIP_SEARCH" != "true" ]] && [[ -f "$QUERY_FILE" ]] && [[ -f "$NEIGHBORS_FILE" ]]; then
    echo "--- search (queries from wiki-10m; recall vs 10M GT — interpret qualitatively at 2M index) ---"
    local q_csv="$OUT_DIR/search_${quant}.csv"
    sample_loop_pid_refresh "$q_csv" &
    sampler_pid=$!
    sleep 0.5
    (cd "$REPO" && python3 run_queries.py \
      --ef-search 100 \
      --warmup-queries 20 \
      --num-queries 60 \
      --query-file "$QUERY_FILE" \
      --neighbors-file "$NEIGHBORS_FILE" \
      --solr-url "$SOLR_URL" \
      --collection "$coll" \
      --vector-field article_vector \
      --top-k 10 \
      --output-file "$OUT_DIR/queries_${quant}.json")
    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true
    echo "Search phase:"
    summarize_csv "$q_csv"
  else
    echo "(SKIP_SEARCH=$SKIP_SEARCH or missing query/GT files — no query benchmark)"
  fi

  echo "Index phase:"
  summarize_csv "$idx_csv"

  if [[ -n "${SOLR_HOME:-}" ]]; then
    python3 - "$coll" "$OUT_DIR" "$quant" <<'PY'
import json, os, sys
from pathlib import Path
coll, out_s, quant = sys.argv[1], sys.argv[2], sys.argv[3]
out = Path(out_s)
base = Path(os.environ.get("SOLR_HOME", ""))
if not base:
    sys.exit(0)
candidates = list(base.glob(f"{coll}_shard1_replica_n1/data/index"))
if not candidates:
    print(f"No index dir for {coll}")
    sys.exit(0)
idx = candidates[0]
ext_tot = {}
for p in idx.rglob("*"):
    if p.is_file():
        suf = p.suffix or "(noext)"
        ext_tot[suf] = ext_tot.get(suf, 0) + p.stat().st_size
lines = sorted(ext_tot.items(), key=lambda x: -x[1])
(out / f"disk_breakdown_{quant}.json").write_text(json.dumps({
    "quant": quant, "index_dir": str(idx), "by_suffix_bytes": dict(lines[:40])
}, indent=2))
for suf, b in lines[:8]:
    print(f"  {suf}: {b/1024/1024:.1f} MiB")
PY
  fi

  echo "Cleanup collection $coll"
  curl -sS "$SOLR_URL/solr/admin/collections?action=DELETE&name=$coll" >/dev/null 2>&1 || true
  curl -sS "$SOLR_URL/solr/admin/configs?action=DELETE&name=$cfg" >/dev/null 2>&1 || true
}

if [[ ! -d "$BATCHES_DIR" ]]; then
  echo "Missing $BATCHES_DIR — run:"
  echo "  ./scripts/prepare-wiki-javabin-subset.sh 2000000 wiki-2m-500kbatches 500000"
  exit 1
fi

if [[ -z "${SOLR_HOME:-}" ]]; then
  SOLR_HOME="$(curl -sf "$SOLR_URL/solr/admin/info/system?wt=json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('solr_home',''))" 2>/dev/null || true)"
  export SOLR_HOME
fi

p="$(_solr_java_pid)"
if [[ -z "$p" ]]; then
  echo "ERROR: Solr not reachable or Java PID not found. Start Solr first (same as for mem-profile-cuvs-quant.sh)."
  exit 1
fi

echo "Output: $OUT_DIR"
echo "Batches: $BATCHES_DIR ($NUM_BATCHES batches)"
echo "Solr PID (initial): $p  SOLR_HOME=$SOLR_HOME"

run_variant none
sleep 3
run_variant scalar

echo ""
echo "========== DONE =========="
echo "Artifacts: $OUT_DIR"
ls -la "$OUT_DIR"
