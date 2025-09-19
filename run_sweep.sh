#!/bin/bash

set -e

if [ $# -ne 1 ]; then
    echo "Usage: $0 <sweep_template>"
    echo "Example: $0 configs/sweep_templates/sift-1m-sweep.yaml"
    exit 1
fi

SWEEP_TEMPLATE="$1"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -f "$SWEEP_TEMPLATE" ]; then
    echo "Error: Sweep template not found: $SWEEP_TEMPLATE"
    exit 1
fi

echo "Starting benchmark sweep: $SWEEP_TEMPLATE"

echo "Phase 1: Checking datasets..."
cd "$PROJECT_ROOT"
./datasets/download_and_preprocess.sh

echo "Phase 2: Generating configurations..."
python3 configs/generate_configs.py "$SWEEP_TEMPLATE" "datasets/datasets.yaml"

SWEEP_ID=$(python3 -c "
import json
import glob
import os
summary_files = glob.glob('configs/generated/sweep_*_summary.json')
if summary_files:
    # Sort by modification time, get the most recent
    latest_file = max(summary_files, key=os.path.getmtime)
    with open(latest_file, 'r') as f:
        data = json.load(f)
    print(data['sweep_id'])
else:
    print('unknown')
")

echo "Sweep ID: $SWEEP_ID"

echo "Phase 3: Running benchmarks..."

# Clean up any existing MapDB files to avoid conflicts
echo "Cleaning up previous MapDB artifacts..."
find . -name "*.mapdb*" -type f -delete 2>/dev/null || true

SWEEP_RESULTS_DIR="results/raw/$SWEEP_ID"
mkdir -p "$SWEEP_RESULTS_DIR"
echo "Created sweep results directory: $SWEEP_RESULTS_DIR"

# Get run IDs from the current sweep summary
RUN_IDS=$(python3 -c "
import json
import glob
import os
summary_files = glob.glob('configs/generated/sweep_*_summary.json')
if summary_files:
    latest_file = max(summary_files, key=os.path.getmtime)
    with open(latest_file, 'r') as f:
        data = json.load(f)
    for config in data['configs']:
        print(config['run_id'])
")

# Build config file paths from run IDs
CONFIG_FILES=""
for run_id in $RUN_IDS; do
    CONFIG_FILES="$CONFIG_FILES configs/generated/${run_id}.json"
done

TOTAL_CONFIGS=$(echo "$CONFIG_FILES" | wc -w)
CURRENT=0

for config_file in $CONFIG_FILES; do
    CURRENT=$((CURRENT + 1))
    echo "Running benchmark $CURRENT/$TOTAL_CONFIGS: $(basename "$config_file")"
    
    if SWEEP_ID="$SWEEP_ID" ./benchmarks/run_benchmark.sh "$config_file"; then
        echo "Benchmark completed successfully"
    else
        echo "Benchmark failed, continuing with next..."
    fi
done

echo "Phase 4: Consolidating results..."
python3 postprocessing/consolidate_results.py

echo "Sweep completed: $SWEEP_ID"
echo "Results available in: $SWEEP_RESULTS_DIR"
echo "Web UI can be started with: cd web-ui-new && python3 unified-server.py 8000"

