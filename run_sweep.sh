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
RESULTS_DIR=${RESULTS_DIR:-results}
RUN_BENCHMARKS=${RUN_BENCHMARKS:-false}

BENCHMARKID=$(head /dev/urandom | tr -dc A-Za-z0-9 | head -c 6)
RESULTS_DIR=${RESULTS_DIR}/$BENCHMARKID

echo "Configuration:"
echo "  Data directory: $DATA_DIR"
echo "  Datasets file: $DATASETS_FILE"
echo "  Sweeps file: $SWEEPS_FILE"
echo "  Configs directory: $CONFIGS_DIR"
echo "  Results directory: $RESULTS_DIR"
echo "  Run benchmarks: $RUN_BENCHMARKS"
echo "  BenchmarkID: $BENCHMARKID"
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
            
            # Run each configuration in the sweep in creation time order (oldest first)
	    for CONFIG_FILE in $(find "$SWEEP_DIR" -name "*.json" | awk -F'[/-]' '{split($NF, a, "."); gsub("ef", "", a[1]); print $(NF-2)"-"$(NF-1), a[1], $0}' | sort -k1,1 -k2,2nr | cut -d' ' -f3); do
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
                    # Pass CONFIG_RESULTS_DIR as third argument to Java program
                    if mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
                        -Dexec.args="$CONFIG_FILE $BENCHMARKID $CONFIG_RESULTS_DIR" \
                        -Dexec.jvmArgs="--add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED" \
                        >> "$LOG_FILE" 2>&1; then
                        
                        echo "✓ Benchmark completed successfully"
                        echo "$SWEEP_NAME/$CONFIG_NAME: SUCCESS" >> "$SUMMARY_FILE"
                        
                        # Results are now written directly to CONFIG_RESULTS_DIR by the Java program
                        echo "Results saved directly to: $CONFIG_RESULTS_DIR"
                        
                        # Backfill indexing metrics if needed for this specific run
                        results_file="$CONFIG_RESULTS_DIR/results.json"
                        if [ -f "$results_file" ] && grep -q '"skipIndexing" : true' "$results_file"; then
                            index_hash=$(echo "$CONFIG_NAME" | sed -E 's/.*-([a-f0-9]{8})(-.+)?$/\1/')
                            if [ ${#index_hash} -eq 8 ]; then
                                algo=$(jq -r '.configuration.algoToRun' "$results_file")
                                if [ "$algo" = "LUCENE_HNSW" ] && ! jq -e '.metrics["hnsw-indexing-time"]' "$results_file" >/dev/null 2>&1; then
                                    metric_type="hnsw"
                                elif [ "$algo" = "CAGRA_HNSW" ] && ! jq -e '.metrics["cuvs-indexing-time"]' "$results_file" >/dev/null 2>&1; then
                                    metric_type="cuvs"
                                else
                                    metric_type=""
                                fi
                                
                                if [ -n "$metric_type" ]; then
                                    source_file=""
                                    for candidate_dir in $(find "$RESULTS_DIR" -name "*$index_hash*" -type d); do
                                        if [ -f "$candidate_dir/results.json" ] && grep -q '"skipIndexing" : false' "$candidate_dir/results.json" && grep -q "${metric_type}-indexing-time" "$candidate_dir/results.json"; then
                                            source_file="$candidate_dir/results.json"
                                            break
                                        fi
                                    done
                                    if [ -n "$source_file" ]; then
                                        indexing_time=$(jq -r ".metrics[\"${metric_type}-indexing-time\"]" "$source_file")
                                        index_size=$(jq -r ".metrics[\"${metric_type}-index-size\"]" "$source_file")
                                        jq --argjson time "$indexing_time" --argjson size "$index_size" \
                                           ".metrics[\"${metric_type}-indexing-time\"] = \$time | .metrics[\"${metric_type}-index-size\"] = \$size" \
                                           "$results_file" > "${results_file}.tmp" && mv "${results_file}.tmp" "$results_file"
                                        echo "  Backfilled $CONFIG_NAME ($metric_type) from $(basename $(dirname "$source_file"))"
                                    fi
                                fi
                            fi
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
    
    # Update sweeps-list.json in the parent results directory
    PARENT_RESULTS_DIR=$(dirname "$RESULTS_DIR")
    SWEEPS_LIST_FILE="$PARENT_RESULTS_DIR/sweeps-list.json"
    
    if [ -f "$SWEEPS_LIST_FILE" ]; then
        # File exists, append the benchmark ID using jq
        jq --arg id "$BENCHMARKID" '.sweeps += [$id]' "$SWEEPS_LIST_FILE" > "${SWEEPS_LIST_FILE}.tmp" && mv "${SWEEPS_LIST_FILE}.tmp" "$SWEEPS_LIST_FILE"
        echo "Updated sweeps-list.json with benchmark ID: $BENCHMARKID"
    else
        # File doesn't exist, create it with the current benchmark ID
        jq -n --arg id "$BENCHMARKID" '{sweeps: [$id]}' > "$SWEEPS_LIST_FILE"
        echo "Created sweeps-list.json with benchmark ID: $BENCHMARKID"
    fi
    
else
    echo ""
    echo "Configurations generated successfully in: $CONFIGS_DIR"
    echo "To run benchmarks, use: $0 --run-benchmarks --results-dir <output_dir>"
fi

