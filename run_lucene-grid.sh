#!/bin/bash

# Export the library path
export LD_LIBRARY_PATH=/home/ishan/code/cuvs/cpp/build:$LD_LIBRARY_PATH

# Index threads to test
index_threads=(8 16 32)
# MaxConn values to test
max_conn_values=(12 16 24 36)
# BeamWidth values to test
beam_width_values=(64 128 256 512)

# Results file
results_file="lucene_hnsw_experiment_results.csv"
echo "IndexThreads,MaxConn,BeamWidth,IndexingTime,QueryTime,RecallAccuracy" > $results_file

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
    local threads=$1
    local max_conn=$2
    local beam_width=$3
    local job_file="job_lucene_hnsw_${threads}_${max_conn}_${beam_width}.json"
    
    cat > $job_file << EOF
{
  "benchmarkID" : "ANN_SIFT1M_LUCENE_HNSW_T${threads}_M${max_conn}_B${beam_width}",
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
  "algoToRun" : "LUCENE_HNSW",
  "groundTruthFile" : "/data/sift/sift_groundtruth.ivecs",
  "cuvsIndexDirPath" : "cuvsIndex",
  "hnswIndexDirPath" : "hnswIndex",
  "hnswMaxConn" : ${max_conn},
  "hnswBeamWidth" : ${beam_width},
  "cagraIntermediateGraphDegree" : 128,
  "cagraGraphDegree" : 64,
  "cagraITopK" : 256,
  "cagraSearchWidth" : 128,
  "cagraHnswLayers" : 1,
  "loadVectorsInMemory": true
}
EOF
    echo $job_file
}

# Run experiments
total_experiments=$((${#index_threads[@]} * ${#max_conn_values[@]} * ${#beam_width_values[@]}))
current_experiment=0

echo "Starting Lucene HNSW experiment with $total_experiments combinations..."
echo "Index Threads: ${index_threads[@]}"
echo "MaxConn Values: ${max_conn_values[@]}"
echo "BeamWidth Values: ${beam_width_values[@]}"
echo ""

for threads in "${index_threads[@]}"; do
    for max_conn in "${max_conn_values[@]}"; do
        for beam_width in "${beam_width_values[@]}"; do
            current_experiment=$((current_experiment + 1))
            
            echo "=== Experiment $current_experiment/$total_experiments: Threads=$threads, MaxConn=$max_conn, BeamWidth=$beam_width ==="
            
            # Create job file
            job_file=$(create_job_file $threads $max_conn $beam_width)
        
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
                echo "$threads,$max_conn,$beam_width,$indexing_time,$query_time,$recall_accuracy" >> $results_file
            else
                echo "ERROR: Failed to extract metrics. Writing placeholder."
                echo "$threads,$max_conn,$beam_width,ERROR,ERROR,ERROR" >> $results_file
            fi
        else
            echo "ERROR: Experiment failed or timed out"
            echo "$threads,$max_conn,$beam_width,TIMEOUT,TIMEOUT,TIMEOUT" >> $results_file
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