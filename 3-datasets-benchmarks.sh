#!/bin/bash

# System setup
export LD_LIBRARY_PATH=/home/puneet/code/cuvs/cpp/build:$LD_LIBRARY_PATH
export MAVEN_OPTS="-Xms64g -Xmx128g -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Create results directory
mkdir -p results

# DATASET CONFIGURATIONS - Including Wiki88M
declare -A DATASETS
DATASETS["openai_4M"]="/data/openai/OpenAI_4.6Mx1536.fvecs"
DATASETS["wiki_5M"]="/data/wiki_dump_5Mx2048D.csv.gz"
DATASETS["wiki88M"]="/data2/wiki88M/base.88M.fbin"

declare -A QUERIES
QUERIES["openai_4M"]="/data/openai/OpenAI_5Mx1536_query_1000.csv"
QUERIES["wiki_5M"]="/data/queries_2P5M_546.csv"
QUERIES["wiki88M"]="/data2/wiki88M/queries.fbin"

declare -A GROUNDTRUTH
GROUNDTRUTH["openai_4M"]="/data/openai/OpenAI_4.6Mx1536_groundtruth_k100_1000.csv"
GROUNDTRUTH["wiki_5M"]="/data/wikipedia_5M_groundtruth.csv"
GROUNDTRUTH["wiki88M"]="/data2/wiki88M/groundtruth.88M.neighbors.ibin"

declare -A NUM_DOCS
NUM_DOCS["openai_4M"]=4600000
NUM_DOCS["wiki_5M"]=5000000
NUM_DOCS["wiki88M"]=87555327

declare -A VECTOR_DIMENSIONS
VECTOR_DIMENSIONS["openai_4M"]=1536
VECTOR_DIMENSIONS["wiki_5M"]=2048
VECTOR_DIMENSIONS["wiki88M"]=768

# TIMEOUTS - Extended for comprehensive testing
declare -A TIMEOUTS
TIMEOUTS["openai_4M"]="480m"    # 8 hours
TIMEOUTS["wiki_5M"]="720m"      # 12 hours 
TIMEOUTS["wiki88M"]="720m"      # 12 hours

declare -A INDEX_OF_VECTOR
INDEX_OF_VECTOR["openai_4M"]=1
INDEX_OF_VECTOR["wiki_5M"]=2
INDEX_OF_VECTOR["wiki88M"]=1

declare -A VECTOR_COLNAMES
VECTOR_COLNAMES["openai_4M"]="embedding"
VECTOR_COLNAMES["wiki_5M"]="embedding"
VECTOR_COLNAMES["wiki88M"]="embedding"

# COMPREHENSIVE PARAMETER GRIDS
flush_frequencies=(100000 500000 1000000)  # 3 flush frequency options
cagra_degrees=(32 64)                      # 2 degree options
cagra_threads=(4 8)                        # 2 indexing thread options  
cuvs_writer_threads=(4 8)                  # 2 cuVS thread options

# Lucene HNSW parameters
declare -A LUCENE_M_VALUES
LUCENE_M_VALUES["openai_4M"]="16"
LUCENE_M_VALUES["wiki_5M"]="16"  
LUCENE_M_VALUES["wiki88M"]="16"

declare -A LUCENE_EF_VALUES
LUCENE_EF_VALUES["openai_4M"]="128"
LUCENE_EF_VALUES["wiki_5M"]="128"
LUCENE_EF_VALUES["wiki88M"]="128"

# Results file in dedicated folder
results_file="results/comprehensive_benchmark_$(date +%Y%m%d_%H%M%S).csv"
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
    local dataset=$1
    local flush_freq=$2
    local degree=$3 
    local threads=$4
    local cuvs_threads=$5
    local intermediate=$((degree * 2))
    local job_file="cagra_${dataset}_${degree}_${threads}_${cuvs_threads}_f${flush_freq}.json"
    
    local num_queries=1000
    case $dataset in
        "wiki_5M") num_queries=546 ;;
        "wiki88M") num_queries=1000 ;;
    esac
    
    cat > $job_file << EOF
{
  "benchmarkID" : "COMP_${dataset}_CAGRA_F${flush_freq}_${degree}_T${threads}_C${cuvs_threads}",
  "datasetFile" : "${DATASETS[$dataset]}",
  "indexOfVector" : ${INDEX_OF_VECTOR[$dataset]},
  "vectorColName" : "${VECTOR_COLNAMES[$dataset]}",
  "numDocs" : ${NUM_DOCS[$dataset]},
  "vectorDimension" : ${VECTOR_DIMENSIONS[$dataset]},
  "queryFile" : "${QUERIES[$dataset]}",
  "numQueriesToRun" : ${num_queries},
  "flushFreq" : ${flush_freq},
  "topK" : 100,
  "numIndexThreads" : ${threads},
  "cuvsWriterThreads" : ${cuvs_threads},
  "queryThreads" : 1,
  "createIndexInMemory" : false,
  "cleanIndexDirectory" : true,
  "saveResultsOnDisk" : false,
  "hasColNames" : true,
  "algoToRun" : "CAGRA_HNSW",
  "groundTruthFile" : "${GROUNDTRUTH[$dataset]}",
  "cuvsIndexDirPath" : "cuvsIndex",
  "hnswIndexDirPath" : "hnswIndex",
  "hnswMaxConn" : 16,
  "hnswBeamWidth" : 64,
  "cagraIntermediateGraphDegree" : ${intermediate},
  "cagraGraphDegree" : ${degree},
  "cagraITopK" : 128,
  "cagraSearchWidth" : 64,
  "cagraHnswLayers" : 1,
  "loadVectorsInMemory": false
}
EOF
    echo $job_file
}

# Lucene job creation - 16 threads for all datasets except Wiki 5M
create_lucene_job_file() {
    local dataset=$1
    local flush_freq=$2
    local m_value=$3
    local ef_value=$4
    local job_file="lucene_${dataset}_${m_value}_${ef_value}_f${flush_freq}.json"
    
    local num_queries=1000
    local indexing_threads=16
    case $dataset in
        "wiki_5M") 
            num_queries=546
            indexing_threads=4  # Special handling for Wiki 5M
            ;;
        "wiki88M") num_queries=1000 ;;
    esac
    
    cat > $job_file << EOF
{
  "benchmarkID" : "COMP_${dataset}_LUCENE_F${flush_freq}_M${m_value}_EF${ef_value}",
  "datasetFile" : "${DATASETS[$dataset]}",
  "indexOfVector" : ${INDEX_OF_VECTOR[$dataset]},
  "vectorColName" : "${VECTOR_COLNAMES[$dataset]}",
  "numDocs" : ${NUM_DOCS[$dataset]},
  "vectorDimension" : ${VECTOR_DIMENSIONS[$dataset]},
  "queryFile" : "${QUERIES[$dataset]}",
  "numQueriesToRun" : ${num_queries},
  "flushFreq" : ${flush_freq},
  "topK" : 100,
  "numIndexThreads" : ${indexing_threads},
  "cuvsWriterThreads" : 8,
  "queryThreads" : 4,
  "createIndexInMemory" : false,
  "cleanIndexDirectory" : true,
  "saveResultsOnDisk" : false,
  "hasColNames" : true,
  "algoToRun" : "LUCENE_HNSW",
  "groundTruthFile" : "${GROUNDTRUTH[$dataset]}",
  "cuvsIndexDirPath" : "cuvsIndex",
  "hnswIndexDirPath" : "hnswIndex",
  "hnswMaxConn" : ${m_value},
  "hnswBeamWidth" : ${ef_value},
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

# Calculate total experiments
total_cagra_per_dataset=$((${#flush_frequencies[@]} * ${#cagra_degrees[@]} * ${#cagra_threads[@]} * ${#cuvs_writer_threads[@]}))
total_lucene_per_dataset=${#flush_frequencies[@]}

# Wiki 5M has reduced CAGRA combinations due to optimizations
wiki_5m_cagra_combinations=$((${#flush_frequencies[@]} * ${#cagra_degrees[@]} * 1 * 1))  # 1 thread option, 1 cuvs option

total_openai_experiments=$((total_cagra_per_dataset + total_lucene_per_dataset))
total_wiki5m_experiments=$((wiki_5m_cagra_combinations + total_lucene_per_dataset))
total_wiki88m_experiments=$((total_cagra_per_dataset + total_lucene_per_dataset))
total_experiments=$((total_openai_experiments + total_wiki5m_experiments + total_wiki88m_experiments))

echo "=================================================================="
echo "COMPREHENSIVE MULTI-PARAMETER BENCHMARK"
echo "=================================================================="
echo "Flush frequencies: ${flush_frequencies[@]}"
echo "CAGRA degrees: ${cagra_degrees[@]}"
echo "Threading options: ${cagra_threads[@]} (indexing) × ${cuvs_writer_threads[@]} (cuVS)"
echo "Lucene indexing threads: 16 (OpenAI/Wiki88M), 4 (Wiki 5M)"
echo ""
echo "Experiment breakdown:"
echo "  - OpenAI 4M: $total_openai_experiments experiments"
echo "  - Wiki 5M: $total_wiki5m_experiments experiments (optimized)"
echo "  - Wiki88M: $total_wiki88m_experiments experiments"
echo ""
echo "TOTAL EXPERIMENTS: $total_experiments"
echo "Estimated runtime: 50-70 hours"
echo "Results will be saved to: $results_file"
echo "=================================================================="

# Main benchmark loop
current_experiment=0

for dataset in "openai_4M" "wiki_5M" "wiki88M"; do
    echo ""
    echo "###################################################################"
    echo "### BENCHMARKING ${dataset} (${NUM_DOCS[$dataset]} × ${VECTOR_DIMENSIONS[$dataset]}D) ###"
    echo "###################################################################"
    
    for flush_freq in "${flush_frequencies[@]}"; do
        echo ""
        echo "--- Testing Flush Frequency: $flush_freq ---"
        
        # DATASET-SPECIFIC OPTIMIZATIONS for Wiki 5M
        thread_options=(${cagra_threads[@]})
        cuvs_options=(${cuvs_writer_threads[@]})
        
        if [[ $dataset == "wiki_5M" ]]; then
            echo "🔧 Applying Wiki 5M optimizations"
            if [[ $flush_freq -gt 500000 ]]; then
                echo "   - Limiting flush frequency to 500K for Wiki 5M stability"
                flush_freq=500000
            fi
            thread_options=(4)  # Only 4 indexing threads for CAGRA
            cuvs_options=(4)    # Only 4 cuVS threads for CAGRA
        fi
        
        # CAGRA parameter sweep
        echo "=== CAGRA-HNSW Parameter Sweep (Flush: $flush_freq) ==="
        for degree in "${cagra_degrees[@]}"; do
            for threads in "${thread_options[@]}"; do
                for cuvs_threads in "${cuvs_options[@]}"; do
                    current_experiment=$((current_experiment + 1))
                    
                    echo "======================================================================"
                    echo "=== CAGRA Experiment $current_experiment/$total_experiments ==="
                    echo "=== Dataset: ${dataset} | Flush: $flush_freq | Degree: $degree | Threads: $threads | CuVS: $cuvs_threads ==="
                    echo "======================================================================"
                    
                    cleanup_resources
                    job_file=$(create_cagra_job_file "$dataset" $flush_freq $degree $threads $cuvs_threads)
                    
                    echo "Started at: $(date)"
                    
                    if output=$(timeout ${TIMEOUTS[$dataset]} mvn exec:java \
                        -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
                        -Dexec.args="$job_file" 2>&1); then
                        
                        metrics=$(extract_metrics "$output")
                        IFS=',' read -r indexing_time query_time recall_accuracy error_details <<< "$metrics"
                        
                        if [[ "$indexing_time" != "OOM" && "$indexing_time" != "ERROR" && "$indexing_time" != "TIMEOUT" ]]; then
                            echo "CAGRA SUCCESS: Index=${indexing_time}ms, Query=${query_time}ms, Recall=${recall_accuracy}%"
                        else
                            echo "CAGRA FAILED: $error_details"
                        fi
                        
                        echo "CAGRA_HNSW,$dataset,${VECTOR_DIMENSIONS[$dataset]},${NUM_DOCS[$dataset]},$flush_freq,$degree,$((degree*2)),$threads,$cuvs_threads,N/A,N/A,$indexing_time,$query_time,$recall_accuracy,COMP_${dataset}_CAGRA_F${flush_freq}_${degree}_T${threads}_C${cuvs_threads},$error_details" >> $results_file
                    else
                        echo "CAGRA TIMEOUT/CRASH after ${TIMEOUTS[$dataset]}"
                        echo "CAGRA_HNSW,$dataset,${VECTOR_DIMENSIONS[$dataset]},${NUM_DOCS[$dataset]},$flush_freq,$degree,$((degree*2)),$threads,$cuvs_threads,N/A,N/A,TIMEOUT,TIMEOUT,TIMEOUT,COMP_${dataset}_CAGRA_F${flush_freq}_${degree}_T${threads}_C${cuvs_threads},TIMEOUT" >> $results_file
                    fi
                    
                    rm -f $job_file
                    echo "Completed at: $(date)"
                    echo ""
                done
            done
        done
        
        # Lucene parameter sweep for this flush frequency
        echo "=== Lucene HNSW Parameter Sweep (Flush: $flush_freq) ==="
        IFS=' ' read -ra m_values <<< "${LUCENE_M_VALUES[$dataset]}"
        IFS=' ' read -ra ef_values <<< "${LUCENE_EF_VALUES[$dataset]}"
        
        for m_val in "${m_values[@]}"; do
            for ef_val in "${ef_values[@]}"; do
                current_experiment=$((current_experiment + 1))
                
                # Get indexing threads for this dataset
                indexing_threads=16
                if [[ $dataset == "wiki_5M" ]]; then
                    indexing_threads=4
                fi
                
                echo "======================================================================"
                echo "=== Lucene Experiment $current_experiment/$total_experiments ==="
                echo "=== Dataset: ${dataset} | Flush: $flush_freq | M: $m_val | EF: $ef_val | IndexThreads: $indexing_threads ==="
                echo "======================================================================"
                
                cleanup_resources
                job_file=$(create_lucene_job_file "$dataset" $flush_freq $m_val $ef_val)
                
                echo "Started at: $(date)"
                
                if output=$(timeout ${TIMEOUTS[$dataset]} mvn exec:java \
                    -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
                    -Dexec.args="$job_file" 2>&1); then
                    
                    metrics=$(extract_metrics "$output")
                    IFS=',' read -r indexing_time query_time recall_accuracy error_details <<< "$metrics"
                    
                    if [[ "$indexing_time" != "OOM" && "$indexing_time" != "ERROR" && "$indexing_time" != "TIMEOUT" ]]; then
                        echo "LUCENE SUCCESS: Index=${indexing_time}ms, Query=${query_time}ms, Recall=${recall_accuracy}%"
                    else
                        echo "LUCENE FAILED: $error_details"
                    fi
                    
                    echo "LUCENE_HNSW,$dataset,${VECTOR_DIMENSIONS[$dataset]},${NUM_DOCS[$dataset]},$flush_freq,N/A,N/A,$indexing_threads,N/A,$m_val,$ef_val,$indexing_time,$query_time,$recall_accuracy,COMP_${dataset}_LUCENE_F${flush_freq}_M${m_val}_EF${ef_val},$error_details" >> $results_file
                else
                    echo "LUCENE TIMEOUT/CRASH after ${TIMEOUTS[$dataset]}"
                    echo "LUCENE_HNSW,$dataset,${VECTOR_DIMENSIONS[$dataset]},${NUM_DOCS[$dataset]},$flush_freq,N/A,N/A,$indexing_threads,N/A,$m_val,$ef_val,TIMEOUT,TIMEOUT,TIMEOUT,COMP_${dataset}_LUCENE_F${flush_freq}_M${m_val}_EF${ef_val},TIMEOUT" >> $results_file
                fi
                
                rm -f $job_file
                echo "Completed at: $(date)"
                echo ""
            done
        done
    done
    
    echo ""
    echo "### COMPLETED ${dataset} - $(grep "$dataset" $results_file | wc -l) experiments ###"
done

echo ""
echo "========== COMPREHENSIVE BENCHMARK COMPLETE =========="
echo "Total experiments completed: $(tail -n +2 $results_file | wc -l)/$total_experiments"
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
echo "All results are saved in the 'results/' folder:"
echo "   - CSV file: $results_file"
echo "   - View results: cat $results_file"
echo "   - List all results: ls -la results/"
echo ""
echo "========== RAW RESULTS TABLE =========="
cat $results_file

