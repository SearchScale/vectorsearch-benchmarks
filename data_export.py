#!/usr/bin/env python3

import json
import os
import pandas as pd
import traceback


def read_json_files(dataset, dataset_path, method):
    """
    Read JSON files from the dataset directory and filter by method.
    
    Args:
        dataset: Dataset name
        dataset_path: Path to dataset directory
        method: Either "search" or "build"
        
    Yields:
        Tuple of (file_path, algorithm_name, dataframe)
    """
    dir_path = os.path.join(dataset_path, dataset)
    for file in os.listdir(dir_path):
        if file.endswith(".json"):
            # Filter files based on method
            if method == "search" and "throughput" in file:
                # Only process search files (containing throughput)
                file_path = os.path.join(dir_path, file)
                try:
                    with open(file_path, "r", encoding="ISO-8859-1") as f:
                        data = json.load(f)
                        df = pd.DataFrame(data["benchmarks"])
                        algo_name = tuple(file.split(",")[:2])
                        yield file_path, algo_name, df
                except Exception as e:
                    print(f"Error processing search file {file_path}: {e}. Skipping...")
            elif method == "build" and "throughput" not in file and "base.json" in file:
                # Only process build files (base.json files without throughput)
                file_path = os.path.join(dir_path, file)
                try:
                    with open(file_path, "r", encoding="ISO-8859-1") as f:
                        data = json.load(f)
                        df = pd.DataFrame(data["benchmarks"])
                        algo_name = tuple(file.split(",")[:2])
                        yield file_path, algo_name, df
                except Exception as e:
                    print(f"Error processing build file {file_path}: {e}. Skipping...")


def clean_algo_name(algo_name):
    """Clean and format the algorithm name."""
    return "_".join(algo_name)


def write_csv(file, algo_name, df, extra_columns=None, skip_cols=None):
    """Write DataFrame to CSV file with optional extra columns."""
    algo_name_clean = algo_name[0] if isinstance(algo_name, tuple) else algo_name
    
    # Extract algo and index name from the full name (format: "algo/index")
    split_names = df["name"].str.split("/", n=1, expand=True)
    if len(split_names.columns) > 1:
        index_from_name = split_names[1]
    else:
        index_from_name = split_names[0]
    
    write_data = pd.DataFrame(
        {
            "algo_name": [algo_name_clean] * len(df),
            "index_name": index_from_name.values,
            "time": df["real_time"].values,
        }
    )
    
    if extra_columns:
        for col in extra_columns:
            if col in df.columns:
                write_data[col] = df[col].values
    
    if skip_cols:
        write_data = write_data.drop(columns=skip_cols)
    
    write_data.to_csv(file, index=False)


def convert_json_to_csv_build(dataset, dataset_path):
    """Convert build JSON files to CSV format."""
    for file, algo_name, df in read_json_files(dataset, dataset_path, "build"):
        try:
            algo_name_clean = algo_name[0]  # Just the algorithm name, not the group
            build_file = os.path.join(dataset_path, dataset, f"{algo_name_clean},base.csv")
            write_csv(build_file, algo_name, df)
        except Exception as e:
            print(f"Error processing build file {file}: {e}. Skipping...")


def convert_json_to_csv_search(dataset, dataset_path):
    """Convert search JSON files to CSV format with Pareto frontier analysis."""
    for file, algo_name, df in read_json_files(dataset, dataset_path, "search"):
        try:
            algo_name_clean = algo_name[0]  # Just the algorithm name, not the group
            # Extract index name from full name (format: "algo/index")
            split_names = df["name"].str.split("/", n=1, expand=True)
            if len(split_names.columns) > 1:
                index_names = split_names[1]
            else:
                index_names = split_names[0]
            
            write = pd.DataFrame(
                {
                    "algo_name": [algo_name_clean] * len(df),
                    "index_name": index_names.values,
                    "recall": df["Recall"].values,
                    "throughput": df["items_per_second"].values,
                    "latency": df["Latency"].values,
                }
            )
            
            # Write raw data
            raw_file = os.path.join(dataset_path, dataset, f"{algo_name_clean},base,raw.csv")
            write.to_csv(raw_file, index=False)
            
            # Calculate Pareto frontier for throughput
            throughput_frontier = get_frontier(write, "throughput")
            throughput_file = os.path.join(dataset_path, dataset, f"{algo_name_clean},base,throughput.csv")
            throughput_frontier.to_csv(throughput_file, index=False)
            
            # Calculate Pareto frontier for latency
            latency_frontier = get_frontier(write, "latency")
            latency_file = os.path.join(dataset_path, dataset, f"{algo_name_clean},base,latency.csv")
            latency_frontier.to_csv(latency_file, index=False)
            
        except Exception as e:
            print(f"Error processing search file {file}: {e}. Skipping...")


def create_pointset(data, xn, yn):
    """Create Pareto frontier point set."""
    metrics = {
        "k-nn": {"worst": float("-inf")},
        "throughput": {"worst": float("-inf")},
        "latency": {"worst": float("inf")},
    }
    
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
    """Calculate Pareto frontier for given metric."""
    lines = create_pointset(df.values.tolist(), "k-nn", metric)
    return pd.DataFrame(lines, columns=df.columns)


def write_frontier(file, write_data, metric):
    """Write Pareto frontier data to CSV file."""
    frontier_data = get_frontier(write_data, metric)
    frontier_data.to_csv(file, index=False)
