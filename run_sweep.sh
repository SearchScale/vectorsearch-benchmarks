#!/bin/bash

# Parse command-line arguments
while getopts ":-:" opt; do
    case $OPTARG in
        data-dir) DATA_DIR="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
        datasets) DATASETS_FILE="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
        sweeps) SWEEPS_FILE="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
        configs-dir) CONFIGS_DIR="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
        results-dir) RESULTS_DIR="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
        run-benchmarks) RUN_BENCHMARKS="true" ;;
        help)
            echo "Usage: $0 [OPTIONS]"
            echo "Options:"
            echo "  --data-dir DIR        Directory containing datasets (default: datasets)"
            echo "  --datasets FILE       Datasets JSON file (default: datasets.json)"
            echo "  --sweeps FILE         Sweeps JSON file (default: sweeps.json)"
            echo "  --configs-dir DIR     Directory to store generated configs (default: configs)"
            echo "  --results-dir DIR     Directory to store benchmark results (default: results/sweep_<timestamp>)"
            echo "  --run-benchmarks      Run benchmarks after generating configs"
            echo "  --help               Show this help message"
            exit 0
            ;;
        *) echo "Unknown option: $OPTARG"; exit 1 ;;
    esac
done

# Set defaults
DATA_DIR=${DATA_DIR:-datasets}
DATASETS_FILE=${DATASETS_FILE:-datasets.json}
SWEEPS_FILE=${SWEEPS_FILE:-sweeps.json}
CONFIGS_DIR=${CONFIGS_DIR:-configs}
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
RESULTS_DIR=${RESULTS_DIR:-results/sweep_${TIMESTAMP}}
RUN_BENCHMARKS=${RUN_BENCHMARKS:-false}

echo "Configuration:"
echo "  Data directory: $DATA_DIR"
echo "  Datasets file: $DATASETS_FILE"
echo "  Sweeps file: $SWEEPS_FILE"
echo "  Configs directory: $CONFIGS_DIR"
echo "  Results directory: $RESULTS_DIR"
echo "  Run benchmarks: $RUN_BENCHMARKS"
echo ""

# Prepare datasets
./prepare-datasets.sh --data-dir "$DATA_DIR" --datasets "$DATASETS_FILE" || exit 1

# Generate configurations
python3 generate-combinations.py --data-dir "$DATA_DIR" --datasets "$DATASETS_FILE" --sweeps "$SWEEPS_FILE" --configs-dir "$CONFIGS_DIR" || exit 1

# Run benchmarks if requested
if [ "$RUN_BENCHMARKS" = "true" ]; then
    echo ""
    echo "========================================="
    echo "Starting benchmark sweep execution"
    echo "========================================="
    
    # Compile the project first
    echo "Compiling project..."
    mvn clean compile || { echo "Maven compilation failed"; exit 1; }
    
    # Create results directory structure
    mkdir -p "$RESULTS_DIR"
    
    # Save sweep configuration for reference
    cp "$SWEEPS_FILE" "$RESULTS_DIR/sweeps.json"
    cp "$DATASETS_FILE" "$RESULTS_DIR/datasets.json"
    
    # Count total configs
    TOTAL_CONFIGS=$(find "$CONFIGS_DIR" -name "*.json" -type f | wc -l)
    CURRENT_CONFIG=0
    
    echo "Found $TOTAL_CONFIGS configurations to run"
    echo ""
    
    # Create a summary file
    SUMMARY_FILE="$RESULTS_DIR/summary.txt"
    echo "Benchmark Sweep Summary" > "$SUMMARY_FILE"
    echo "Started at: $(date)" >> "$SUMMARY_FILE"
    echo "Total configurations: $TOTAL_CONFIGS" >> "$SUMMARY_FILE"
    echo "" >> "$SUMMARY_FILE"
    
    # Iterate through all sweep directories
    for SWEEP_DIR in "$CONFIGS_DIR"/*; do
        if [ -d "$SWEEP_DIR" ]; then
            SWEEP_NAME=$(basename "$SWEEP_DIR")
            echo "Processing sweep: $SWEEP_NAME"
            
            # Create results subdirectory for this sweep
            SWEEP_RESULTS_DIR="$RESULTS_DIR/$SWEEP_NAME"
            mkdir -p "$SWEEP_RESULTS_DIR"
            
            # Run each configuration in the sweep
            for CONFIG_FILE in "$SWEEP_DIR"/*.json; do
                if [ -f "$CONFIG_FILE" ]; then
                    CURRENT_CONFIG=$((CURRENT_CONFIG + 1))
                    CONFIG_NAME=$(basename "$CONFIG_FILE" .json)
                    
                    echo ""
                    echo "[$CURRENT_CONFIG/$TOTAL_CONFIGS] Running: $SWEEP_NAME/$CONFIG_NAME"
                    echo "-----------------------------------------"
                    
                    # Create a directory for this specific config's results
                    CONFIG_RESULTS_DIR="$SWEEP_RESULTS_DIR/$CONFIG_NAME"
                    mkdir -p "$CONFIG_RESULTS_DIR"
                    
                    # Copy the config file to results for reference
                    cp "$CONFIG_FILE" "$CONFIG_RESULTS_DIR/config.json"
                    
                    # Run the benchmark and capture output
                    LOG_FILE="$CONFIG_RESULTS_DIR/benchmark.log"
                    echo "Starting benchmark at $(date)" > "$LOG_FILE"
                    
                    # Run benchmark with Maven, redirecting output to log file
                    if mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
                        -Dexec.args="$CONFIG_FILE" \
                        -Dexec.jvmArgs="--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED" \
                        >> "$LOG_FILE" 2>&1; then
                        
                        echo "✓ Benchmark completed successfully"
                        echo "$SWEEP_NAME/$CONFIG_NAME: SUCCESS" >> "$SUMMARY_FILE"
                        
                        # Copy the results.json if it exists
                        if [ -f "results/results.json" ]; then
                            cp "results/results.json" "$CONFIG_RESULTS_DIR/results.json"
                        fi
                        
                        # Copy any CSV files generated
                        if ls results/*.csv 1> /dev/null 2>&1; then
                            cp results/*.csv "$CONFIG_RESULTS_DIR/" 2>/dev/null || true
                        fi
                    else
                        echo "✗ Benchmark failed (check log for details)"
                        echo "$SWEEP_NAME/$CONFIG_NAME: FAILED" >> "$SUMMARY_FILE"
                    fi
                    
                    echo "Log saved to: $LOG_FILE"
                fi
            done
        fi
    done
    
    echo ""
    echo "========================================="
    echo "Benchmark sweep completed"
    echo "Results saved to: $RESULTS_DIR"
    echo "Summary saved to: $SUMMARY_FILE"
    echo "Finished at: $(date)"
    echo "========================================="
    
    # Add completion timestamp to summary
    echo "" >> "$SUMMARY_FILE"
    echo "Completed at: $(date)" >> "$SUMMARY_FILE"
    
    # Generate a consolidated results JSON if possible
    echo ""
    echo "Generating consolidated results..."
    python3 - << 'EOF' "$RESULTS_DIR"
import json
import os
import sys
from pathlib import Path

results_dir = Path(sys.argv[1])
consolidated = {"sweeps": {}}

for sweep_dir in results_dir.iterdir():
    if sweep_dir.is_dir() and sweep_dir.name not in ['summary.txt']:
        sweep_name = sweep_dir.name
        consolidated["sweeps"][sweep_name] = {}
        
        for config_dir in sweep_dir.iterdir():
            if config_dir.is_dir():
                config_name = config_dir.name
                results_file = config_dir / "results.json"
                
                if results_file.exists():
                    with open(results_file) as f:
                        consolidated["sweeps"][sweep_name][config_name] = json.load(f)

# Save consolidated results
with open(results_dir / "consolidated_results.json", "w") as f:
    json.dump(consolidated, f, indent=2)

print(f"Consolidated results saved to: {results_dir}/consolidated_results.json")
EOF
else
    echo ""
    echo "Configurations generated successfully in: $CONFIGS_DIR"
    echo "To run benchmarks, use: $0 --run-benchmarks --results-dir <output_dir>"
fi

