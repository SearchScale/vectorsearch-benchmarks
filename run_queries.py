#!/usr/bin/env python3
import numpy as np
import struct
import requests
import json
import time
import sys
import argparse

def parse_arguments():
    """Parse command line arguments"""
    parser = argparse.ArgumentParser(description='Run Solr KNN benchmark queries')
    
    # Query configuration
    parser.add_argument('--num-queries', type=int, default=500,
                        help='Number of queries to test (default: 500)')
    parser.add_argument('--warmup-queries', type=int, default=20,
                        help='Number of warmup queries before measurements (default: 20)')
    parser.add_argument('--top-k', type=int, default=100,
                        help='Number of nearest neighbors to retrieve (default: 100)')
    parser.add_argument('--ef-search', type=int, default=800,
                        help='Overfetch parameter for topK (default: 800)')
    
    # File paths
    parser.add_argument('--query-file', type=str, default='data/queries.fbin',
                        help='Path to query vectors file (default: data/queries.fbin)')
    parser.add_argument('--neighbors-file', type=str, default='data/groundtruth.10M.neighbors.ibin',
                        help='Path to ground truth neighbors file (default: data/groundtruth.10M.neighbors.ibin)')
    parser.add_argument('--output-file', type=str, default='results.json',
                        help='Path to output results file (default: results.json)')
    
    # Solr configuration
    parser.add_argument('--solr-url', type=str, default='http://localhost:8983',
                        help='Solr base URL (default: http://localhost:8983)')
    parser.add_argument('--collection', type=str, default='test',
                        help='Solr collection name (default: test)')
    parser.add_argument('--vector-field', type=str, default='article_vector',
                        help='Name of the vector field in Solr (default: article_vector)')
    
    # Other options
    parser.add_argument('--timeout', type=int, default=30,
                        help='Request timeout in seconds (default: 30)')
    parser.add_argument('--skip-queries', type=int, default=0,
                        help='Number of queries to skip from the beginning of the file (default: 0)')
    
    return parser.parse_args()

def read_query_vectors(filename, num_to_read, skip=0):
    """Read query vectors from fbin file"""
    with open(filename, 'rb') as f:
        num_vectors = struct.unpack('I', f.read(4))[0]
        dim = struct.unpack('I', f.read(4))[0]
        
        # Skip vectors if needed
        if skip > 0:
            f.seek(8 + skip * dim * 4)  # 8 bytes header + skip vectors
        
        vectors = []
        for i in range(min(num_to_read, num_vectors - skip)):
            vector = np.frombuffer(f.read(dim * 4), dtype=np.float32)
            vectors.append(vector)
        
        return vectors

def read_ground_truth(neighbors_file, num_queries, skip=0, k=100):
    """Read ground truth neighbors"""
    with open(neighbors_file, 'rb') as f:
        num_queries_total = struct.unpack('I', f.read(4))[0]
        gt_k = struct.unpack('I', f.read(4))[0]
        
        # Skip if needed
        if skip > 0:
            f.seek(8 + skip * gt_k * 4)
        
        all_neighbors = []
        for i in range(min(num_queries, num_queries_total - skip)):
            neighbors = np.frombuffer(f.read(gt_k * 4), dtype=np.int32)
            all_neighbors.append(neighbors[:k])
        
        return all_neighbors

def perform_knn_query_with_timing(query_vector, topK, args):
    """Perform single KNN query and measure latency"""
    url = f"{args.solr_url}/solr/{args.collection}/select?omitHeader=true"
    
    # Convert vector to string format for Solr
    vector_str = "[" + ",".join(map(str, query_vector)) + "]"
    
    payload = {
        "fields": "id,score",
        "query": {"lucene": {
                "df": "name",
                "query": "{!knn f=" + args.vector_field + " topK=" + str(args.ef_search) + "}" + vector_str
            }
        },
        "limit": topK
    }
    headers = {
        "Content-Type": "application/json"
    }
    
    try:
        # Measure latency
        start_time = time.perf_counter()
        response = requests.request("GET", url, json=payload, headers=headers, timeout=args.timeout)
        end_time = time.perf_counter()
        
        latency_ms = (end_time - start_time) * 1000  # Convert to milliseconds
        
        response_data = response.json()
        
        # Extract IDs from response
        docs = response_data.get('response', {}).get('docs', [])
        retrieved_ids = [int(doc['id']) for doc in docs]
        
        return retrieved_ids, latency_ms
    except Exception as e:
        print(f"Error in query: {str(e)}")
        return [], None

def calculate_recall_at_k(retrieved_ids, ground_truth_ids):
    """Calculate recall@k for a single query"""
    retrieved_set = set(retrieved_ids)
    ground_truth_set = set(ground_truth_ids)
    
    intersection = retrieved_set & ground_truth_set
    recall = len(intersection) / len(ground_truth_set) if len(ground_truth_set) > 0 else 0
    
    return recall

def run_warmup_queries(query_vectors, num_warmup, args):
    """Run warmup queries to prime the cache"""
    warmup_vectors = query_vectors[:num_warmup]
    
    for i, vector in enumerate(warmup_vectors):
        _, _ = perform_knn_query_with_timing(vector, topK=args.top_k, args=args)

def main():
    # Parse command line arguments
    args = parse_arguments()
    
    # Read all query vectors (warmup + test)
    total_vectors_needed = args.warmup_queries + args.num_queries
    all_query_vectors = read_query_vectors(args.query_file, total_vectors_needed, skip=args.skip_queries)
    
    # Split into warmup and test vectors
    warmup_vectors = all_query_vectors[:args.warmup_queries]
    test_vectors = all_query_vectors[args.warmup_queries:]
    
    # Read ground truth (only for test queries, skip warmup)
    ground_truth_all = read_ground_truth(args.neighbors_file, num_queries=args.num_queries, 
                                         skip=args.warmup_queries + args.skip_queries, k=args.top_k)
    
    # Run warmup queries
    run_warmup_queries(warmup_vectors, args.warmup_queries, args)
    
    # Process test queries and measure latency
    recalls = []
    latencies = []
    
    for i, (query_vector, ground_truth) in enumerate(zip(test_vectors, ground_truth_all)):
        # Perform query with timing
        retrieved_ids, latency_ms = perform_knn_query_with_timing(query_vector, topK=args.top_k, args=args)
        
        if len(retrieved_ids) > 0 and latency_ms is not None:
            recall = calculate_recall_at_k(retrieved_ids, ground_truth)
            recalls.append(recall)
            latencies.append(latency_ms)
        else:
            recalls.append(0.0)  # Failed query
    
    # Calculate and report only the two metrics
    avg_recall = np.mean(recalls) * 100
    mean_latency = np.mean(latencies)
    
    print(f"Average Recall@{args.top_k}: {avg_recall:.2f}%")
    print(f"Mean Latency: {mean_latency:.2f}ms")
    
    # Create results.json file with metrics
    results = {
        "metrics": {
            "recall-accuracy": avg_recall,
            "mean-latency": mean_latency
        }
    }
    
    # Save results to output file
    with open(args.output_file, "w") as f:
        json.dump(results, f, indent=2)
    
    print(f"Results saved to {args.output_file}")

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)
