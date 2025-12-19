
import itertools
import argparse
import sys
import json
import hashlib
import os
import shutil
parser = argparse.ArgumentParser(description='Generate combinations script')
parser.add_argument('--data-dir', required=True, help='Data directory path')
parser.add_argument('--datasets', required=True, help='Datasets JSON file path')
parser.add_argument('--sweeps', required=True, help='Sweeps JSON file path')
parser.add_argument('--configs-dir', required=True, help='Configs output directory path')

args = parser.parse_args()

print("Arguments captured:")
print(f"data-dir: {args.data_dir}")
print(f"datasets: {args.datasets}")
print(f"sweeps: {args.sweeps}")
print(f"configs-dir: {args.configs_dir}")
print("----------------------")

sweeps = json.load(open(args.sweeps))
datasets = json.load(open(args.datasets))

# Clean configs directory
if os.path.exists(args.configs_dir):
    shutil.rmtree(args.configs_dir)

for sweep in sweeps:
    # Get dataset information for this sweep
    dataset_name = sweeps[sweep]["dataset"]
    dataset_info = datasets["datasets"][dataset_name]
    variants={}
    invariants={}

    # Add dataset-specific parameters to invariants
    invariants["datasetFile"] = f"{args.data_dir}/{dataset_name}/{dataset_info['base_file']}"
    invariants["queryFile"] = f"{args.data_dir}/{dataset_name}/{dataset_info['query_file']}"
    invariants["groundTruthFile"] = f"{args.data_dir}/{dataset_name}/{dataset_info['ground_truth_file']}"
    invariants["vectorDimension"] = dataset_info["vector_dimension"]
    print("sweep: " + sweep)
    for param, value in sweeps[sweep].get("common-params", {}).items():
        if not isinstance(value, list):
            invariants[param] = value
        else:
            variants[param] = value
    for algo in sweeps[sweep].get("algorithms", []):
        algorithms = sweeps[sweep].get("algorithms", [])
        algo_variants = variants.copy()
        algo_invariants = invariants.copy()
        algo_invariants["algoToRun"] = algo

        for param, value in algorithms[algo].items():
            if param not in ["params"]:
                if not isinstance(value, list):
                    algo_invariants[param] = value
                else:
                    algo_variants[param] = value

        # Generate all combination of variants. For each combination, generate a hashed ID, and a file with the
        # name pattern as <sweep>-<algo>-<hash>.json. The file should contain the invariants as is, and the variants as the current combination.
        if algo_variants:
            # Separate efSearch or efSearchScaleFactor from other variants if it exists
            efSearch_values = None
            efSearchScaleFactor_values = None
            other_variant_keys = []
            other_variant_values = []
            
            for key, value in algo_variants.items():
                if key == 'efSearch':
                    efSearch_values = value
                elif key == 'efSearchScaleFactor':
                    efSearchScaleFactor_values = value
                else:
                    other_variant_keys.append(key)
                    other_variant_values.append(value)
            
            # Generate combinations with efSearch or efSearchScaleFactor at the beginning (innermost loop)
            if (efSearch_values or efSearchScaleFactor_values) and other_variant_keys:
                # Generate combinations of other parameters first
                for other_combination in itertools.product(*other_variant_values):
                    other_variants = dict(zip(other_variant_keys, other_combination))
                    # Then iterate through efSearch or efSearchScaleFactor values
                    search_values = efSearch_values if efSearch_values else efSearchScaleFactor_values
                    search_key = 'efSearch' if efSearch_values else 'efSearchScaleFactor'
                    for ef_index, ef_value in enumerate(search_values):
                        current_variants = other_variants.copy()
                        current_variants[search_key] = ef_value
                        
                        # Skip if cagraIntermediateDegree < cagraGraphDegree
                        if 'cagraIntermediateDegree' in current_variants and 'cagraGraphDegree' in current_variants:
                            if current_variants['cagraIntermediateDegree'] < current_variants['cagraGraphDegree']:
                                print(f"\t\tSkipping combination: cagraIntermediateDegree ({current_variants['cagraIntermediateDegree']}) < cagraGraphDegree ({current_variants['cagraGraphDegree']})")
                                continue
                        
                        # Skip if hnswMaxConn > hnswBeamWidth
                        if 'hnswMaxConn' in current_variants and 'hnswBeamWidth' in current_variants:
                            if current_variants['hnswMaxConn'] > current_variants['hnswBeamWidth']:
                                print(f"\t\tSkipping combination: hnswMaxConn ({current_variants['hnswMaxConn']}) > hnswBeamWidth ({current_variants['hnswBeamWidth']})")
                                continue
                        
                        # Generate hash only from other_variants (excluding efSearch/efSearchScaleFactor)
                        base_hash = hashlib.md5(json.dumps(other_variants, sort_keys=True).encode()).hexdigest()[:8]
                        if search_key == 'efSearch':
                            hash_id = f"{base_hash}-ef{ef_value}"
                        else:
                            hash_id = f"{base_hash}-efs{ef_value}"
                        
                        config = algo_invariants.copy()
                        config.update(current_variants)
                        
                        # For multiple search value combinations: subsequent ones skip indexing
                        if len(search_values) > 1 and ef_index > 0:
                            config['skipIndexing'] = True
                        
                        # Set cleanIndexDirectory based on position
                        if ef_index == 0:
                            config['cleanIndexDirectory'] = False
                        elif ef_index == len(search_values) - 1:
                            config['cleanIndexDirectory'] = True
                        else:
                            config['cleanIndexDirectory'] = False
                        
                        # Use base_hash for index directory paths
                        if 'hnswIndexDirPath' in config:
                            config['hnswIndexDirPath'] = f"hnswIndex-{base_hash}"
                        if 'cuvsIndexDirPath' in config:
                            config['cuvsIndexDirPath'] = f"cuvsIndex-{base_hash}"
                        
                        filename = f"{algo}-{hash_id}.json"
                        sweep_dir = f"{args.configs_dir}/{sweep}"
                        filepath = f"{sweep_dir}/{filename}"
                        os.makedirs(sweep_dir, exist_ok=True)
                        with open(filepath, 'w') as f:
                            json.dump(config, f, indent=2)
                        print(f"\tGenerated config file: {filepath}")
            elif efSearch_values or efSearchScaleFactor_values:
                # Only efSearch or efSearchScaleFactor values, no other variants
                search_values = efSearch_values if efSearch_values else efSearchScaleFactor_values
                search_key = 'efSearch' if efSearch_values else 'efSearchScaleFactor'
                for ef_index, ef_value in enumerate(search_values):
                    current_variants = {search_key: ef_value}
                    # Generate hash from empty dict since no other variants exist
                    base_hash = hashlib.md5(json.dumps({}, sort_keys=True).encode()).hexdigest()[:8]
                    if search_key == 'efSearch':
                        hash_id = f"{base_hash}-ef{ef_value}"
                    else:
                        hash_id = f"{base_hash}-efs{ef_value}"
                    
                    config = algo_invariants.copy()
                    config.update(current_variants)
                    
                    # For multiple search value combinations: subsequent ones skip indexing
                    if len(search_values) > 1 and ef_index > 0:
                        config['skipIndexing'] = True
                    
                    # Set cleanIndexDirectory based on position
                    if ef_index == 0:
                        config['cleanIndexDirectory'] = False
                    elif ef_index == len(search_values) - 1:
                        config['cleanIndexDirectory'] = True
                    else:
                        config['cleanIndexDirectory'] = False
                    
                    # Use base_hash for index directory paths
                    if 'hnswIndexDirPath' in config:
                        config['hnswIndexDirPath'] = f"hnswIndex-{base_hash}"
                    if 'cuvsIndexDirPath' in config:
                        config['cuvsIndexDirPath'] = f"cuvsIndex-{base_hash}"
                    
                    filename = f"{algo}-{hash_id}.json"
                    sweep_dir = f"{args.configs_dir}/{sweep}"
                    filepath = f"{sweep_dir}/{filename}"
                    os.makedirs(sweep_dir, exist_ok=True)
                    with open(filepath, 'w') as f:
                        json.dump(config, f, indent=2)
                    print(f"\tGenerated config file: {filepath}")
            else:
                # No efSearch, use original logic
                variant_keys = list(algo_variants.keys())
                variant_values = list(algo_variants.values())
                for combination in itertools.product(*variant_values):
                    current_variants = dict(zip(variant_keys, combination))
                    
                    # Skip if cagraIntermediateDegree < cagraGraphDegree
                    if 'cagraIntermediateDegree' in current_variants and 'cagraGraphDegree' in current_variants:
                        if current_variants['cagraIntermediateDegree'] < current_variants['cagraGraphDegree']:
                            print(f"\t\tSkipping combination: cagraIntermediateDegree ({current_variants['cagraIntermediateDegree']}) < cagraGraphDegree ({current_variants['cagraGraphDegree']})")
                            continue
                    
                    # Skip if hnswMaxConn > hnswBeamWidth
                    if 'hnswMaxConn' in current_variants and 'hnswBeamWidth' in current_variants:
                        if current_variants['hnswMaxConn'] > current_variants['hnswBeamWidth']:
                            print(f"\t\tSkipping combination: hnswMaxConn ({current_variants['hnswMaxConn']}) > hnswBeamWidth ({current_variants['hnswBeamWidth']})")
                            continue
                    
                    hash_id = hashlib.md5(json.dumps(current_variants, sort_keys=True).encode()).hexdigest()[:8]
                    
                    config = algo_invariants.copy()
                    config.update(current_variants)
                    filename = f"{algo}-{hash_id}.json"
                    sweep_dir = f"{args.configs_dir}/{sweep}"
                    filepath = f"{sweep_dir}/{filename}"
                    os.makedirs(sweep_dir, exist_ok=True)
                    with open(filepath, 'w') as f:
                        json.dump(config, f, indent=2)
                    print(f"\tGenerated config file: {filepath}")
        
        
    print("----------------------")
