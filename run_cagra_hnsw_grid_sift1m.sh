#!/bin/bash

# Export the library path
export LD_LIBRARY_PATH=/home/ishan/code/cuvs/cpp/build:$LD_LIBRARY_PATH

# Graph degrees to test
graph_degrees=(16 32 48)
# HNSW layers to test
hnsw_layers=(1 2)
# Index threads to test
index_threads=(1 4)

# Results file
results_file="intermediate_experiment_results.csv"
echo "GraphDegree,IntermediateGraphDegree,HnswLayers,IndexThreads,IndexingTime,QueryTime,RecallAccuracy" > $results_file

# Function to extract metrics from Maven output
extract_metrics() {
    local output="$1"
    local indexing_time=$(echo "$output" | grep -o '".*indexing-time" : [0-9]*' | grep -o '[0-9]*$')
    local query_time=$(echo "$output" | grep -o '".*query-time" : [0-9]*' | grep -o '[0-9]*$')
    local recall_accuracy=$(echo "$output" | grep -o '".*recall-accuracy" : [0-9.]*' | grep -o '[0-9.]*$')
    
    # If recall_accuracy is empty, try alternative pattern
    if [ -z "$recall_accuracy" ]; then
        recall_accuracy=$(echo "$output" | grep -o 'recall-accuracy.*: [0-9.]*' | grep -o '[0-9.]*$')
    fi
    
    echo "$indexing_time,$query_time,$recall_accuracy"
}

# Function to create job file
create_job_file() {
    local graph_degree=$1
    local layers=$2
    local threads=$3
    local intermediate_degree=$((graph_degree * 2))  # intermediate_graph_degree = 2*graph_degree
    local job_file="job_temp_${graph_degree}_${layers}_${threads}.json"
    
    cat > $job_file << EOF
{
  "benchmarkID" : "ANN_SIFT1M_CAGRA_HNSW_${graph_degree}_${layers}_T${threads}",
  "datasetFile" : "/data/sift/sift_base.fvecs",
  "indexOfVector" : 3,
  "vectorColName" : "article_vector",
  "numDocs" : 1000000,
  "vectorDimension" : 128,
  "queryFile" : "/data/sift/sift_query.fvecs",
  "numQueriesToRun" : 1000,
  "flushFreq" : 2000000,
  "topK" : 100,
  "numIndexThreads" : ${threads},
  "cuvsWriterThreads" : 8,
  "queryThreads" : 1,
  "createIndexInMemory" : false,
  "cleanIndexDirectory" : true,
  "saveResultsOnDisk" : true,
  "hasColNames" : true,
  "algoToRun" : "CAGRA_HNSW",
  "groundTruthFile" : "/data/sift/sift_groundtruth.ivecs",
  "cuvsIndexDirPath" : "cuvsIndex",
  "hnswIndexDirPath" : "hnswIndex",
  "hnswMaxConn" : 16,
  "hnswBeamWidth" : 100,
  "cagraIntermediateGraphDegree" : ${intermediate_degree},
  "cagraGraphDegree" : ${graph_degree},
  "cagraITopK" : 256,
  "cagraSearchWidth" : 128,
  "cagraHnswLayers" : ${layers},
  "loadVectorsInMemory": true
}
EOF
    echo $job_file
}

# Run experiments
total_experiments=$((${#graph_degrees[@]} * ${#hnsw_layers[@]} * ${#index_threads[@]}))
current_experiment=0

echo "Starting intermediate experiment with $total_experiments combinations..."
echo "Graph Degrees: ${graph_degrees[@]} (with intermediate_graph_degree = 2*graph_degree)"
echo "HNSW Layers: ${hnsw_layers[@]}"
echo "Index Threads: ${index_threads[@]}"
echo ""

for threads in "${index_threads[@]}"; do
    for graph_degree in "${graph_degrees[@]}"; do
        for layers in "${hnsw_layers[@]}"; do
            current_experiment=$((current_experiment + 1))
            intermediate_degree=$((graph_degree * 2))
            
            echo "=== Experiment $current_experiment/$total_experiments: Threads=$threads, GraphDegree=$graph_degree (Intermediate=$intermediate_degree), Layers=$layers ==="
            
            # Create job file
            job_file=$(create_job_file $graph_degree $layers $threads)
            
            # Run the experiment with timeout
            echo "Running: mvn exec:java -Dexec.mainClass=\"com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks\" -Dexec.args=\"$job_file\""
            
            # Capture output and run with timeout
            if output=$(timeout 20m mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" -Dexec.args="$job_file" 2>&1); then
                # Extract metrics
                metrics=$(extract_metrics "$output")
                indexing_time=$(echo "$metrics" | cut -d',' -f1)
                query_time=$(echo "$metrics" | cut -d',' -f2)
                recall_accuracy=$(echo "$metrics" | cut -d',' -f3)
                
                # Validate extracted data
                if [ -n "$indexing_time" ] && [ -n "$query_time" ] && [ -n "$recall_accuracy" ]; then
                    echo "Results: IndexingTime=${indexing_time}ms, QueryTime=${query_time}ms, Recall=${recall_accuracy}%"
                    echo "$graph_degree,$intermediate_degree,$layers,$threads,$indexing_time,$query_time,$recall_accuracy" >> $results_file
                else
                    echo "ERROR: Failed to extract metrics. Writing placeholder."
                    echo "$graph_degree,$intermediate_degree,$layers,$threads,ERROR,ERROR,ERROR" >> $results_file
                fi
            else
                echo "ERROR: Experiment failed or timed out"
                echo "$graph_degree,$intermediate_degree,$layers,$threads,TIMEOUT,TIMEOUT,TIMEOUT" >> $results_file
            fi
            
            # Clean up job file
            rm -f $job_file
            
            echo ""
        done
    done
done

echo "All experiments completed! Results saved to $results_file"
echo ""
echo "Raw results table:"
cat $results_file