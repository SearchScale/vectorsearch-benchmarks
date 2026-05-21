#!/bin/bash

# NVIDIA Pareto Analysis Workflow
# Converts benchmark results to NVIDIA format, runs Pareto analysis, and generates plots.
#
# Usage:
#   ./run_pareto_analysis.sh <sweep_id> <output_label> [sweep_subdir1,sweep_subdir2,...]
#
# Arguments:
#   sweep_id      - The benchmark run ID (e.g. mfZE9B)
#   output_label  - Label used for output files and directories (e.g. wiki_10m)
#   sweep_subdirs - Optional comma-separated list of sweep subdirectory names to combine
#                   (e.g. wiki10m_cagra_nn,wiki10m_cagra_ivfpq,wiki10m_lucene_hnsw).
#                   Defaults to <output_label> for backward compatibility with single-algo runs.
#
# Example (combined, 3 algorithms):
#   ./run_pareto_analysis.sh mfZE9B wiki_10m wiki10m_cagra_nn,wiki10m_cagra_ivfpq,wiki10m_lucene_hnsw
#
# Example (single algorithm, backward compat):
#   ./run_pareto_analysis.sh mfZE9B wiki10m_cagra_nn

set -e

cd "$(dirname "$0")" || exit 1

if [ "$#" -lt 2 ]; then
    echo "Usage: $0 <sweep_id> <output_label> [sweep_subdir1,sweep_subdir2,...]"
    exit 1
fi

SWEEP_ID="$1"
DATASET_NAME="$2"
# Third arg is comma-separated sweep subdirs; default to DATASET_NAME (backward compat)
SWEEP_DIRS_CSV="${3:-$DATASET_NAME}"

INPUT_DIR="results/${SWEEP_ID}"
OUTPUT_DIR="results/${SWEEP_ID}/${DATASET_NAME}"
INTERMEDIATE_DIR="results/${SWEEP_ID}/intermediate-files"
RESULTS_DIR="results"

echo "Processing sweep: ${SWEEP_ID}, output label: ${DATASET_NAME}"
echo "Sweep subdirs to combine: ${SWEEP_DIRS_CSV}"

rm -rf "${INTERMEDIATE_DIR}" "${OUTPUT_DIR}/plots"

echo "Converting results to NVIDIA format..."
IFS=',' read -ra SWEEP_SUBDIRS <<< "${SWEEP_DIRS_CSV}"
for sweep_subdir in "${SWEEP_SUBDIRS[@]}"; do
    sweep_path="${INPUT_DIR}/${sweep_subdir}"
    if [ -d "$sweep_path" ]; then
        result_count=$(find "$sweep_path" -name "results.json" 2>/dev/null | wc -l)
        if [ "$result_count" -gt 0 ]; then
            echo "  Converting ${sweep_subdir} (${result_count} results)..."
            python3 convert_to_nvidia_format.py \
                --sweep-dir "$sweep_path" \
                --output-dir "${INTERMEDIATE_DIR}" \
                --dataset "${DATASET_NAME}"
        else
            echo "  Skipping ${sweep_subdir}: no results.json files found"
        fi
    else
        echo "  Skipping ${sweep_subdir}: directory not found"
    fi
done

echo "Generating Pareto frontier CSVs..."
python3 -c "
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

# Grab k and n_queries from any available results.json across all sweep subdirs
FIRST_RESULTS=""
for sweep_subdir in "${SWEEP_SUBDIRS[@]}"; do
    sweep_path="${INPUT_DIR}/${sweep_subdir}"
    if [ -d "$sweep_path" ]; then
        candidate=$(find "$sweep_path" -name "results.json" | head -1)
        if [ -n "$candidate" ]; then
            FIRST_RESULTS="$candidate"
            break
        fi
    fi
done

if [ -z "$FIRST_RESULTS" ]; then
    echo "Error: No results.json found in any sweep subdir."
    exit 1
fi

K=$(python3 -c "import json; print(json.load(open('${FIRST_RESULTS}'))['configuration']['topK'])")
N_QUERIES=$(python3 -c "import json; print(json.load(open('${FIRST_RESULTS}'))['configuration']['numQueriesToRun'])")

echo "Creating directory structure for plotting..."
mkdir -p "${INTERMEDIATE_DIR}/${DATASET_NAME}/result/search"
mkdir -p "${INTERMEDIATE_DIR}/${DATASET_NAME}/result/build"

if [ -d "${INTERMEDIATE_DIR}/${DATASET_NAME}" ]; then
    cd "${INTERMEDIATE_DIR}/${DATASET_NAME}"

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

    for file in *.csv; do
        if [ -f "$file" ]; then
            mv "$file" "result/build/"
        fi
    done

    cd - > /dev/null
fi

echo "Generating is_pareto files for Pareto optimal runs..."
python3 << PYEOF
import os
import csv
import json
import glob

def get_algorithm_label(config):
    """Return the same algorithm label that convert_to_nvidia_format.py produces."""
    algo = config.get('algoToRun', 'UNKNOWN')
    if algo in ['cagra_hnsw', 'CAGRA_HNSW']:
        build_algo = config.get('cuvsCagraGraphBuildAlgo', 'NN_DESCENT')
        return 'CAGRA_IVF_PQ' if build_algo == 'IVF_PQ' else 'CAGRA_NN_DESCENT'
    elif algo in ['hnsw', 'LUCENE_HNSW']:
        return 'LUCENE_HNSW'
    return algo

def create_index_name_from_config(config):
    algorithm = config.get('algoToRun', 'UNKNOWN')
    ef_search = config.get('efSearch', 0)
    if algorithm in ['LUCENE_HNSW', 'hnsw']:
        beam_width = config.get('hnswBeamWidth', 0)
        max_conn = config.get('hnswMaxConn', 0)
        return f'beam{beam_width}-conn{max_conn}-ef{ef_search}'
    elif algorithm in ['CAGRA_HNSW', 'cagra_hnsw']:
        graph_degree = config.get('cagraGraphDegree', 0)
        intermediate_degree = config.get('cagraIntermediateGraphDegree', 0)
        return f'ef{ef_search}-deg{graph_degree}-ideg{intermediate_degree}'
    else:
        return f'ef{ef_search}'

intermediate_dir = '${INTERMEDIATE_DIR}/${DATASET_NAME}'
base_results_dir = '${RESULTS_DIR}/${SWEEP_ID}'
sweep_subdirs = '${SWEEP_DIRS_CSV}'.split(',')

csv_patterns = [
    f'{intermediate_dir}/result/search/*throughput.csv',
    f'{intermediate_dir}/result/search/*latency.csv'
]

pareto_runs_by_algo = {}

for pattern in csv_patterns:
    csv_files = glob.glob(pattern)
    for csv_file in csv_files:
        algorithm = os.path.basename(csv_file).split(',')[0]
        with open(csv_file, 'r') as f:
            reader = csv.DictReader(f)
            pareto_runs = list(reader)
        if algorithm not in pareto_runs_by_algo:
            pareto_runs_by_algo[algorithm] = {}
        for pareto_run in pareto_runs:
            index_name = pareto_run['index_name']
            if index_name not in pareto_runs_by_algo[algorithm]:
                pareto_runs_by_algo[algorithm][index_name] = pareto_run

print(f'Found Pareto optimal runs from CSV files:')
for algo, runs in pareto_runs_by_algo.items():
    print(f'  {algo}: {len(runs)} unique configurations')

for algorithm, pareto_indices in pareto_runs_by_algo.items():
    print(f'\\nProcessing {algorithm}...')

    # Collect benchmark dirs from ALL sweep subdirs
    benchmark_dirs = []
    for sweep_subdir in sweep_subdirs:
        results_dir = os.path.join(base_results_dir, sweep_subdir.strip())
        if not os.path.isdir(results_dir):
            continue
        for variant in [algorithm, algorithm.upper(), algorithm.lower()]:
            benchmark_dirs.extend(glob.glob(f'{results_dir}/{variant}-*'))
        if algorithm == 'CAGRA_NN_DESCENT':
            benchmark_dirs.extend(glob.glob(f'{results_dir}/cagra_hnsw-*'))
        elif algorithm == 'CAGRA_IVF_PQ':
            benchmark_dirs.extend(glob.glob(f'{results_dir}/cagra_hnsw-*'))
        elif algorithm == 'CAGRA_HNSW':
            benchmark_dirs.extend(glob.glob(f'{results_dir}/cagra_hnsw-*'))
        elif algorithm == 'LUCENE_HNSW':
            benchmark_dirs.extend(glob.glob(f'{results_dir}/hnsw-*'))

    benchmark_dirs = list(set(benchmark_dirs))
    print(f'Found {len(benchmark_dirs)} candidate result directories')

    index_to_dir = {}
    for benchmark_dir in benchmark_dirs:
        results_json_path = os.path.join(benchmark_dir, 'results.json')
        if os.path.exists(results_json_path):
            try:
                with open(results_json_path, 'r') as f:
                    results_data = json.load(f)
                config = results_data['configuration']
                if get_algorithm_label(config) == algorithm:
                    index_name = create_index_name_from_config(config)
                    if index_name not in index_to_dir:
                        index_to_dir[index_name] = benchmark_dir
            except Exception as e:
                print(f'  Error processing {benchmark_dir}: {e}')

    print(f'Mapped {len(index_to_dir)} configurations')

    matched = 0
    unmatched = 0
    for index_name, pareto_run in pareto_indices.items():
        if index_name in index_to_dir:
            benchmark_dir = index_to_dir[index_name]
            is_pareto_file = os.path.join(benchmark_dir, 'is_pareto')
            with open(is_pareto_file, 'w') as f:
                f.write(f'Pareto optimal run\\n')
                f.write(f'Algorithm: {algorithm}\\n')
                f.write(f'Index: {index_name}\\n')
                f.write(f'Recall: {pareto_run["recall"]}\\n')
                f.write(f'Throughput: {pareto_run["throughput"]}\\n')
                f.write(f'Latency: {pareto_run["latency"]}\\n')
            matched += 1
        else:
            unmatched += 1

    print(f'Matched {matched}/{len(pareto_indices)} runs')

print('\\nPareto file generation complete')
PYEOF

echo "Parameters: k=${K}, n_queries=${N_QUERIES}"

mkdir -p "${OUTPUT_DIR}/plots"

# Write metadata.json so the web UI knows the exact k and n_queries for plot filenames
echo "{\"k\": ${K}, \"n_queries\": ${N_QUERIES}}" > "${OUTPUT_DIR}/metadata.json"

echo "Generating plots..."
python3 plot_pareto.py --dataset "${DATASET_NAME}" --dataset-path "${INTERMEDIATE_DIR}" --mode throughput --count "${K}" --n-queries "${N_QUERIES}" --output-filepath "${OUTPUT_DIR}/plots" --search
mv "${OUTPUT_DIR}/plots/search-${DATASET_NAME}-k${K}-n_queries${N_QUERIES}.png" "${OUTPUT_DIR}/plots/throughput-${DATASET_NAME}-k${K}-n_queries${N_QUERIES}.png"

python3 plot_pareto.py --dataset "${DATASET_NAME}" --dataset-path "${INTERMEDIATE_DIR}" --mode latency --count "${K}" --n-queries "${N_QUERIES}" --output-filepath "${OUTPUT_DIR}/plots" --search
mv "${OUTPUT_DIR}/plots/search-${DATASET_NAME}-k${K}-n_queries${N_QUERIES}.png" "${OUTPUT_DIR}/plots/latency-${DATASET_NAME}-k${K}-n_queries${N_QUERIES}.png"

python3 plot_pareto.py --dataset "${DATASET_NAME}" --dataset-path "${INTERMEDIATE_DIR}" --mode throughput --count "${K}" --n-queries "${N_QUERIES}" --output-filepath "${OUTPUT_DIR}/plots" --build

echo "Complete! Output saved to: ${OUTPUT_DIR}"
echo "Plots: ${OUTPUT_DIR}/plots/"
ls -la "${OUTPUT_DIR}/plots"/*.png

echo ""
echo "Cleaning up intermediate files..."
rm -rf "${INTERMEDIATE_DIR}"
echo "Intermediate files cleaned up!"
echo ""
echo "Final output:"
echo "- Pareto optimal runs marked with is_pareto files"
echo "- Plots: ${OUTPUT_DIR}/plots/"
echo "- No intermediate files (completely cleaned up)"
