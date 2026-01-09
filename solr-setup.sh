#!/bin/bash

# An end-to-end script to benchmark Solr's indexing performance

# Function to display usage
usage() {
    echo "Usage: $0 <sweep_file> <dataset_file> <data_dir>"
    echo "  sweep_file:   Path to the sweep configuration JSON file (e.g., solr-sweeps.json)"
    echo "  dataset_file: Path to the dataset configuration JSON file (e.g., datasets.json)"
    echo "  data_dir:     Base directory where dataset files are located"
    echo ""
    echo "Example: $0 solr-sweeps.json datasets.json /data2/vsbench-datasets"
    exit 1
}

# Check if correct number of arguments provided
if [ $# -ne 3 ]; then
    echo "Error: Incorrect number of arguments"
    usage
fi

echo "************* SOLR SETUP **************"

# Parse command line arguments
SWEEP_FILE="$1"
DATASET_CONFIG_FILE="$2"
DATA_DIR="$3"

# Validate input files exist
if [ ! -f "$SWEEP_FILE" ]; then
    echo "Error: Sweep file '$SWEEP_FILE' not found"
    exit 1
fi

if [ ! -f "$DATASET_CONFIG_FILE" ]; then
    echo "Error: Dataset file '$DATASET_CONFIG_FILE' not found"
    exit 1
fi

if [ ! -d "$DATA_DIR" ]; then
    echo "Error: Data directory '$DATA_DIR' not found"
    exit 1
fi

# Extract dataset name from sweep file (assuming first key in the sweep file)
DATASET_NAME=$(jq -r 'keys[0]' "$SWEEP_FILE")
if [ "$DATASET_NAME" = "null" ] || [ -z "$DATASET_NAME" ]; then
    echo "Error: Could not extract dataset name from sweep file"
    exit 1
fi

# Extract dataset info from sweep file
DATASET_FROM_SWEEP=$(jq -r ".[\"$DATASET_NAME\"].dataset" "$SWEEP_FILE")
if [ "$DATASET_FROM_SWEEP" = "null" ]; then
    echo "Error: Could not extract dataset from sweep file"
    exit 1
fi

# Get dataset configuration
DATASET_INFO=$(jq -r ".datasets[\"$DATASET_FROM_SWEEP\"]" "$DATASET_CONFIG_FILE")
if [ "$DATASET_INFO" = "null" ]; then
    echo "Error: Dataset '$DATASET_FROM_SWEEP' not found in dataset configuration file"
    exit 1
fi

# Extract dataset parameters
BASE_FILE=$(echo "$DATASET_INFO" | jq -r '.base_file')
VECTOR_DIMENSION=$(echo "$DATASET_INFO" | jq -r '.vector_dimension')
NUM_DOCS=$(echo "$DATASET_INFO" | jq -r '.num_docs')

# Construct full dataset file path
DATASET_FILE="$DATA_DIR/$DATASET_FROM_SWEEP/$BASE_FILE"

# Validate dataset file exists
if [ ! -f "$DATASET_FILE" ]; then
    echo "Error: Dataset file '$DATASET_FILE' not found"
    exit 1
fi

# Extract common parameters from sweep file
BATCH_SIZE=$(jq -r ".[\"$DATASET_NAME\"][\"common-params\"].batchSize" "$SWEEP_FILE")
DOCS_COUNT=$(jq -r ".[\"$DATASET_NAME\"][\"common-params\"].numDocs" "$SWEEP_FILE")

# Extract algorithm parameters (assuming first algorithm for now)
ALGORITHM_NAME=$(jq -r ".[\"$DATASET_NAME\"].algorithms | keys[0]" "$SWEEP_FILE")
ALGORITHM_PARAMS=$(jq -r ".[\"$DATASET_NAME\"].algorithms[\"$ALGORITHM_NAME\"]" "$SWEEP_FILE")

# Extract specific algorithm parameters with defaults
if [ "$ALGORITHM_NAME" = "cagra_hnsw" ]; then
    CUVS_WRITER_THREADS=$(echo "$ALGORITHM_PARAMS" | jq -r '.cuvsWriterThreads // 16')
    INT_GRAPH_DEGREE=$(echo "$ALGORITHM_PARAMS" | jq -r 'if .cagraIntermediateGraphDegree | type == "array" then .cagraIntermediateGraphDegree[0] else .cagraIntermediateGraphDegree end // 128')
    GRAPH_DEGREE=$(echo "$ALGORITHM_PARAMS" | jq -r 'if .cagraGraphDegree | type == "array" then .cagraGraphDegree[0] else .cagraGraphDegree end // 64')
    HNSW_LAYERS=$(echo "$ALGORITHM_PARAMS" | jq -r '.cagraHnswLayers // 1')
fi

echo "Configuration loaded:"
echo "  Dataset: $DATASET_FROM_SWEEP"
echo "  Dataset file: $DATASET_FILE"
echo "  Vector dimension: $VECTOR_DIMENSION"
echo "  Documents: $DOCS_COUNT"
echo "  Algorithm: $ALGORITHM_NAME"
echo ""

# Additional Variables
JFG_DIR="solr-javabin-generator"
JFG_GITHUB_URL="https://github.com/SearchScale/solr-javabin-generator.git"
SOLR_DIR="solr"
SOLR_GITHUB_REPO="https://github.com/apache/solr.git"
SOLR_CUVS_MODULE_BRANCH="main"
SOLR_ROOT=solr-11.0.0-SNAPSHOT
JAVABIN_FILES_DIR="${DATASET_FROM_SWEEP}_batches"
SOLR_URL="http://localhost:8983"
URL="$SOLR_URL/solr/test/update?commit=true&overwrite=false"
KNN_ALGORITHM=${KNN_ALGORITHM:-$ALGORITHM_NAME}
SIMILARITY_FUNCTION=${SIMILARITY_FUNCTION:-euclidean}
RAM_BUFFER_SIZE_MB=${RAM_BUFFER_SIZE_MB:-20000}
MAX_CONN=${MAX_CONN:-16}
BEAM_WIDTH=${BEAM_WIDTH:-100}

pkill -9 java
rm -rf $SOLR_ROOT

# Get Javabin files generator if not already existing and build it
if [ ! -d "$JFG_DIR" ]; then
  echo "repo '$JFG_DIR' does not exist."
  git clone $JFG_GITHUB_URL $JFG_DIR
  # build
  cd $JFG_DIR
  mvn clean package
  cd ..
fi

# Get Solr's PR branch containing the cuvs module if not already existing
if [ ! -d "$SOLR_DIR" ]; then
  echo "repo '$SOLR_DIR' does not exist."
  git clone $SOLR_GITHUB_REPO $SOLR_DIR
  # build
  cd $SOLR_DIR
  git checkout $SOLR_CUVS_MODULE_BRANCH
  ./gradlew clean distTar
  mv solr/packaging/build/distributions/$SOLR_ROOT.tgz ../
  cd ..
fi

rm -rf $JAVABIN_FILES_DIR
if [ ! -d "$JAVABIN_FILES_DIR" ]; then
	  javabin_start_time=$(date +%s%N)
	  java -jar $JFG_DIR/target/javabin-generator-1.0-SNAPSHOT-jar-with-dependencies.jar data_file=$DATASET_FILE output_dir=$JAVABIN_FILES_DIR batch_size=$BATCH_SIZE docs_count=$DOCS_COUNT threads=all
	  javabin_end_time=$(date +%s%N)
	  javabin_duration=$(( (javabin_end_time - javabin_start_time) / 1000000 ))
	  echo "JavaBin preparation time: $javabin_duration ms"
	  echo "$javabin_duration" > ${JAVABIN_FILES_DIR}_preparation_time.txt
fi

echo "************** Setup complete: $SOLR_URL **************"
