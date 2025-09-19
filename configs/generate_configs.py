#!/usr/bin/env python3

import yaml
import json
import hashlib
import itertools
import os
from datetime import datetime

def generate_run_id(config):
    config_str = json.dumps(config, sort_keys=True)
    return hashlib.md5(config_str.encode()).hexdigest()[:12]

def expand_parameter_combinations(params):
    keys = []
    values = []
    
    for key, value in params.items():
        keys.append(key)
        if isinstance(value, list):
            values.append(value)
        else:
            values.append([value])
    
    combinations = []
    for combo in itertools.product(*values):
        combinations.append(dict(zip(keys, combo)))
    
    return combinations

def generate_configs(sweep_file, datasets_file):
    with open(sweep_file, 'r') as f:
        sweep_data = yaml.safe_load(f)
    
    with open(datasets_file, 'r') as f:
        datasets_data = yaml.safe_load(f)
    
    configs_dir = "configs/generated"
    os.makedirs(configs_dir, exist_ok=True)
    
    date_prefix = datetime.now().strftime("%d-%m-%Y")
    time_suffix = datetime.now().strftime("%H%M%S")
    sweep_config_str = json.dumps(sweep_data, sort_keys=True)
    config_hash = hashlib.md5(sweep_config_str.encode()).hexdigest()[:8]
    sweep_id = f"{date_prefix}-{config_hash}-{time_suffix}"
    generated_configs = []
    
    # Handle new multi-dataset format
    if 'datasets' in sweep_data and 'algorithms' in sweep_data:
        # New format: datasets + algorithms + common_params
        datasets = sweep_data['datasets']
        algorithms = sweep_data['algorithms']
        common_params = sweep_data.get('common_params', {})
        
        # Handle both list format (dataset names) and dict format (dataset details)
        if isinstance(datasets, list):
            # New format: list of dataset names
            dataset_names = datasets
        else:
            # Legacy format: dict with dataset details
            dataset_names = datasets.keys()
        
        for dataset_name in dataset_names:
            dataset_data = datasets_data['datasets'][dataset_name]
            
            for algo_name, algo_params in algorithms.items():
                # Combine common params with algorithm-specific params
                all_params = {**common_params, **algo_params}
                
                # Generate parameter combinations
                param_combinations = expand_parameter_combinations(all_params)
                
                for params in param_combinations:
                    config = {
                        'sweep_id': sweep_id,
                        'algorithm': algo_name,
                        'dataset_info': dataset_data,
                        'parameters': params
                    }
                    
                    run_id = generate_run_id(config)
                    config['run_id'] = run_id
                    generated_configs.append(config)
                    
                    config_file = f"{configs_dir}/{run_id}.json"
                    with open(config_file, 'w') as f:
                        json.dump(config, f, indent=2)
    else:
        # Legacy format: single dataset per sweep
        for sweep_name, sweep_config in sweep_data.items():
            dataset_name = sweep_config['dataset']
            dataset_info = datasets_data['datasets'][dataset_name]
            
            common_params = sweep_config.get('common-params', {})
            algorithms = sweep_config.get('algorithms', {})
            
            for algo_name, algo_params in algorithms.items():
                all_params = {**common_params, **algo_params}
                param_combinations = expand_parameter_combinations(all_params)
                
                for param_combo in param_combinations:
                    config = {
                        'sweep_id': sweep_id,
                        'sweep_name': sweep_name,
                        'run_id': generate_run_id(param_combo),
                        'dataset': dataset_name,
                        'algorithm': algo_name,
                        'dataset_info': dataset_info,
                        'parameters': param_combo
                    }
                    
                    config_file = f"{configs_dir}/{config['run_id']}.json"
                    with open(config_file, 'w') as f:
                        json.dump(config, f, indent=2)
                    
                    generated_configs.append(config)
    
    # Save summary of all generated configs
    summary_file = f"{configs_dir}/sweep_{sweep_id}_summary.json"
    with open(summary_file, 'w') as f:
        json.dump({
            'sweep_id': sweep_id,
            'total_configs': len(generated_configs),
            'configs': generated_configs
        }, f, indent=2)
    
    print(f"Generated {len(generated_configs)} configurations")
    print(f"Summary saved to: {summary_file}")
    
    return generated_configs

if __name__ == "__main__":
    import sys
    
    if len(sys.argv) != 3:
        print("Usage: python3 generate_configs.py <sweep_file> <datasets_file>")
        sys.exit(1)
    
    sweep_file = sys.argv[1]
    datasets_file = sys.argv[2]
    
    if not os.path.exists(sweep_file):
        print(f"Error: Sweep file not found: {sweep_file}")
        sys.exit(1)
    
    if not os.path.exists(datasets_file):
        print(f"Error: Datasets file not found: {datasets_file}")
        sys.exit(1)
    
    generate_configs(sweep_file, datasets_file)

