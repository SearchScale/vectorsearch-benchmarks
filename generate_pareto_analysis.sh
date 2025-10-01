#!/bin/bash

set -e

BENCHMARK_ID=""
RESULTS_DIR=""
DATASETS_FILE=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --benchmark-id)
            BENCHMARK_ID="$2"
            shift 2
            ;;
        --results-dir)
            RESULTS_DIR="$2"
            shift 2
            ;;
        --datasets-file)
            DATASETS_FILE="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

if [ -z "$BENCHMARK_ID" ] || [ -z "$RESULTS_DIR" ] || [ -z "$DATASETS_FILE" ]; then
    echo "Usage: $0 --benchmark-id <id> --results-dir <dir> --datasets-file <file>"
    exit 1
fi

SWEEP_RESULTS_DIR="$RESULTS_DIR/$BENCHMARK_ID"
PARETO_DATA_DIR="$RESULTS_DIR/pareto_data/$BENCHMARK_ID"
PLOTS_DIR="$RESULTS_DIR/plots/$BENCHMARK_ID"

if [ ! -d "$SWEEP_RESULTS_DIR" ] || [ ! -f "$DATASETS_FILE" ]; then
    echo "Error: Required directories/files not found"
    exit 1
fi

echo "Generating Pareto analysis for: $BENCHMARK_ID"

# Extract dataset names from datasets.json
DATASETS=$(jq -r '.datasets | keys[]' "$DATASETS_FILE")
if [ -z "$DATASETS" ]; then
    echo "Error: No datasets found in $DATASETS_FILE"
    exit 1
fi

# Detect datasets from actual sweep results
DETECTED_DATASETS=$(find "$SWEEP_RESULTS_DIR" -maxdepth 1 -type d ! -name ".*" ! -name "$(basename "$SWEEP_RESULTS_DIR")" | sed 's|.*/||' | sort | uniq)

# Create output directories
mkdir -p "$PARETO_DATA_DIR"
mkdir -p "$PLOTS_DIR"

# Process each detected dataset
for dataset_dir in $DETECTED_DATASETS; do
    echo "Processing dataset: $dataset_dir"
    
    # Map dataset names
    case "$dataset_dir" in
        "wiki10m") dataset_name="wiki-10m" ;;
        "sift-1m") dataset_name="sift-1m" ;;
        *) dataset_name="$dataset_dir" ;;
    esac
    
    dataset_results_dir="$SWEEP_RESULTS_DIR/$dataset_dir"
    if [ ! -d "$dataset_results_dir" ]; then
        echo "  No results found for dataset: $dataset_dir"
        continue
    fi
    
    # Count available results
    result_count=$(find "$dataset_results_dir" -name "results.json" 2>/dev/null | wc -l)
    echo "  Found $result_count result files"
    
    if [ "$result_count" -eq 0 ]; then
        echo "  No result files found, skipping dataset: $dataset_dir"
        continue
    fi
    
    dataset_pareto_dir="$PARETO_DATA_DIR/$dataset_dir"
    dataset_plots_dir="$PLOTS_DIR"
    
    echo "  Converting JSON results to CSV format..."
    
    # Convert JSON to CSV
    if python3 convert_to_pareto_format.py \
        --input-dir "$dataset_results_dir" \
        --output-dir "$dataset_pareto_dir" \
        --dataset-name "$dataset_dir" \
        --json-filename "results.json" \
        --datasets-file "$DATASETS_FILE"; then
        
        echo "  CSV conversion completed"
        
        # Extract parameters from the first result file for plotting
        first_result=$(find "$dataset_results_dir" -name "results.json" | head -1)
        if [ -f "$first_result" ]; then
            k=$(jq -r '.configuration.topK' "$first_result")
            batch_size=$(jq -r '.configuration.numQueriesToRun' "$first_result")
            
            echo "  Parameters: k=$k, batch_size=$batch_size"
            
            # Save metadata
            jq -n --arg k "$k" --arg batch_size "$batch_size" --arg dataset "$dataset" --arg dataset_dir "$dataset_dir" \
                '{dataset: $dataset, dataset_dir: $dataset_dir, k: ($k | tonumber), batch_size: ($batch_size | tonumber), generated_at: now}' \
                > "$dataset_pareto_dir/metadata.json"
            
            echo "  Generating Pareto plots..."
            
            # Generate plots
            python3 plot_pareto.py --dataset "$dataset_dir" --dataset-path "$dataset_pareto_dir" \
                --output-filepath "$dataset_plots_dir/latency" --mode latency --time-unit ms \
                --search --count "$k" --batch-size "$batch_size" --x-start 0.8 && echo "  Latency plot generated"
            
            python3 plot_pareto.py --dataset "$dataset_dir" --dataset-path "$dataset_pareto_dir" \
                --output-filepath "$dataset_plots_dir/throughput" --mode throughput \
                --search --count "$k" --batch-size "$batch_size" --x-start 0.8 && echo "  Throughput plot generated"
            
            python3 plot_pareto.py --dataset "$dataset_dir" --dataset-path "$dataset_pareto_dir" \
                --output-filepath "$dataset_plots_dir/build_time" --build \
                --count "$k" --batch-size "$batch_size" && echo "  Build time plot generated"
            
        else
            echo "  No result files found for parameter extraction"
        fi
        
    else
        echo "  CSV conversion failed"
    fi
done

echo ""
echo "Pareto analysis completed for: $BENCHMARK_ID"
echo "CSV data saved to: $PARETO_DATA_DIR"
echo "Plots saved to: $PLOTS_DIR"

# Create summary file
SUMMARY_FILE="$PLOTS_DIR/pareto_analysis_summary.txt"
cat > "$SUMMARY_FILE" << EOF
Pareto Analysis Summary
======================
Benchmark ID: $BENCHMARK_ID
Generated at: $(date)
Results directory: $SWEEP_RESULTS_DIR
CSV data directory: $PARETO_DATA_DIR
Plots directory: $PLOTS_DIR

Datasets processed:
$(for dataset_dir in $DETECTED_DATASETS; do
    if [ -d "$SWEEP_RESULTS_DIR/$dataset_dir" ]; then
        result_count=$(find "$SWEEP_RESULTS_DIR/$dataset_dir" -name "results.json" 2>/dev/null | wc -l)
        echo "  - $dataset_dir ($result_count results)"
    fi
done)

Note: This analysis includes partial data from incomplete sweeps.
EOF

echo "Summary saved to: $SUMMARY_FILE"
