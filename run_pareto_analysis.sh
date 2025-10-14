#!/bin/bash

# NVIDIA Pareto Analysis Workflow
# Converts benchmark results to NVIDIA format, runs Pareto analysis, and generates plots

set -e

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <sweep_id> <dataset_name>"
    echo "Example: $0 3cNWY5 wiki10m"
    exit 1
fi

SWEEP_ID="$1"
DATASET_NAME="$2"

INPUT_DIR="results/${SWEEP_ID}"
OUTPUT_DIR="results/${SWEEP_ID}/${DATASET_NAME}"
PLOTS_DIR="results/${SWEEP_ID}/${DATASET_NAME}"
INTERMEDIATE_DIR="results/${SWEEP_ID}/intermediate-files"

echo "Processing sweep: ${SWEEP_ID}, dataset: ${DATASET_NAME}"

# Clean previous output (but preserve original results)
rm -rf "${INTERMEDIATE_DIR}" "${OUTPUT_DIR}/plots"

# Convert to NVIDIA JSON format
echo "Converting results to NVIDIA format..."
python convert_to_nvidia_format.py --sweep-dir "${INPUT_DIR}/${DATASET_NAME}" --output-dir "${INTERMEDIATE_DIR}" --dataset "${DATASET_NAME}"

# Generate CSVs using NVIDIA data_export
echo "Generating Pareto frontier CSVs..."
python -c "
import sys
sys.path.append('.')
from data_export import convert_json_to_csv_search, convert_json_to_csv_build
convert_json_to_csv_search('${DATASET_NAME}', '${INTERMEDIATE_DIR}')
convert_json_to_csv_build('${DATASET_NAME}', '${INTERMEDIATE_DIR}')
"

if [ $? -ne 0 ]; then
    echo "Error: NVIDIA data_export.py failed. Exiting."
    exit 1
fi

# Extract parameters from first results.json
FIRST_RESULTS=$(find "${INPUT_DIR}" -name "results.json" | head -1)
K=$(python -c "import json; print(json.load(open('${FIRST_RESULTS}'))['configuration']['topK'])")
N_QUERIES=$(python -c "import json; print(json.load(open('${FIRST_RESULTS}'))['configuration']['numQueriesToRun'])")

# Clean up: Delete JSON files after CSV generation
echo "Cleaning up intermediate JSON files..."
rm -f "${INTERMEDIATE_DIR}/${DATASET_NAME}"/*.json

# Create the directory structure expected by plot_pareto.py
echo "Creating directory structure for plotting..."
mkdir -p "${INTERMEDIATE_DIR}/${DATASET_NAME}/result/search"
mkdir -p "${INTERMEDIATE_DIR}/${DATASET_NAME}/result/build"

# Move and rename CSV files to match the expected format for plot_pareto.py
if [ -d "${INTERMEDIATE_DIR}/${DATASET_NAME}" ]; then
    cd "${INTERMEDIATE_DIR}/${DATASET_NAME}"
    
    # Move search CSVs to result/search/ and rename them
    for file in *throughput.csv *latency.csv *raw.csv; do
        if [ -f "$file" ]; then
            if [[ "$file" == *",raw.csv" ]]; then
                mv "$file" "result/search/${file%,raw.csv},k${K},bs${N_QUERIES},raw.csv"
            elif [[ "$file" == *",throughput.csv" ]]; then
                mv "$file" "result/search/${file%,throughput.csv},k${K},bs${N_QUERIES},throughput.csv"
            elif [[ "$file" == *",latency.csv" ]]; then
                mv "$file" "result/search/${file%,latency.csv},k${K},bs${N_QUERIES},latency.csv"
            fi
        fi
    done
    
    # Move build CSVs to result/build/
    for file in *.csv; do
        if [ -f "$file" ]; then
            mv "$file" "result/build/"
        fi
    done
    
    cd - > /dev/null
fi

echo "Parameters: k=${K}, n_queries=${N_QUERIES}"

# Create plots directory
mkdir -p "${PLOTS_DIR}/plots"

# Generate plots
echo "Generating plots..."
python plot_pareto.py --dataset "${DATASET_NAME}" --dataset-path "${INTERMEDIATE_DIR}" --mode throughput --count "${K}" --n-queries "${N_QUERIES}" --output-filepath "${PLOTS_DIR}/plots" --search
mv "${PLOTS_DIR}/plots/search-${DATASET_NAME}-k${K}-n_queries${N_QUERIES}.png" "${PLOTS_DIR}/plots/throughput-${DATASET_NAME}-k${K}-n_queries${N_QUERIES}.png"

python plot_pareto.py --dataset "${DATASET_NAME}" --dataset-path "${INTERMEDIATE_DIR}" --mode latency --count "${K}" --n-queries "${N_QUERIES}" --output-filepath "${PLOTS_DIR}/plots" --search
mv "${PLOTS_DIR}/plots/search-${DATASET_NAME}-k${K}-n_queries${N_QUERIES}.png" "${PLOTS_DIR}/plots/latency-${DATASET_NAME}-k${K}-n_queries${N_QUERIES}.png"

python plot_pareto.py --dataset "${DATASET_NAME}" --dataset-path "${INTERMEDIATE_DIR}" --mode throughput --count "${K}" --n-queries "${N_QUERIES}" --output-filepath "${PLOTS_DIR}/plots" --build

echo "Complete! Output saved to: ${OUTPUT_DIR}"
echo "Intermediate files (JSON + CSV): ${INTERMEDIATE_DIR}/${DATASET_NAME}/"
echo "Plots: ${OUTPUT_DIR}/plots/"
ls -la "${OUTPUT_DIR}/plots"/*.png
