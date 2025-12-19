#!/bin/bash

# An end-to-end script to benchmark Solr's indexing performance

# Check if required arguments are provided
if [ $# -ne 4 ]; then
    echo "Usage: $0 <config_file> <batches_dir> <solr_update_url> <results_dir>"
    echo "Example: $0 configs/wiki10m/cagra_hnsw-08f8e8a1-ef800.json wiki-10m_batches http://localhost:8983/solr/test/update?commit=true&overwrite=false /path/to/results"
    exit 1
fi

CONFIG_FILE="$1"
JAVABIN_FILES_DIR="$2"
URL="$3"
RESULTS_DIR="$4"

mkdir -p "$RESULTS_DIR"

# Check if config file exists
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Config file '$CONFIG_FILE' not found"
    exit 1
fi

# Check if batches directory exists
if [ ! -d "$JAVABIN_FILES_DIR" ]; then
    echo "Error: Batches directory '$JAVABIN_FILES_DIR' not found"
    exit 1
fi

# Extract variables from config file using jq
VECTOR_DIMENSION=$(jq -r '.vectorDimension' "$CONFIG_FILE")
KNN_ALGORITHM=$(jq -r '.algoToRun' "$CONFIG_FILE")
EF_SEARCH_SCALE_FACTOR=$(jq -r '.efSearchScaleFactor' "$CONFIG_FILE")
WARMUP_QUERIES=$(jq -r '.numWarmUpQueries' "$CONFIG_FILE")
TOTAL_QUERIES=$(jq -r '.numQueriesToRun' "$CONFIG_FILE")
QUERY_FILE=$(jq -r '.queryFile' "$CONFIG_FILE")
GROUND_TRUTH_FILE=$(jq -r '.groundTruthFile' "$CONFIG_FILE")
VECTOR_COL_NAME=$(jq -r '.vectorColName' "$CONFIG_FILE")
TOP_K=$(jq -r '.topK' "$CONFIG_FILE")
CUVS_WRITER_THREADS=$(jq -r 'if .cuvsWriterThreads == null or .cuvsWriterThreads == "null" then "8" else .cuvsWriterThreads end' "$CONFIG_FILE")
INT_GRAPH_DEGREE=$(jq -r 'if .cagraIntermediateGraphDegree == null or .cagraIntermediateGraphDegree == "null" then "64" else .cagraIntermediateGraphDegree end' "$CONFIG_FILE")
GRAPH_DEGREE=$(jq -r 'if .cagraGraphDegree == null or .cagraGraphDegree == "null" then "32" else .cagraGraphDegree end' "$CONFIG_FILE")
HNSW_LAYERS=$(jq -r 'if .cagraHnswLayers == null or .cagraHnswLayers == "null" then "1" else .cagraHnswLayers end' "$CONFIG_FILE")
MAX_CONN=$(jq -r 'if .hnswMaxConn == null or .hnswMaxConn == "null" then "16" else .hnswMaxConn end' "$CONFIG_FILE")
BEAM_WIDTH=$(jq -r 'if .hnswBeamWidth == null or .hnswBeamWidth == "null" then "100" else .hnswBeamWidth end' "$CONFIG_FILE")
CLEAN_INDEX_DIRECTORY=$(jq -r 'if has("cleanIndexDirectory") then .cleanIndexDirectory else true end' "$CONFIG_FILE")
SKIP_INDEXING=$(jq -r 'if has("skipIndexing") then .skipIndexing else false end' "$CONFIG_FILE")

# Extract Solr URL from the update URL parameter
SOLR_URL=$(echo "$URL" | sed 's|/solr/.*||')

# Static variables and defaults
DATA_DIR="data"
DATASET_FILENAME="wiki_all_10M.tar"
SOLR_GITHUB_REPO="https://github.com/apache/solr.git"
SOLR_ROOT=solr-11.0.0-SNAPSHOT
NPARALLEL=${NPARALLEL:-4}
SIMILARITY_FUNCTION=${SIMILARITY_FUNCTION:-euclidean}
RAM_BUFFER_SIZE_MB=${RAM_BUFFER_SIZE_MB:-20000}
SOLR_HEAP=${SOLR_HEAP:-16G}

METRICS_PID=""

wait_for_solr() {
    local url="$1"
    local retries="${2:-60}"
    for _ in $(seq 1 "$retries"); do
        if curl -sSf "${url}/solr/admin/info/system?wt=json" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

start_metrics() {
    if [ -n "${METRICS_PID:-}" ]; then
        return
    fi

    mkdir -p "$RESULTS_DIR"
    SOLR_URL="$SOLR_URL" RESULTS_DIR="$RESULTS_DIR" python3 -u << 'METRICS_EOF' > "$RESULTS_DIR/metrics.log" 2>&1 &
import urllib.request
import json
import time
import signal
import sys
import math
import os

solr_url = os.environ["SOLR_URL"]
results_dir = os.environ["RESULTS_DIR"]
start_time = int(time.time() * 1000)
memory_samples = []
cpu_samples = []

PREFERRED_SCOPE = "io.opentelemetry.runtime-telemetry-java17"

def save_metrics():
    import os
    os.makedirs(results_dir, exist_ok=True)
    with open(f"{results_dir}/memory_metrics.json", "w") as f:
        json.dump({"memory_samples": memory_samples}, f, indent=2)
    with open(f"{results_dir}/cpu_metrics.json", "w") as f:
        json.dump({"cpu_samples": cpu_samples}, f, indent=2)

def handle_signal(signum, frame):
    save_metrics()
    sys.exit(0)

signal.signal(signal.SIGTERM, handle_signal)
signal.signal(signal.SIGINT, handle_signal)

def parse_labels(label_str: str):
    labels = {}
    if not label_str:
        return labels
    parts = []
    cur = []
    in_quotes = False
    for ch in label_str:
        if ch == '"' and (not cur or cur[-1] != '\\'):
            in_quotes = not in_quotes
        if ch == ',' and not in_quotes:
            parts.append(''.join(cur))
            cur = []
        else:
            cur.append(ch)
    if cur:
        parts.append(''.join(cur))
    for p in parts:
        if '=' not in p:
            continue
        k, v = p.split('=', 1)
        labels[k.strip()] = v.strip().strip('"')
    return labels

def parse_prometheus(text: str):
    points = []
    for line in text.splitlines():
        if not line or line[0] == '#':
            continue
        try:
            left, val = line.rsplit(' ', 1)
        except ValueError:
            continue
        if '{' in left:
            name, rest = left.split('{', 1)
            labels = parse_labels(rest.rstrip('}'))
        else:
            name, labels = left, {}
        try:
            value = float(val)
        except ValueError:
            continue
        points.append((name, labels, value))
    return points

def pick_scope(points):
    scopes = set()
    for _, labels, _ in points:
        s = labels.get("otel_scope_name")
        if s:
            scopes.add(s)
    if PREFERRED_SCOPE in scopes:
        return PREFERRED_SCOPE
    return next(iter(scopes), None)

for _ in range(60):
    try:
        urllib.request.urlopen(f"{solr_url}/solr/admin/metrics?wt=prometheus", timeout=5)
        break
    except Exception:
        time.sleep(1)
else:
    print("Error: Solr metrics endpoint not ready", file=sys.stderr)
    sys.exit(1)

while True:
    try:
        elapsed = int(time.time() * 1000) - start_time

        sys_resp = urllib.request.urlopen(f"{solr_url}/solr/admin/info/system?wt=json", timeout=5)
        sys_data = json.loads(sys_resp.read().decode("utf-8"))
        heap_used = sys_data["jvm"]["memory"]["raw"]["used"]
        heap_max = sys_data["jvm"]["memory"]["raw"]["max"]

        resp = urllib.request.urlopen(f"{solr_url}/solr/admin/metrics?wt=prometheus", timeout=10)
        text = resp.read().decode("utf-8", errors="replace")
        points = parse_prometheus(text)
        scope = pick_scope(points)

        nonheap_used = 0.0
        proc_cpu = None
        sys_cpu = None

        for name, labels, value in points:
            if scope and labels.get("otel_scope_name") not in (None, scope):
                continue
            if name == "jvm_memory_used_bytes":
                if labels.get("jvm_memory_type") == "non_heap":
                    nonheap_used += value
            elif name == "jvm_cpu_recent_utilization_ratio":
                proc_cpu = value
            elif name == "jvm_system_cpu_utilization_ratio":
                sys_cpu = value

        if proc_cpu is None or math.isnan(proc_cpu):
            proc_cpu = 0.0
        if sys_cpu is None or math.isnan(sys_cpu):
            sys_cpu = 0.0

        memory_samples.append({
            "timestamp": elapsed,
            "heapUsed": int(heap_used),
            "heapMax": int(heap_max),
            "offHeapUsed": int(nonheap_used),
        })
        cpu_samples.append({
            "timestamp": elapsed,
            "cpuUsagePercent": float(proc_cpu) * 100.0,
            "systemCpuUsagePercent": float(sys_cpu) * 100.0,
        })

        time.sleep(2)
    except Exception as e:
        print(f"Metrics collection error: {e}", file=sys.stderr)
        time.sleep(2)
METRICS_EOF

    METRICS_PID=$!
    echo "Started metrics collection (PID: $METRICS_PID)"
    trap "kill -TERM $METRICS_PID 2>/dev/null; wait $METRICS_PID 2>/dev/null" EXIT
}

if [ "$SKIP_INDEXING" = "false" ]; then

mkdir -p temp-configset

if [ "$KNN_ALGORITHM" = "hnsw" ]; then
    cat > temp-configset/managed-schema << EOF
<?xml version="1.0" ?>
<schema name="schema-densevector" version="1.7">
    <fieldType name="string" class="solr.StrField" multiValued="true"/>
    <fieldType name="knn_vector" class="solr.DenseVectorField"
               vectorDimension="$VECTOR_DIMENSION"
               knnAlgorithm="$KNN_ALGORITHM"
               similarityFunction="$SIMILARITY_FUNCTION"
               hnswMaxConnections="$MAX_CONN" hnswBeamWidth="$BEAM_WIDTH" />
    <fieldType name="plong" class="solr.LongPointField" useDocValuesAsStored="false"/>

    <field name="id" type="string" indexed="true" stored="true" multiValued="false" required="false"/>
    <field name="article_vector" type="knn_vector" indexed="true" stored="false"/>
    <field name="_version_" type="plong" indexed="true" stored="true" multiValued="false" />

    <uniqueKey>id</uniqueKey>
</schema>
EOF
else
    cat > temp-configset/managed-schema << EOF
<?xml version="1.0" ?>
<schema name="schema-densevector" version="1.7">
    <fieldType name="string" class="solr.StrField" multiValued="true"/>
    <fieldType name="knn_vector" class="solr.DenseVectorField"
               vectorDimension="$VECTOR_DIMENSION"
               knnAlgorithm="$KNN_ALGORITHM"
               similarityFunction="$SIMILARITY_FUNCTION" />
    <fieldType name="plong" class="solr.LongPointField" useDocValuesAsStored="false"/>

    <field name="id" type="string" indexed="true" stored="true" multiValued="false" required="false"/>
    <field name="article_vector" type="knn_vector" indexed="true" stored="false"/>
    <field name="_version_" type="plong" indexed="true" stored="true" multiValued="false" />

    <uniqueKey>id</uniqueKey>
</schema>
EOF
fi

if [ "$KNN_ALGORITHM" = "hnsw" ]; then
    cat > temp-configset/solrconfig.xml << EOF
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
            <infoStream>true</infoStream>
    </indexConfig>

    <updateHandler class="solr.DirectUpdateHandler2">
        <autoCommit>
            <maxTime>\${solr.autoCommit.maxTime:1500000}</maxTime>
            <openSearcher>true</openSearcher>
        </autoCommit>
        <autoSoftCommit>
            <maxTime>\${solr.autoSoftCommit.maxTime:1500000}</maxTime>
        </autoSoftCommit>
    </updateHandler>

    <requestHandler name="/select" class="solr.SearchHandler">
        <lst name="defaults">
            <str name="echoParams">explicit</str>
            <int name="rows">10</int>
        </lst>
    </requestHandler>

    <requestHandler name="/update" class="solr.UpdateRequestHandler" />
</config>
EOF
else
    cat > temp-configset/solrconfig.xml << EOF
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
            <infoStream>true</infoStream>
    </indexConfig>

    <updateHandler class="solr.DirectUpdateHandler2">
        <autoCommit>
            <maxTime>\${solr.autoCommit.maxTime:1500000}</maxTime>
            <openSearcher>true</openSearcher>
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
fi


# Load cuvs module, start Solr
pkill -9 java 2>/dev/null || true
rm -rf $SOLR_ROOT
tar -xf $SOLR_ROOT.tgz
cd $SOLR_ROOT
cp modules/cuvs/lib/*.jar server/solr-webapp/webapp/WEB-INF/lib/ 2>/dev/null || true
bin/solr start -m "$SOLR_HEAP"
cd ..

if ! wait_for_solr "$SOLR_URL" 180; then
    echo "Error: Solr did not start on $SOLR_URL"
    exit 1
fi

(cd temp-configset && zip -r - *) | curl -X POST --header "Content-Type:application/octet-stream" --data-binary @- "$SOLR_URL/solr/admin/configs?action=UPLOAD&name=cuvs"
curl "$SOLR_URL/solr/admin/collections?action=CREATE&name=test&numShards=1&collection.configName=cuvs"

start_metrics

python3 << EOF
import json
import os

os.makedirs("$RESULTS_DIR", exist_ok=True)

with open("$CONFIG_FILE", "r") as config_file:
    config_data = json.load(config_file)

results = {
    "configuration": config_data,
    "metrics": {}
}

javabin_time_file = "$JAVABIN_FILES_DIR" + "_preparation_time.txt"
if os.path.exists(javabin_time_file):
    with open(javabin_time_file, "r") as f:
        javabin_prep_time = int(f.read().strip())
        results["metrics"]["javabin-preparation-time"] = javabin_prep_time

with open("$RESULTS_DIR/results.json", "w") as f:
    json.dump(results, f, indent=2)
EOF

start_time=$(date +%s%N)

python3 << EOF
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import requests
import time

files = sorted(Path("$JAVABIN_FILES_DIR").glob("*"))
failed_files = []

def upload(f, max_retries=3):
    for attempt in range(max_retries):
        try:
            if attempt > 0:
                wait_time = min((attempt + 1) * 5, 30)
                print(f"Uploading {f.name}... (attempt {attempt + 1}/{max_retries}, waiting {wait_time}s)", flush=True)
                time.sleep(wait_time)
            else:
                print(f"Uploading {f.name}... (attempt {attempt + 1}/{max_retries})", flush=True)
            with open(f, "rb") as fh:
                r = requests.post(
                    "$URL",
                    data=fh,
                    headers={"Content-Type": "application/javabin"},
                    timeout=600,
                )
                r.raise_for_status()
            print(f"Completed {f.name}", flush=True)
            time.sleep(0.5)
            return True
        except requests.exceptions.HTTPError as e:
            if e.response.status_code >= 500 and attempt < max_retries - 1:
                continue
            print(f"Failed to upload {f.name}: HTTP {e.response.status_code if hasattr(e, 'response') else 'unknown'}", flush=True)
            return False
        except requests.exceptions.ConnectionError as e:
            if attempt < max_retries - 1:
                continue
            print(f"Failed to upload {f.name}: Connection error", flush=True)
            return False
        except Exception as e:
            if attempt < max_retries - 1:
                continue
            print(f"Failed to upload {f.name}: {e}", flush=True)
            return False
    return False

print(f"Starting batch upload with {$NPARALLEL} parallel workers")
with ThreadPoolExecutor(max_workers=$NPARALLEL) as ex:
    futures = {ex.submit(upload, f): f for f in files}
    for future in as_completed(futures):
        f = futures[future]
        try:
            success = future.result()
            if not success:
                failed_files.append(f)
        except Exception as e:
            print(f"Exception for {f.name}: {e}", flush=True)
            failed_files.append(f)

if failed_files:
    print(f"\nWarning: {len(failed_files)} files failed to upload:", flush=True)
    for f in failed_files:
        print(f"  - {f.name}", flush=True)
    exit(1)
else:
    print("All uploads completed successfully", flush=True)
EOF
INDEXING_EXIT_CODE=$?

if [ $INDEXING_EXIT_CODE -ne 0 ]; then
    echo "Error: Indexing failed with exit code $INDEXING_EXIT_CODE"
    if [ -n "${METRICS_PID:-}" ]; then
        kill -TERM $METRICS_PID 2>/dev/null || true
        wait $METRICS_PID 2>/dev/null || true
    fi
    exit $INDEXING_EXIT_CODE
fi

end_time=$(date +%s%N)
duration=$(( (end_time - start_time) / 1000000 ))
echo "Execution time: $duration ms"
echo "Done!"

fi

start_metrics

python3 run_queries.py \
    --ef-search-scale-factor $EF_SEARCH_SCALE_FACTOR \
    --warmup-queries $WARMUP_QUERIES \
    --num-queries $TOTAL_QUERIES \
    --query-file $QUERY_FILE \
    --neighbors-file $GROUND_TRUTH_FILE \
    --solr-url $SOLR_URL \
    --collection test \
    --vector-field $VECTOR_COL_NAME \
    --top-k $TOP_K \
    --output-file "$RESULTS_DIR/results.json"
QUERY_EXIT_CODE=$?

if [ $QUERY_EXIT_CODE -ne 0 ]; then
    echo "Error: Query benchmarks failed with exit code $QUERY_EXIT_CODE"
    if [ -n "${METRICS_PID:-}" ]; then
        kill -TERM $METRICS_PID 2>/dev/null || true
        wait $METRICS_PID 2>/dev/null || true
    fi
    exit $QUERY_EXIT_CODE
fi

if [ "$SKIP_INDEXING" = "false" ]; then
    python3 -c "
import json
with open('$RESULTS_DIR/results.json', 'r+') as f:
    results = json.load(f)
    if 'metrics' not in results:
        results['metrics'] = {}
    results['metrics']['cuvs-indexing-time'] = $duration
    f.seek(0)
    json.dump(results, f, indent=2)
    f.truncate()
" || echo "Warning: Failed to add indexing time to results.json (non-fatal)"
fi

if [ -n "${METRICS_PID:-}" ]; then
    kill -TERM $METRICS_PID 2>/dev/null || true
    wait $METRICS_PID 2>/dev/null || true
fi

rm -rf temp-configset

if [ "$CLEAN_INDEX_DIRECTORY" = "true" ]; then
    cd $SOLR_ROOT
    bin/solr stop -p 8983
    cd ..
    rm -rf $SOLR_ROOT
fi

wait
exit 0
