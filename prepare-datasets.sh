#!/bin/bash
while getopts ":-:" opt; do
    case $OPTARG in
        data-dir) DATA_DIR="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
        datasets) DATASETS_FILE="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
    esac
done
DATA_DIR=${DATA_DIR:-datasets}
DATASETS_FILE=${DATASETS_FILE:-datasets.json}
DATASETS_FILE=$(realpath "$DATASETS_FILE")
mkdir -p "$DATA_DIR"
cd $DATA_DIR || exit 1

echo "Datadir is: $DATA_DIR"

for dataset in $(jq -r '.datasets | keys[]' "$DATASETS_FILE"); do
    echo "$dataset"

    # Check if all files exist and checksums match
    valid=true
    for file_type in base query ground_truth; do
        file=$(jq -r --arg dataset "$dataset" --arg type "$file_type" '.datasets[$dataset][$type + "_file"]' "$DATASETS_FILE")
        checksum=$(jq -r --arg dataset "$dataset" --arg type "$file_type" '.datasets[$dataset][$type + "_checksum"]' "$DATASETS_FILE")
        if [ ! -f "$dataset/$file" ] || [ "$(sha256sum "$dataset/$file" | cut -d' ' -f1)" != "$checksum" ]; then
            valid=false
            break
        fi
    done

    if [ "$valid" = true ]; then
        echo "Dataset $dataset already exists with correct checksums. Skipping."
        continue
    fi

    url=$(jq -r --arg dataset "$dataset" '.datasets[$dataset]["download-url"]' "$DATASETS_FILE")
    prep_cmd=$(jq -r --arg dataset "$dataset" '.datasets[$dataset]["preparation-commands"]' "$DATASETS_FILE")

    mkdir -p $dataset; cd $dataset
    curl -C - -O "$url"
    eval "$prep_cmd"
    cd ..
done