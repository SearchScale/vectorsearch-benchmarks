#!/bin/bash

while getopts ":-:" opt; do
    case $OPTARG in
        data-dir) DATA_DIR="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
        datasets) DATASETS_FILE="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
        sweeps) SWEEPS_FILE="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
        configs-dir) CONFIGS_DIR="${!OPTIND}"; OPTIND=$((OPTIND+1)) ;;
    esac
done

DATA_DIR=${DATA_DIR:-datasets}
DATASETS_FILE=${DATASETS_FILE:-datasets.json}
SWEEPS_FILE=${SWEEPS_FILE:-sweeps.json}
CONFIGS_DIR=${CONFIGS_DIR:-configs}

./prepare-datasets.sh --data-dir "$DATA_DIR" --datasets "$DATASETS_FILE" || exit 1
python generate-combinations.py --data-dir "$DATA_DIR" --datasets "$DATASETS_FILE" --sweeps "$SWEEPS_FILE" --configs-dir "$CONFIGS_DIR" || exit 1

