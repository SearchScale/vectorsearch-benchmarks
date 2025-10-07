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
EF_SEARCH=$(jq -r '.efSearch' "$CONFIG_FILE")
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

echo "DEBUG: CLEAN_INDEX_DIRECTORY=$CLEAN_INDEX_DIRECTORY"
echo "DEBUG: SKIP_INDEXING=$SKIP_INDEXING"

# Extract Solr URL from the update URL parameter
SOLR_URL=$(echo "$URL" | sed 's|/solr/.*||')

# Static variables and defaults
DATA_DIR="data"
DATASET_FILENAME="wiki_all_10M.tar"
SOLR_GITHUB_REPO="https://github.com/apache/solr.git"
SOLR_ROOT=solr-10.0.0-SNAPSHOT
NPARALLEL=8
SIMILARITY_FUNCTION=${SIMILARITY_FUNCTION:-euclidean}
RAM_BUFFER_SIZE_MB=${RAM_BUFFER_SIZE_MB:-20000}

# Only proceed with indexing if skipIndexing is false
if [ "$SKIP_INDEXING" = "false" ]; then

# Generate configset files on the fly
mkdir -p temp-configset

# Generate managed-schema
if [ "$KNN_ALGORITHM" = "hnsw" ]; then
    # For HNSW: Include hnswMaxConnections and hnswBeamWidth in fieldType
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
    # For CAGRA_HNSW: Keep original format without HNSW-specific parameters
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

# Generate solrconfig.xml
if [ "$KNN_ALGORITHM" = "hnsw" ]; then
    # For HNSW: Remove codecFactory altogether
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
            <openSearcher>false</openSearcher>
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
    # For CAGRA_HNSW: Keep codecFactory as before
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
fi


# Load cuvs module, start Solr
pkill -9 java; rm -rf $SOLR_ROOT
tar -xf $SOLR_ROOT.tgz
cd $SOLR_ROOT
cp log4j2.xml solr-10.0.0-SNAPSHOT/server/resources/log4j2.xml
cp modules/cuvs/lib/*.jar server/solr-webapp/webapp/WEB-INF/lib/
bin/solr start -m 29G
cd ..
# Create collection with dynamically generated configset
(cd temp-configset && zip -r - *) | curl -X POST --header "Content-Type:application/octet-stream" --data-binary @- "$SOLR_URL/solr/admin/configs?action=UPLOAD&name=cuvs"
curl "$SOLR_URL/solr/admin/collections?action=CREATE&name=test&numShards=1&collection.configName=cuvs"

# Create results file with configuration object
python3 << EOF
import json
import os

# Ensure results directory exists
os.makedirs("$RESULTS_DIR", exist_ok=True)

# Read the config file and put it as-is into the configuration section
with open("$CONFIG_FILE", "r") as config_file:
    config_data = json.load(config_file)

# Create initial results file with configuration and metrics
results = {
    "configuration": config_data,
    "metrics": {}
}

# Read javabin preparation time if available
javabin_time_file = "$JAVABIN_FILES_DIR" + "_preparation_time.txt"
if os.path.exists(javabin_time_file):
    with open(javabin_time_file, "r") as f:
        javabin_prep_time = int(f.read().strip())
        results["metrics"]["javabin-preparation-time"] = javabin_prep_time

with open("$RESULTS_DIR/results.json", "w") as f:
    json.dump(results, f, indent=2)
EOF

start_time=$(date +%s%N) # Record start time in nanoseconds

# Loop through each file in the directory and post it in batches using Python
python3 << EOF
import subprocess
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

files = sorted(Path("$JAVABIN_FILES_DIR").glob("*"))
def upload(f):
    print(f"Uploading {f}...")
    subprocess.run(["http", "--ignore-stdin", "POST", "$URL", "Content-Type:application/javabin", f"@{f}"])
    print(f"Completed {f}")

print(f"Starting batch upload with {$NPARALLEL} parallel workers")
with ThreadPoolExecutor(max_workers=$NPARALLEL) as ex:
    list(ex.map(upload, files))
print("All uploads completed")
EOF
end_time=$(date +%s%N)   # Record end time in nanoseconds

duration=$(( (end_time - start_time) / 1000000 )) # Calculate duration in milliseconds
echo "Execution time: $duration ms"
echo "Done!"

fi # End of skipIndexing check

# Run query benchmarks
echo "Running query benchmarks..."
python3 run_queries.py \
    --ef-search $EF_SEARCH \
    --warmup-queries $WARMUP_QUERIES \
    --num-queries $TOTAL_QUERIES \
    --query-file $QUERY_FILE \
    --neighbors-file $GROUND_TRUTH_FILE \
    --solr-url $SOLR_URL \
    --collection test \
    --vector-field $VECTOR_COL_NAME \
    --top-k $TOP_K \
    --output-file "$RESULTS_DIR/results.json"

# Add indexing time to results.json only if indexing was performed
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
"
fi

# Cleanup
rm -rf temp-configset

echo "DEBUG: About to check CLEAN_INDEX_DIRECTORY condition: '$CLEAN_INDEX_DIRECTORY'"
if [ "$CLEAN_INDEX_DIRECTORY" = "true" ]; then
    echo "DEBUG: CLEAN_INDEX_DIRECTORY is true, stopping and cleaning Solr"
    cd $SOLR_ROOT
    bin/solr stop -p 8983
    cd ..
    rm -rf $SOLR_ROOT
    echo "Stopped Solr and cleaned it up..."
else
    echo "DEBUG: CLEAN_INDEX_DIRECTORY is false, preserving Solr for reuse"
fi

wait
