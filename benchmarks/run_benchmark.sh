#!/bin/bash

set -e

if [ $# -ne 1 ]; then
    echo "Usage: $0 <config_file>"
    exit 1
fi

CONFIG_FILE="$1"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Config file not found: $CONFIG_FILE"
    exit 1
fi

RUN_ID=$(python3 -c "import json; print(json.load(open('$CONFIG_FILE'))['run_id'])")
SWEEP_ID="${SWEEP_ID:-unknown}"

# Update config file with the sweep ID from environment
python3 -c "
import json
with open('$CONFIG_FILE', 'r') as f:
    config = json.load(f)
config['sweep_id'] = '$SWEEP_ID'
with open('$CONFIG_FILE', 'w') as f:
    json.dump(config, f, indent=2)
"

RESULTS_DIR="$PROJECT_ROOT/results/raw/$SWEEP_ID/$RUN_ID"
mkdir -p "$RESULTS_DIR"

echo "Running benchmark: $RUN_ID"

cd "$PROJECT_ROOT"
mvn clean compile -q

export LD_LIBRARY_PATH=/home/puneet/code/cuvs-repo/cuvs/cpp/build:/home/puneet/code/cuvs-repo/cuvs/cpp/build/_deps/rmm-build:/usr/local/cuda-12.5/lib64:/home/puneet/miniforge3/lib:$LD_LIBRARY_PATH

mvn exec:java \
    -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.BenchmarkRunner" \
    -Dexec.args="$CONFIG_FILE" \
    -Dexec.jvmArgs="-Xmx64g --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED" \
    -q \
    > "$RESULTS_DIR/${RUN_ID}_stdout.log" \
    2> "$RESULTS_DIR/${RUN_ID}_stderr.log"

EXIT_CODE=$?

env > "$RESULTS_DIR/env.json"
cp "$CONFIG_FILE" "$RESULTS_DIR/materialized-config.json"

if [ $EXIT_CODE -eq 0 ]; then
    echo "Benchmark completed successfully: $RUN_ID"
else
    echo "Benchmark failed with exit code $EXIT_CODE: $RUN_ID"
fi

exit $EXIT_CODE
