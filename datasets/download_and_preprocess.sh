#!/bin/bash

# Dataset management script

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DATASETS_FILE="$SCRIPT_DIR/datasets.yaml"

if [ ! -f "$DATASETS_FILE" ]; then
    echo "Error: datasets.yaml not found at $DATASETS_FILE"
    exit 1
fi

check_file() {
    local file_path="$1"
    if [ -f "$file_path" ] && [ -s "$file_path" ]; then
        return 0
    else
        return 1
    fi
}

download_and_extract_dataset() {
    local dataset_name="$1"
    local download_url="$2"
    local extract_commands="$3"
    local target_dir="$4"
    
    echo "Downloading $dataset_name from $download_url..."
    mkdir -p "$target_dir"
    cd "$target_dir"
    
    # Download the file
    if ! wget -O dataset.tar.gz "$download_url"; then
        echo "Failed to download $dataset_name"
        return 1
    fi
    
    # Extract if needed
    if [ -n "$extract_commands" ]; then
        echo "Extracting dataset..."
        if ! eval "$extract_commands"; then
            echo "Failed to extract $dataset_name"
            return 1
        fi
    fi
    
    cd "$PROJECT_ROOT"
    echo "Successfully downloaded and extracted $dataset_name"
    return 0
}

# Function to detect actual file names after extraction
detect_extracted_files() {
    local target_dir="$1"
    local dataset_name="$2"
    
    # Common file patterns for different datasets
    case "$dataset_name" in
        "wiki-all-1m"|"wiki-all-10m")
            # Look for wiki dataset files
            base_file=$(find "$target_dir" -name "*.fbin" -o -name "base*" | head -1)
            query_file=$(find "$target_dir" -name "*query*" -o -name "*queries*" | head -1)
            ground_truth_file=$(find "$target_dir" -name "*groundtruth*" -o -name "*neighbors*" | head -1)
            ;;
        "sift-1m"|"sift-10m")
            # Look for SIFT dataset files
            base_file=$(find "$target_dir" -name "*base*.fvecs" | head -1)
            query_file=$(find "$target_dir" -name "*query*.fvecs" | head -1)
            ground_truth_file=$(find "$target_dir" -name "*.ivecs" | head -1)
            ;;
        *)
            # Generic detection
            base_file=$(find "$target_dir" -name "*base*" | head -1)
            query_file=$(find "$target_dir" -name "*query*" | head -1)
            ground_truth_file=$(find "$target_dir" -name "*groundtruth*" -o -name "*gt*" | head -1)
            ;;
    esac
    
    echo "$base_file|$query_file|$ground_truth_file"
}

update_dataset_paths() {
    local dataset_name="$1"
    local base_file="$2"
    local query_file="$3"
    local ground_truth_file="$4"
    
    echo "Updating dataset paths in datasets.yaml..."
    python3 -c "
import yaml
import os

with open('datasets/datasets.yaml', 'r') as f:
    data = yaml.safe_load(f)
if 'datasets' in data and '$dataset_name' in data['datasets']:
    dataset_config = data['datasets']['$dataset_name']
    dataset_config['base_file'] = '$base_file'
    dataset_config['query_file'] = '$query_file'
    dataset_config['ground_truth_file'] = '$ground_truth_file'
    dataset_config['available'] = True
    with open('datasets/datasets.yaml', 'w') as f:
        yaml.dump(data, f, default_flow_style=False, sort_keys=False)
    
    print(f'Updated paths for $dataset_name in datasets.yaml')
    print(f'   Base: $base_file')
    print(f'   Query: $query_file')
    print(f'   Ground Truth: $ground_truth_file')
else:
    print(f'Dataset $dataset_name not found in datasets.yaml')
"
}

echo "Checking dataset availability..."
cd "$PROJECT_ROOT"

python3 -c "
import yaml
import os
import sys
import subprocess

with open('datasets/datasets.yaml', 'r') as f:
    data = yaml.safe_load(f)

datasets = data.get('datasets', {})
missing_datasets = []
downloadable_datasets = []
ready_datasets = []

for name, config in datasets.items():
    if not config.get('available', False):
        continue
        
    base_file = config.get('base_file', '')
    query_file = config.get('query_file', '')
    ground_truth_file = config.get('ground_truth_file', '')
    
    missing_files = []
    if not os.path.exists(base_file) or os.path.getsize(base_file) == 0:
        missing_files.append('base_file')
    if not os.path.exists(query_file) or os.path.getsize(query_file) == 0:
        missing_files.append('query_file')
    if not os.path.exists(ground_truth_file) or os.path.getsize(ground_truth_file) == 0:
        missing_files.append('ground_truth_file')
    
    if missing_files:
        if 'download_url' in config and 'extract_commands' in config and 'target_directory' in config:
            downloadable_datasets.append({
                'name': name,
                'config': config,
                'missing_files': missing_files
            })
        else:
            missing_datasets.append({
                'name': name,
                'config': config,
                'missing_files': missing_files
            })
    else:
        ready_datasets.append(name)

# Report status
if ready_datasets:
    print('Ready datasets:')
    for dataset in ready_datasets:
        print(f'   {dataset}')

if downloadable_datasets:
    print('\\nDatasets that can be downloaded:')
    for dataset in downloadable_datasets:
        print(f'   {dataset[\"name\"]}: missing {dataset[\"missing_files\"]}')
    
    print('\\nStarting download process...')
    
    # Download each dataset
    for dataset in downloadable_datasets:
        name = dataset['name']
        config = dataset['config']
        download_url = config['download_url']
        extract_commands = config['extract_commands']
        target_directory = config['target_directory']
        
        print(f'\\nProcessing {name}...')
        
        # Download and extract using shell functions
        try:
            # Create target directory
            os.makedirs(target_directory, exist_ok=True)
            
            # Download
            print(f'Downloading {name} from {download_url}...')
            subprocess.run(['wget', '-O', f'{target_directory}/dataset.tar.gz', download_url], check=True)
            
            # Extract
            if extract_commands:
                print('Extracting dataset...')
                subprocess.run(['bash', '-c', f'cd {target_directory} && {extract_commands}'], check=True)
            
            expected_base_file = config.get('expected_base_file', f'{target_directory}/base.fvecs')
            expected_query_file = config.get('expected_query_file', f'{target_directory}/query.fvecs')
            expected_ground_truth_file = config.get('expected_ground_truth_file', f'{target_directory}/groundtruth.ivecs')
            dataset_config = config
            dataset_config['base_file'] = expected_base_file
            dataset_config['query_file'] = expected_query_file
            dataset_config['ground_truth_file'] = expected_ground_truth_file
            dataset_config['available'] = True
            with open('datasets/datasets.yaml', 'w') as f:
                yaml.dump(data, f, default_flow_style=False, sort_keys=False)
            
            print(f'{name} is now ready!')
            print(f'   Base: {expected_base_file}')
            print(f'   Query: {expected_query_file}')
            print(f'   Ground Truth: {expected_ground_truth_file}')
                
        except subprocess.CalledProcessError as e:
            print(f'Failed to download {name}: {e}')
        except Exception as e:
            print(f'Error processing {name}: {e}')
    
    print('\\nDownload process completed!')
    sys.exit(0)
elif missing_datasets:
    print('\\nMissing datasets (no download URL available):')
    for dataset in missing_datasets:
        print(f'   {dataset[\"name\"]}: missing {dataset[\"missing_files\"]}')
    print('\\nTo fix this:')
    print('   1. Update datasets.yaml with correct file paths, or')
    print('   2. Add download_url, extract_commands, and target_directory to the dataset config')
    sys.exit(1)
else:
    print('\\nAll datasets are ready for benchmarking!')
    sys.exit(0)
"

if [ $? -ne 0 ]; then
    echo "Dataset check failed. Please review the output above."
    exit 1
fi

echo "Dataset management completed successfully!"