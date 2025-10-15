#!/bin/bash

# NVIDIA Pareto Analysis Workflow
# Converts benchmark results to NVIDIA format, runs Pareto analysis, and generates plots

set -e

cd "$(dirname "$0")" || exit 1

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <sweep_id> <dataset_name>"
    echo "Example: $0 3cNWY5 wiki10m"
    exit 1
fi

SWEEP_ID="$1"
DATASET_NAME="$2"

INPUT_DIR="results/${SWEEP_ID}"
OUTPUT_DIR="results/${SWEEP_ID}/${DATASET_NAME}"
INTERMEDIATE_DIR="results/${SWEEP_ID}/intermediate-files"
RESULTS_DIR="results"

echo "Processing sweep: ${SWEEP_ID}, dataset: ${DATASET_NAME}"

rm -rf "${INTERMEDIATE_DIR}" "${OUTPUT_DIR}/plots"

echo "Converting results to NVIDIA format..."
python3 convert_to_nvidia_format.py --sweep-dir "${INPUT_DIR}/${DATASET_NAME}" --output-dir "${INTERMEDIATE_DIR}" --dataset "${DATASET_NAME}"

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

FIRST_RESULTS=$(find "${INPUT_DIR}" -name "results.json" | head -1)
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
python3 -c "
import os
import csv
import json
import glob

intermediate_dir = '${INTERMEDIATE_DIR}/${DATASET_NAME}'
results_dir = '${RESULTS_DIR}/${SWEEP_ID}/${DATASET_NAME}'

csv_files = glob.glob(f'{intermediate_dir}/result/search/*throughput.csv')
for csv_file in csv_files:
    algorithm = os.path.basename(csv_file).split(',')[0]
    print(f'Processing {algorithm} Pareto runs...')
    
    with open(csv_file, 'r') as f:
        reader = csv.DictReader(f)
        pareto_runs = list(reader)
    
    benchmark_dirs = glob.glob(f'{results_dir}/{algorithm}-*')
    if not benchmark_dirs:
        if algorithm == 'CAGRA_HNSW':
            benchmark_dirs = glob.glob(f'{results_dir}/CAGRA_HNSW-*') + glob.glob(f'{results_dir}/cagra_hnsw-*')
        elif algorithm == 'LUCENE_HNSW':
            benchmark_dirs = glob.glob(f'{results_dir}/LUCENE_HNSW-*') + glob.glob(f'{results_dir}/hnsw-*')
    
    all_runs = []
    print(f'Processing {len(benchmark_dirs)} directories for {algorithm}')
    for benchmark_dir in benchmark_dirs:
        results_json_path = os.path.join(benchmark_dir, 'results.json')
        if os.path.exists(results_json_path):
            try:
                with open(results_json_path, 'r') as f:
                    results_data = json.load(f)
                
                config = results_data['configuration']
                metrics = results_data['metrics']
                
                algo_to_run = config.get('algoToRun')
                algorithm_match = False
                if algorithm == 'CAGRA_HNSW' and algo_to_run in ['CAGRA_HNSW', 'cagra_hnsw']:
                    algorithm_match = True
                elif algorithm == 'LUCENE_HNSW' and algo_to_run in ['LUCENE_HNSW', 'hnsw']:
                    algorithm_match = True
                
                if algorithm_match:
                    recall_key = next((key for key in metrics.keys() if 'recall' in key.lower()), None)
                    if recall_key:
                        run_recall = float(metrics[recall_key]) / 100.0
                        all_runs.append({
                            'dir': benchmark_dir,
                            'recall': run_recall,
                            'config': config,
                            'metrics': metrics
                        })
                        print(f'Added run: {benchmark_dir} (recall: {run_recall:.4f})')
                    else:
                        print(f'No recall key found in {benchmark_dir}')
                else:
                    print(f'Algorithm mismatch: CSV={algorithm}, JSON={algo_to_run}')
            except Exception as e:
                print(f'Error processing {benchmark_dir}: {e}')
                continue
    
    print(f'Found {len(all_runs)} {algorithm} runs to match against {len(pareto_runs)} Pareto points')
    
    for pareto_run in pareto_runs:
        index_name = pareto_run['index_name']
        target_recall = float(pareto_run['recall'])
        
        best_match = None
        best_score = float('inf')
        
        for run in all_runs:
            score = float('inf')
            
            parts = index_name.split('-')
            parameter_match = False
            if len(parts) >= 3:
                if algorithm == 'CAGRA_HNSW':
                    ef_search = int(parts[0].replace('ef', ''))
                    graph_degree = int(parts[1].replace('deg', ''))
                    intermediate_degree = int(parts[2].replace('ideg', ''))
                    
                    if (run['config'].get('efSearch') == ef_search and
                        run['config'].get('cagraGraphDegree') == graph_degree and
                        run['config'].get('cagraIntermediateGraphDegree') == intermediate_degree):
                        parameter_match = True
                        score = 0
                elif algorithm == 'LUCENE_HNSW':
                    beam_width = int(parts[0].replace('beam', ''))
                    max_conn = int(parts[1].replace('conn', ''))
                    ef_search = int(parts[2].replace('ef', ''))
                    
                    if (run['config'].get('hnswBeamWidth') == beam_width and
                        run['config'].get('hnswMaxConn') == max_conn and
                        run['config'].get('efSearch') == ef_search):
                        parameter_match = True
                        score = 0
            
            if not parameter_match:
                recall_diff = abs(run['recall'] - target_recall)
                score = recall_diff
            
            if score < best_score:
                best_score = score
                best_match = run
        
        if best_match and best_score <= 0.05:
            is_pareto_file = os.path.join(best_match['dir'], 'is_pareto')
            with open(is_pareto_file, 'w') as f:
                f.write(f'Pareto optimal run\n')
                f.write(f'Algorithm: {algorithm}\n')
                f.write(f'Index: {index_name}\n')
                f.write(f'Target Recall: {target_recall:.4f}\n')
                f.write(f'Actual Recall: {best_match[\"recall\"]:.4f}\n')
                f.write(f'Match Score: {best_score:.4f}\n')
                f.write(f'Throughput: {pareto_run[\"throughput\"]}\n')
                f.write(f'Latency: {pareto_run[\"latency\"]}\n')
            print(f'Created is_pareto file: {is_pareto_file} (score: {best_score:.4f})')
        else:
            print(f'No good match found for {algorithm} {index_name} (best score: {best_score:.4f})')

print('is_pareto file generation complete!')
"

echo "Parameters: k=${K}, n_queries=${N_QUERIES}"

mkdir -p "${OUTPUT_DIR}/plots"

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