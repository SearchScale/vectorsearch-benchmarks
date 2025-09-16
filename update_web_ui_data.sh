#!/bin/bash

# Script to update web UI data with latest benchmark results
# This should be run after each benchmark sweep

echo "Updating web UI data..."

# Run the Python script to generate sweep CSV files
python3 generate_sweep_csvs.py

# Check if the script succeeded
if [ $? -eq 0 ]; then
    echo "✅ Web UI data updated successfully!"
    echo "You can now refresh the web UI to see the latest data."
else
    echo "❌ Failed to update web UI data"
    exit 1
fi




