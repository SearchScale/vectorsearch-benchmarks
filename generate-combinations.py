
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
    invariants["datasetFile"] = f"{args.data_dir}/{dataset_info['base_file']}"
    invariants["queryFile"] = f"{args.data_dir}/{dataset_info['query_file']}"
    invariants["groundTruthFile"] = f"{args.data_dir}/{dataset_info['ground_truth_file']}"
    invariants["vectorDimension"] = dataset_info["vector_dimension"]
    invariants["indexOfVector"] = 3
    invariants["vectorColName"] = "article_vector"
    invariants["hasColNames"] = True
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

        for param, value in algorithms[algo].items():
            if param not in ["params"]:
                if not isinstance(value, list):
                    algo_invariants[param] = value
                else:
                    algo_variants[param] = value

        # Generate all combination of variants. For each combination, generate a hashed ID, and a file with the
        # name pattern as <sweep>-<algo>-<hash>.json. The file should contain the invariants as is, and the variants as the current combination.
        if algo_variants:
            variant_keys = list(algo_variants.keys())
            variant_values = list(algo_variants.values())
            for combination in itertools.product(*variant_values):
                current_variants = dict(zip(variant_keys, combination))
                # Generate hash for this combination
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