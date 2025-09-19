#!/usr/bin/env python3

import json
import csv
import os
import glob
from datetime import datetime
import hashlib

def consolidate_sweep_results(sweep_id):
    results_dir = f"results/raw/{sweep_id}"
    
    if not os.path.exists(results_dir):
        print(f"Sweep directory not found: {results_dir}")
        return
    
    run_dirs = []
    for run_dir in glob.glob(f"{results_dir}/*"):
        if os.path.isdir(run_dir):
            run_id = os.path.basename(run_dir)
            results_file = f"{run_dir}/results.json"
            if os.path.exists(results_file):
                run_dirs.append(run_dir)
    
    if not run_dirs:
        print(f"No runs found for sweep: {sweep_id}")
        return
    
    print(f"Found {len(run_dirs)} runs for sweep {sweep_id}")
    
    all_runs = []
    
    for run_dir in run_dirs:
        run_id = os.path.basename(run_dir)
        print(f"Processing run: {run_id}")
        
        results_file = f"{run_dir}/results.json"
        with open(results_file, 'r') as f:
            results = json.load(f)
        
        # Get config from results.json (has dataset_info)
        config = results.get('configuration', {})
        
        # Get metrics from detailed_results.json if available, otherwise from results.json
        detailed_results_file = f"{run_dir}/detailed_results.json"
        if os.path.exists(detailed_results_file):
            with open(detailed_results_file, 'r') as f:
                detailed_results = json.load(f)
                metrics = detailed_results.get('metrics', {})
        else:
            metrics = results.get('metrics', {})
        
        algorithm = config.get('algoToRun', config.get('algorithm', ''))
        if algorithm == 'CAGRA_HNSW':
            # Handle both old and new cuVS-Lucene versions
            indexing_time = metrics.get('cuvs-indexing-time', metrics.get('hnsw-indexing-time', 0)) / 1000.0
            query_time = metrics.get('hnsw-query-time', 0) / 1000.0
            recall = metrics.get('cuvs-recall-accuracy', 0)
            qps = metrics.get('hnsw-query-throughput', 0)
            mean_latency = metrics.get('hnsw-mean-latency', 0)
            index_size = metrics.get('cuvs-index-size', 0)
            segment_count = metrics.get('hnsw-segment-count', 0)
        else:
            indexing_time = metrics.get('hnsw-indexing-time', 0) / 1000.0
            query_time = metrics.get('hnsw-query-time', 0) / 1000.0
            recall = metrics.get('hnsw-recall-accuracy', 0)
            qps = metrics.get('hnsw-query-throughput', 0)
            mean_latency = metrics.get('hnsw-mean-latency', 0)
            index_size = metrics.get('hnsw-index-size', 0)
            segment_count = metrics.get('hnsw-segment-count', 0)
        
        # Determine dataset name from config
        dataset_name = 'unknown'
        if 'dataset_info' in config:
            dataset_info = config['dataset_info']
            if 'name' in dataset_info:
                # Convert dataset name to a normalized format
                dataset_name = dataset_info['name'].lower().replace(' ', '-').replace('_', '-')
            elif 'base_file' in dataset_info:
                # Fallback: extract dataset name from file path
                base_file = dataset_info['base_file']
                if 'sift' in base_file.lower():
                    if '1m' in base_file.lower() or '1M' in base_file:
                        dataset_name = 'sift-1m'
                    elif '10m' in base_file.lower() or '10M' in base_file:
                        dataset_name = 'sift-10m'
                    else:
                        dataset_name = 'sift'
                elif 'wiki' in base_file.lower():
                    if '1m' in base_file.lower() or '1M' in base_file:
                        dataset_name = 'wiki-1m'
                    elif '10m' in base_file.lower() or '10M' in base_file:
                        dataset_name = 'wiki-10m'
                    else:
                        dataset_name = 'wiki'
                else:
                    # Extract filename without extension as dataset name
                    filename = os.path.basename(base_file)
                    dataset_name = filename.split('.')[0].lower()
        
        # Convert NaN values to 0
        def safe_float(value):
            if value == 'NaN' or value is None:
                return 0.0
            try:
                return float(value)
            except (ValueError, TypeError):
                return 0.0
        
        run_data = {
            'run_id': run_id,
            'algorithm': algorithm,
            'dataset': dataset_name,
            'sweep_id': sweep_id,
            'sweep_name': config.get('sweep_name', sweep_id),
            'indexingTime': safe_float(indexing_time),
            'queryTime': safe_float(query_time),
            'recall': safe_float(recall),
            'qps': safe_float(qps),
            'meanLatency': safe_float(mean_latency),
            'indexSize': safe_float(index_size),
            'segmentCount': safe_float(segment_count),
            'createdAt': datetime.now().isoformat(),
            'resultsDirectory': f"results/raw/{sweep_id}/{run_id}",
        }
        
        # Get parameters from the nested parameters object
        params = config.get('parameters', {})
        
        if algorithm == 'CAGRA_HNSW':
            run_data.update({
                'cagraGraphDegree': params.get('cagraGraphDegree', 0),
                'cagraIntermediateGraphDegree': params.get('cagraIntermediateGraphDegree', 0),
                'cuvsWriterThreads': params.get('cuvsWriterThreads', 0),
                'hnswMaxConn': 0,
                'hnswBeamWidth': 0,
            })
        else:
            run_data.update({
                'cagraGraphDegree': 0,
                'cagraIntermediateGraphDegree': 0,
                'cuvsWriterThreads': 0,
                'hnswMaxConn': params.get('hnswMaxConn', 0),
                'hnswBeamWidth': params.get('hnswBeamWidth', 0),
            })
        
        # Get dataset info for numDocs
        dataset_info = config.get('dataset_info', {})
        
        run_data.update({
            'numIndexThreads': params.get('numIndexThreads', 0),
            'queryThreads': params.get('queryThreads', 0),
            'efSearch': params.get('efSearch', 0),
            'topK': params.get('topK', 0),
            'flushFreq': params.get('flushFreq', 0),
            'numDocs': dataset_info.get('num_docs', params.get('numDocs', 0)),
            'numQueriesToRun': params.get('numQueriesToRun', 0),
        })
        
        all_runs.append(run_data)
    
    # Write consolidated CSV
    csv_file = f"results/raw/{sweep_id}/{sweep_id}.csv"
    fieldnames = [
        'run_id', 'algorithm', 'dataset', 'sweep_id', 'sweep_name',
        'indexingTime', 'queryTime', 'recall', 'qps', 'meanLatency', 
        'indexSize', 'segmentCount', 'createdAt', 'resultsDirectory',
        'cagraGraphDegree', 'cagraIntermediateGraphDegree', 'cuvsWriterThreads',
        'hnswMaxConn', 'hnswBeamWidth', 'numIndexThreads', 'queryThreads',
        'efSearch', 'topK', 'flushFreq', 'numDocs', 'numQueriesToRun'
    ]
    
    with open(csv_file, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(all_runs)
    
    print(f"Generated consolidated CSV: {csv_file}")
    return csv_file

def consolidate_all_sweeps():
    """Consolidate all sweeps in results/raw"""
    
    results_dir = "results/raw"
    if not os.path.exists(results_dir):
        print("Results directory not found")
        return
    
    # Find all sweep directories
    sweep_dirs = []
    for item in os.listdir(results_dir):
        item_path = os.path.join(results_dir, item)
        if os.path.isdir(item_path) and item.count('-') >= 3:  # Format: DD-MM-YYYY-hash
            sweep_dirs.append(item)
    
    print(f"Found {len(sweep_dirs)} sweeps to consolidate")
    
    for sweep_id in sweep_dirs:
        print(f"\nConsolidating sweep: {sweep_id}")
        consolidate_sweep_results(sweep_id)

if __name__ == "__main__":
    consolidate_all_sweeps()