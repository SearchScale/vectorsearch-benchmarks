#!/usr/bin/env python3

import os
import json
import csv
import argparse
import pandas as pd
from typing import List, Dict, Optional, Tuple

metrics = {
    "k-nn": {"worst": float("-inf")},
    "throughput": {"worst": float("-inf")},
    "latency": {"worst": float("inf")},
}


def create_pointset(data, xn, yn):
    xm, ym = metrics[xn], metrics[yn]
    y_col = 4 if yn == "latency" else 3

    rev_x = -1 if xm["worst"] < 0 else 1
    rev_y = -1 if ym["worst"] < 0 else 1

    data.sort(key=lambda t: (rev_y * t[y_col], rev_x * t[2]))
    lines = []
    last_x = xm["worst"]
    comparator = (lambda xv, lx: xv > lx) if last_x < 0 else (lambda xv, lx: xv < lx)

    for d in data:
        if comparator(d[2], last_x):
            last_x = d[2]
            lines.append(d)
    return lines


def get_frontier(df, metric):
    lines = create_pointset(df.values.tolist(), "k-nn", metric)
    return pd.DataFrame(lines, columns=df.columns)


def find_json_files(input_dir: str, json_filename: str = "detailed_results.json") -> List[str]:
    json_files = []
    for root, dirs, files in os.walk(input_dir):
        if json_filename in files:
            json_files.append(os.path.join(root, json_filename))
    return json_files


def detect_dataset_name(dataset_file: str, datasets_config: Dict) -> str:
    """Detect dataset name from dataset file path using datasets.json configuration"""
    dataset_file_lower = dataset_file.lower()
    
    for dataset_name, dataset_info in datasets_config.items():
        base_file = dataset_info.get('base_file', '').lower()
        if base_file and base_file in dataset_file_lower:
            return dataset_name
        
        # Also check for partial matches in the dataset name
        if dataset_name.lower().replace('-', '') in dataset_file_lower.replace('-', '').replace('_', ''):
            return dataset_name
    
    return 'unknown'


def extract_metrics_from_json(json_file: str, datasets_config: Dict = None) -> Optional[Dict]:
    try:
        with open(json_file, 'r') as f:
            data = json.load(f)
        
        if 'metrics' not in data:
            return None
            
        metrics = data['metrics']
        config = data.get('configuration', {})
        
        algorithm = config.get('algoToRun', 'UNKNOWN')
        
        dataset_file = config.get('datasetFile', '')
        dataset_name = detect_dataset_name(dataset_file, datasets_config or {})
        
        recall = None
        latency = None
        build_time = None
        
        for key, value in metrics.items():
            if 'recall' in key.lower():
                recall = float(value) / 100.0
            elif 'mean-latency' in key.lower():
                latency = float(value) / 1000.0
            elif 'indexing-time' in key.lower():
                build_time = float(value) / 1000.0
        
        # Calculate throughput as 1/latency (queries per second)
        throughput = 1.0 / latency if latency and latency > 0 else None
        
        return {
            'algorithm': algorithm,
            'dataset': dataset_name,
            'recall': recall,
            'throughput': throughput,
            'latency': latency,
            'build_time': build_time,
            'config': config
        }
        
    except Exception as e:
        print(f"Error processing {json_file}: {e}")
        return None


def create_index_name(config: Dict) -> str:
    algorithm = config.get('algoToRun', 'UNKNOWN')
    
    if algorithm == 'LUCENE_HNSW':
        ef_construction = config.get('efConstruction', 150)
        beam_width = config.get('hnswBeamWidth', 32)
        return f"ef{ef_construction}-beam{beam_width}"
    elif algorithm == 'CAGRA_HNSW':
        ef_construction = config.get('efConstruction', 150)
        graph_degree = config.get('cagraGraphDegree', 32)
        intermediate_degree = config.get('cagraIntermediateGraphDegree', 32)
        return f"ef{ef_construction}-deg{graph_degree}-ideg{intermediate_degree}"
    else:
        ef_construction = config.get('efConstruction', 150)
        m = config.get('m', 32)
        max_candidates = config.get('maxCandidates', 128)
        return f"ef{ef_construction}-deg{m}-ideg{max_candidates}"


def convert_results(input_dir: str, output_dir: str, dataset_name: str, 
                   json_filename: str = "detailed_results.json", 
                   k: int = None, n_queries: int = None,
                   datasets_file: str = "datasets.json") -> Tuple[List[str], List[str]]:
    
    # Load datasets configuration
    datasets_config = {}
    if os.path.exists(datasets_file):
        try:
            with open(datasets_file, 'r') as f:
                datasets_data = json.load(f)
                datasets_config = datasets_data.get('datasets', {})
        except Exception as e:
            print(f"Warning: Could not load {datasets_file}: {e}")
    
    json_files = find_json_files(input_dir, json_filename)
    if not json_files:
        print(f"No {json_filename} files found in {input_dir}")
        return [], []
    
    print(f"Found {len(json_files)} JSON files to process")
    
    # Show which algorithms/configurations we have
    algorithms_found = set()
    for json_file in json_files:
        try:
            with open(json_file, 'r') as f:
                data = json.load(f)
                if 'configuration' in data:
                    algo = data['configuration'].get('algoToRun', 'UNKNOWN')
                    algorithms_found.add(algo)
        except:
            pass
    
    if algorithms_found:
        print(f"Algorithms found: {', '.join(sorted(algorithms_found))}")
    
    # Extract k and n_queries from the first file if not provided
    if k is None or n_queries is None:
        first_file = json_files[0]
        sample_metrics = extract_metrics_from_json(first_file, datasets_config)
        if sample_metrics and 'config' in sample_metrics:
            config = sample_metrics['config']
            if k is None:
                k = config.get('topK', 10)
            if n_queries is None:
                n_queries = config.get('numQueriesToRun', 10000)

        print(f"Using k={k}, n_queries={n_queries} from data")
        
        # Validate that all files have the same parameters
        for json_file in json_files[1:]:
            metrics = extract_metrics_from_json(json_file, datasets_config)
            if metrics and 'config' in metrics:
                config = metrics['config']
                file_k = config.get('topK', 10)
                file_n_queries = config.get('numQueriesToRun', 10000)
                
                if file_k != k or file_n_queries != n_queries:
                    print(f"Warning: Parameter mismatch in {json_file}")
                    print(f"  Expected: k={k}, n_queries={n_queries}")
                    print(f"  Found: k={file_k}, n_queries={file_n_queries}")
                    print("  Using the first file's parameters")
    
    # Create output directories
    search_dir = os.path.join(output_dir, "result", "search")
    build_dir = os.path.join(output_dir, "result", "build")
    
    os.makedirs(search_dir, exist_ok=True)
    os.makedirs(build_dir, exist_ok=True)
    
    search_data = []
    build_data = []
    
    for json_file in json_files:
        print(f"Processing: {json_file}")
        
        metrics = extract_metrics_from_json(json_file, datasets_config)
        if not metrics:
            continue
        
        index_name = create_index_name(metrics['config'])
        
        # Add to search data if we have the required metrics
        if metrics['recall'] is not None and metrics['throughput'] is not None and metrics['latency'] is not None:
            search_data.append({
                'algorithm': metrics['algorithm'],
                'index_name': index_name,
                'recall': metrics['recall'],
                'throughput': metrics['throughput'],
                'latency': metrics['latency']
            })
        
        # Add to build data if we have build time
        if metrics['build_time'] is not None:
            existing_build = next((item for item in build_data if 
                                 item['algorithm'] == metrics['algorithm'] and 
                                 item['index_name'] == index_name), None)
            
            if not existing_build:
                build_data.append({
                    'algorithm': metrics['algorithm'],
                    'index_name': index_name,
                    'build_time': metrics['build_time']
                })
    
    # Write search CSV files with frontier filtering
    search_files = []
    if search_data:
        algorithms = list(set(item['algorithm'] for item in search_data))
        
        for algorithm in algorithms:
            algorithm_data = [item for item in search_data if item['algorithm'] == algorithm]
            
            # Convert to DataFrame for frontier calculation
            df_data = []
            for item in algorithm_data:
                df_data.append([item['algorithm'], item['index_name'], 
                               item['recall'], item['throughput'], item['latency']])
            
            df = pd.DataFrame(df_data, columns=['algorithm', 'index_name', 'recall', 'throughput', 'latency'])
            
            # Generate frontier for throughput
            throughput_frontier = get_frontier(df, 'throughput')
            filename = f"{algorithm},base,k{k},bs{n_queries},throughput.csv"
            filepath = os.path.join(search_dir, filename)
            throughput_frontier.to_csv(filepath, index=False)
            search_files.append(filename)
            print(f"Created throughput frontier: {filepath}")
            
            # Generate frontier for latency
            latency_frontier = get_frontier(df, 'latency')
            latency_filename = f"{algorithm},base,k{k},bs{n_queries},latency.csv"
            latency_filepath = os.path.join(search_dir, latency_filename)
            latency_frontier.to_csv(latency_filepath, index=False)
            search_files.append(latency_filename)
            print(f"Created latency frontier: {latency_filepath}")
    
    # Write build CSV files
    build_files = []
    if build_data:
        algorithms = list(set(item['algorithm'] for item in build_data))
        
        for algorithm in algorithms:
            algorithm_data = [item for item in build_data if item['algorithm'] == algorithm]
            
            filename = f"{algorithm},base.csv"
            filepath = os.path.join(build_dir, filename)
            
            with open(filepath, 'w', newline='') as f:
                writer = csv.writer(f)
                writer.writerow(['algorithm', 'index_name', 'build_time'])
                for item in algorithm_data:
                    writer.writerow([item['algorithm'], item['index_name'], f"{item['build_time']:.6f}"])
            
            build_files.append(filename)
            print(f"Created build file: {filepath}")
    
    print(f"\nConversion complete!")
    print(f"Created {len(search_files)} search CSV files")
    print(f"Created {len(build_files)} build CSV files")
    
    return search_files, build_files


def main():
    parser = argparse.ArgumentParser(description='Convert benchmark results to CSV format')
    parser.add_argument('--input-dir', required=True, help='Input directory containing JSON files')
    parser.add_argument('--output-dir', required=True, help='Output directory for CSV files')
    parser.add_argument('--dataset-name', required=True, help='Dataset name')
    parser.add_argument('--json-filename', default='results.json', help='JSON filename to look for')
    parser.add_argument('--k', type=int, help='Top-K value (auto-detected if not provided)')
    parser.add_argument('--n-queries', type=int, help='Number of queries (auto-detected if not provided)')
    parser.add_argument('--datasets-file', default='datasets.json', help='Path to datasets.json file')
    
    args = parser.parse_args()
    
    print(f"Converting results from {args.input_dir} to {args.output_dir}")
    print(f"Dataset name: {args.dataset_name}")
    print(f"Looking for JSON files named: {args.json_filename}")
    
    search_files, build_files = convert_results(
        args.input_dir, 
        args.output_dir, 
        args.dataset_name, 
        args.json_filename,
        args.k,
        args.n_queries,
        args.datasets_file
    )
    
    if search_files or build_files:
        print(f"\nYou can now run the plotting script with:")
        print(f"python plot.py --dataset {args.dataset_name} --dataset-path {args.output_dir}")


if __name__ == "__main__":
    main()
