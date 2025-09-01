#!/bin/bash
# System setup
export LD_LIBRARY_PATH=/home/puneet/code/cuvs/cpp/build:$LD_LIBRARY_PATH
export MAVEN_OPTS="-Xms64g -Xmx128g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Create results directory
mkdir -p results

# WIKI88M DATASET CONFIGURATION
dataset="wiki88M"
dataset_file="/data2/wiki88M/base.88M.fbin"
query_file="/data2/wiki88M/queries.fbin"
groundtruth_file="/data2/wiki88M/groundtruth.88M.neighbors.ibin"
num_docs=87555327
vector_dimension=768
index_of_vector=1
vector_colname="embedding"
num_queries=1000
timeout_duration="720m"  # 12 hours

# ALGORITHM PARAMETERS
flush_frequency=500000
cagra_degree=64
cagra_threads=4
cuvs_writer_threads=8
lucene_m=16
lucene_ef=128
lucene_indexing_threads=16

# Results file
results_file="results/wiki88M_benchmark_$(date +%Y%m%d_%H%M%S).csv"
echo "Algorithm,Dataset,VectorDim,NumDocs,FlushFreq,CagraDegree,CagraIntDegree,IndexingThreads,CuvsWriterThreads,LuceneM,LuceneEF,IndexingTime,QueryTime,RecallAccuracy,BenchmarkID,ErrorDetails" > $results_file

# Enhanced cleanup
cleanup_resources() {
    echo "=== CLEANUP BETWEEN EXPERIMENTS ==="
    pkill -f "LuceneCuvsBenchmarks" 2>/dev/null || true
    pkill -f "exec-maven-plugin" 2>/dev/null || true
    sleep 10
    rm -rf cuvsIndex hnswIndex luceneIndex 2>/dev/null || true
    if [ -w /proc/sys/vm/drop_caches ]; then
        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true
    fi
    sleep 5
    echo "✓ Cleanup completed"
}

# CAGRA job creation
create_cagra_job_file() {
    local job_file="cagra_wiki88M.json"
    local intermediate=$((cagra_degree * 2))
    
    cat > $job_file << EOF
{
  "benchmarkID" : "WIKI88M_CAGRA",
  "datasetFile" : "${dataset_file}",
  "indexOfVector" : ${index_of_vector},
  "vectorColName" : "${vector_colname}",
  "numDocs" : ${num_docs},
  "vectorDimension" : ${vector_dimension},
  "queryFile" : "${query_file}",
  "numQueriesToRun" : ${num_queries},
  "flushFreq" : ${flush_frequency},
  "topK" : 100,
  "numIndexThreads" : ${cagra_threads},
  "cuvsWriterThreads" : ${cuvs_writer_threads},
  "queryThreads" : 1,
  "createIndexInMemory" : false,
  "cleanIndexDirectory" : true,
  "saveResultsOnDisk" : false,
  "hasColNames" : true,
  "algoToRun" : "CAGRA_HNSW",
  "groundTruthFile" : "${groundtruth_file}",
  "cuvsIndexDirPath" : "cuvsIndex",
  "hnswIndexDirPath" : "hnswIndex",
  "hnswMaxConn" : 16,
  "hnswBeamWidth" : 64,
  "cagraIntermediateGraphDegree" : ${intermediate},
  "cagraGraphDegree" : ${cagra_degree},
  "cagraITopK" : 128,
  "cagraSearchWidth" : 64,
  "cagraHnswLayers" : 1,
  "loadVectorsInMemory": false
}
EOF
    echo $job_file
}

# Lucene job creation
create_lucene_job_file() {
    local job_file="lucene_wiki88M.json"
    
    cat > $job_file << EOF
{
  "benchmarkID" : "WIKI88M_LUCENE",
  "datasetFile" : "${dataset_file}",
  "indexOfVector" : ${index_of_vector},
  "vectorColName" : "${vector_colname}",
  "numDocs" : ${num_docs},
  "vectorDimension" : ${vector_dimension},
  "queryFile" : "${query_file}",
  "numQueriesToRun" : ${num_queries},
  "flushFreq" : ${flush_frequency},
  "topK" : 100,
  "numIndexThreads" : ${lucene_indexing_threads},
  "cuvsWriterThreads" : 8,
  "queryThreads" : 4,
  "createIndexInMemory" : false,
  "cleanIndexDirectory" : true,
  "saveResultsOnDisk" : false,
  "hasColNames" : true,
  "algoToRun" : "LUCENE_HNSW",
  "groundTruthFile" : "${groundtruth_file}",
  "cuvsIndexDirPath" : "cuvsIndex",
  "hnswIndexDirPath" : "hnswIndex",
  "hnswMaxConn" : ${lucene_m},
  "hnswBeamWidth" : ${lucene_ef},
  "cagraIntermediateGraphDegree" : 128,
  "cagraGraphDegree" : 64,
  "cagraITopK" : 256,
  "cagraSearchWidth" : 128,
  "cagraHnswLayers" : 1,
  "loadVectorsInMemory": false
}
EOF
    echo $job_file
}

# Extract metrics function
extract_metrics() {
    local output="$1"
    local indexing_time=$(echo "$output" | grep -o '".*indexing-time" : [0-9]*' | grep -o '[0-9]*$')
    local query_time=$(echo "$output" | grep -o '".*query-time" : [0-9]*' | grep -o '[0-9]*$')
    local recall_accuracy=$(echo "$output" | grep -o '".*recall-accuracy" : [0-9.]*' | grep -o '[0-9.]*$')
    
    if [ -z "$recall_accuracy" ]; then
        recall_accuracy=$(echo "$output" | grep -o 'recall-accuracy.*: [0-9.]*' | grep -o '[0-9.]*$')
    fi
    
    if echo "$output" | grep -qi "OutOfMemoryError\|java.lang.OutOfMemoryError"; then
        echo "OOM,OOM,$recall_accuracy,OutOfMemoryError"
    elif echo "$output" | grep -qi "Cannot allocate memory\|insufficient memory"; then
        echo "OOM,OOM,$recall_accuracy,InsufficientMemory"
    elif echo "$output" | grep -qi "CUDA out of memory\|GPU memory"; then
        echo "OOM,OOM,$recall_accuracy,GPUOutOfMemory"
    else
        echo "$indexing_time,$query_time,$recall_accuracy,Success"
    fi
}

echo "=================================================================="
echo "WIKI88M BENCHMARK (87.6M × 768D vectors)"
echo "=================================================================="
echo "Dataset: $dataset_file"
echo "Queries: $query_file"
echo "Ground truth: $groundtruth_file"
echo ""
echo "Parameters:"
echo "  - Documents: $num_docs"
echo "  - Dimensions: $vector_dimension"
echo "  - Flush frequency: $flush_frequency"
echo "  - CAGRA degree: $cagra_degree (intermediate: $((cagra_degree * 2)))"
echo "  - CAGRA threads: $cagra_threads indexing, $cuvs_writer_threads cuVS"
echo "  - Lucene M: $lucene_m, EF: $lucene_ef, threads: $lucene_indexing_threads"
echo ""
echo "TOTAL EXPERIMENTS: 2 (CAGRA + Lucene)"
echo "Timeout per experiment: $timeout_duration"
echo "Results will be saved to: $results_file"
echo "=================================================================="

# Experiment 1: CAGRA-HNSW
echo ""
echo "####################################################################"
echo "### EXPERIMENT 1/2: CAGRA-HNSW on Wiki88M ###"
echo "####################################################################"

cleanup_resources
job_file=$(create_cagra_job_file)

echo "CAGRA experiment started at: $(date)"

if output=$(timeout $timeout_duration mvn exec:java \
    -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
    -Dexec.args="$job_file" 2>&1); then
    
    metrics=$(extract_metrics "$output")
    IFS=',' read -r indexing_time query_time recall_accuracy error_details <<< "$metrics"
    
    if [[ "$indexing_time" != "OOM" && "$indexing_time" != "ERROR" && "$indexing_time" != "TIMEOUT" ]]; then
        echo " CAGRA SUCCESS: Index=${indexing_time}ms, Query=${query_time}ms, Recall=${recall_accuracy}%"
    else
        echo " CAGRA FAILED: $error_details"
    fi
    
    echo "CAGRA_HNSW,$dataset,$vector_dimension,$num_docs,$flush_frequency,$cagra_degree,$((cagra_degree*2)),$cagra_threads,$cuvs_writer_threads,N/A,N/A,$indexing_time,$query_time,$recall_accuracy,WIKI88M_CAGRA,$error_details" >> $results_file
else
    echo " CAGRA TIMEOUT/CRASH after $timeout_duration"
    echo "CAGRA_HNSW,$dataset,$vector_dimension,$num_docs,$flush_frequency,$cagra_degree,$((cagra_degree*2)),$cagra_threads,$cuvs_writer_threads,N/A,N/A,TIMEOUT,TIMEOUT,TIMEOUT,WIKI88M_CAGRA,TIMEOUT" >> $results_file
fi

rm -f $job_file
echo "CAGRA experiment completed at: $(date)"

# Experiment 2: Lucene-HNSW
echo ""
echo "####################################################################"
echo "### EXPERIMENT 2/2: Lucene-HNSW on Wiki88M ###"
echo "####################################################################"

cleanup_resources
job_file=$(create_lucene_job_file)

echo "Lucene experiment started at: $(date)"

if output=$(timeout $timeout_duration mvn exec:java \
    -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
    -Dexec.args="$job_file" 2>&1); then
    
    metrics=$(extract_metrics "$output")
    IFS=',' read -r indexing_time query_time recall_accuracy error_details <<< "$metrics"
    
    if [[ "$indexing_time" != "OOM" && "$indexing_time" != "ERROR" && "$indexing_time" != "TIMEOUT" ]]; then
        echo " LUCENE SUCCESS: Index=${indexing_time}ms, Query=${query_time}ms, Recall=${recall_accuracy}%"
    else
        echo " LUCENE FAILED: $error_details"
    fi
    
    echo "LUCENE_HNSW,$dataset,$vector_dimension,$num_docs,$flush_frequency,N/A,N/A,$lucene_indexing_threads,N/A,$lucene_m,$lucene_ef,$indexing_time,$query_time,$recall_accuracy,WIKI88M_LUCENE,$error_details" >> $results_file
else
    echo " LUCENE TIMEOUT/CRASH after $timeout_duration"
    echo "LUCENE_HNSW,$dataset,$vector_dimension,$num_docs,$flush_frequency,N/A,N/A,$lucene_indexing_threads,N/A,$lucene_m,$lucene_ef,TIMEOUT,TIMEOUT,TIMEOUT,WIKI88M_LUCENE,TIMEOUT" >> $results_file
fi

rm -f $job_file
echo "Lucene experiment completed at: $(date)"

echo ""
echo "========== WIKI88M BENCHMARK COMPLETE =========="
total_completed=$(tail -n +2 $results_file | wc -l)
echo "Total experiments completed: $total_completed/2"
echo "Results saved to: $results_file"

# Final summary
successful_runs=$(grep -v "TIMEOUT\|ERROR\|OOM" $results_file | tail -n +2 | wc -l)
failed_runs=$(grep -E "TIMEOUT|ERROR|OOM" $results_file | wc -l)

echo ""
echo "Final Results Summary:"
echo "====================="
echo "Successful runs: $successful_runs"
echo "Failed runs: $failed_runs"
if [[ $((successful_runs + failed_runs)) -gt 0 ]]; then
    echo "Success rate: $(( successful_runs * 100 / (successful_runs + failed_runs) ))%"
fi

echo ""
echo "Results file: $results_file"
echo "View results: cat $results_file"

echo ""
echo "========== RESULTS TABLE =========="
cat $results_file

# Cleanup final job files
rm -f cagra_wiki88M.json lucene_wiki88M.json 2>/dev/null || true

