#!/bin/bash

# Export the library path
export LD_LIBRARY_PATH=/home/puneet/code/cuvs/cpp/build:$LD_LIBRARY_PATH

# JVM Memory Settings - optimized for very large datasets (100M scale)
export MAVEN_OPTS="-Xms64g -Xmx128g -XX:+UseZGC -XX:MaxGCPauseMillis=100 -XX:+UnlockExperimentalVMOptions -XX:+UseLargePages"

# DATASET CONFIGURATIONS - SIFT-100M + OpenAI-4.6M
declare -A DATASETS
DATASETS["100M"]="/data/sift1B/extracted/sift_100M_base.fvecs"
DATASETS["openai_4M"]="/data/openai/OpenAI_4.6Mx1536.fvecs"

declare -A QUERIES
QUERIES["100M"]="/data/sift1B/bigann_query.bvecs"
QUERIES["openai_4M"]="/data/openai/OpenAI_5Mx1536_query_1000.csv"

declare -A GROUNDTRUTH
GROUNDTRUTH["100M"]="/data/sift1B/gnd/idx_100M.ivecs"
GROUNDTRUTH["openai_4M"]="/data/openai/OpenAI_4.6Mx1536_groundtruth_k100_1000.csv"

declare -A NUM_DOCS
NUM_DOCS["100M"]=100000000
NUM_DOCS["openai_4M"]=4600000

declare -A VECTOR_DIMENSIONS
VECTOR_DIMENSIONS["100M"]=128
VECTOR_DIMENSIONS["openai_4M"]=1536

declare -A TIMEOUTS
TIMEOUTS["100M"]="600m"       # 10 hours for 100M - much longer than 10M
TIMEOUTS["openai_4M"]="120m"  # 2 hours for 4.6M OpenAI

# FLUSH FREQUENCY VARIATIONS - Reduced to 2 key values
flush_frequencies=(500000 2000000)  # 500K, 2M - focused comparison

# ALGORITHM PARAMETERS - Reduced for 100M scale management
# CAGRA-HNSW Parameters (conservative for 100M)
cagra_degrees=(64)        # Only highest performing degree for 100M
indexing_threads=(4)      # Optimal thread count for large scale
cuvs_writer_threads=(8)   # Optimal GPU utilization

# Lucene HNSW Parameters - Very focused for 100M scale
declare -A LUCENE_M_VALUES
LUCENE_M_VALUES["100M"]="24"           # Single best M value for 100M (avoid excessive runtime)
LUCENE_M_VALUES["openai_4M"]="24 32"   # Slightly more exploration for smaller dataset

declare -A LUCENE_EF_VALUES
LUCENE_EF_VALUES["100M"]="128"         # Single EF value for 100M (avoid excessive runtime)
LUCENE_EF_VALUES["openai_4M"]="128 256" # Slightly more exploration for smaller dataset

# Results file
results_file="comprehensive_100M_flush_frequency_benchmark_results.csv"
echo "Algorithm,Dataset,VectorDim,NumDocs,FlushFreq,CagraDegree,CagraIntDegree,IndexingThreads,CuvsWriterThreads,LuceneM,LuceneEF,IndexingTime,QueryTime,RecallAccuracy,BenchmarkID,ErrorDetails" > $results_file

# Enhanced metrics extraction function
extract_metrics() {
    local output="$1"
    local indexing_time=$(echo "$output" | grep -o '".*indexing-time" : [0-9]*' | grep -o '[0-9]*$')
    local query_time=$(echo "$output" | grep -o '".*query-time" : [0-9]*' | grep -o '[0-9]*$')
    local recall_accuracy=$(echo "$output" | grep -o '".*recall-accuracy" : [0-9.]*' | grep -o '[0-9.]*$')
    
    # Alternative patterns for recall accuracy
    if [ -z "$recall_accuracy" ]; then
        recall_accuracy=$(echo "$output" | grep -o 'recall-accuracy.*: [0-9.]*' | grep -o '[0-9.]*$')
    fi
    
    # Check for specific errors
    if echo "$output" | grep -qi "OutOfMemoryError\|java.lang.OutOfMemoryError"; then
        echo "OOM,OOM,$recall_accuracy,OutOfMemoryError"
    elif echo "$output" | grep -qi "Cannot allocate memory\|insufficient memory"; then
        echo "OOM,OOM,$recall_accuracy,InsufficientMemory"
    elif echo "$output" | grep -qi "CUDA out of memory\|GPU memory"; then
        echo "OOM,OOM,$recall_accuracy,GPUOutOfMemory"
    elif echo "$output" | grep -qi "No such file or directory"; then
        echo "ERROR,ERROR,ERROR,FileNotFound"
    elif echo "$output" | grep -qi "cuVS.*error\|cuvs.*failed"; then
        echo "ERROR,ERROR,ERROR,CuVSError"
    elif echo "$output" | grep -qi "BUILD FAILURE\|compilation"; then
        echo "ERROR,ERROR,ERROR,BuildFailure"
    else
        echo "$indexing_time,$query_time,$recall_accuracy,Success"
    fi
}

# Function to clean up resources aggressively for 100M scale
cleanup_resources() {
    echo "=== AGGRESSIVE CLEANUP FOR 100M SCALE ==="
    
    # Kill all Java processes related to benchmarks
    for pid in $(pgrep -f "LuceneCuvsBenchmarks\|java.*exec"); do
        echo "Killing process: $pid"
        kill -9 $pid 2>/dev/null
    done
    
    # Wait for processes to fully terminate
    sleep 10
    
    # Clean up index directories completely
    echo "Cleaning index directories..."
    rm -rf cuvsIndex hnswIndex luceneIndex 2>/dev/null || true
    
    # Clean up any temporary files
    rm -rf /tmp/lucene* /tmp/cuvs* 2>/dev/null || true
    
    # Force filesystem sync
    sync 2>/dev/null || true
    
    # Try to clear file system caches aggressively
    if [ -w /proc/sys/vm/drop_caches ]; then
        echo "Clearing system caches..."
        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null || true
    fi
    
    # Force garbage collection by creating temporary Java process
    timeout 30s java -XX:+UseZGC -Xms1g -Xmx2g -XX:+UnlockExperimentalVMOptions -XX:+ExitOnOutOfMemoryError -c "System.gc(); System.runFinalization(); System.gc();" 2>/dev/null || true
    
    # Wait for cleanup to take effect
    sleep 15
    
    echo "✓ Cleanup completed"
}

# Function to check prerequisites with 100M specific validations
check_dataset_prerequisites() {
    local dataset_size=$1
    
    echo "Checking prerequisites for ${dataset_size}..."
    
    # Check if dataset file exists
    if [ ! -f "${DATASETS[$dataset_size]}" ]; then
        echo " ERROR: Dataset file not found: ${DATASETS[$dataset_size]}"
        return 1
    fi
    
    # Check if query file exists  
    if [ ! -f "${QUERIES[$dataset_size]}" ]; then
        echo "ERROR: Query file not found: ${QUERIES[$dataset_size]}"
        return 1
    fi
    
    # Check if ground truth file exists
    if [ ! -f "${GROUNDTRUTH[$dataset_size]}" ]; then
        echo "ERROR: Ground truth file not found: ${GROUNDTRUTH[$dataset_size]}"
        return 1
    fi
    
    # Additional checks for 100M dataset
    if [ "$dataset_size" = "100M" ]; then
        # Check available memory (need at least 200GB for 100M)
        local available_mem_gb=$(free -g | awk '/^Mem:/{print $7}')
        if [ "$available_mem_gb" -lt 180 ]; then
            echo "ERROR: Insufficient memory for 100M dataset. Need 180GB+, have ${available_mem_gb}GB"
            free -h
            return 1
        fi
        
        # Check available disk space (need at least 300GB for 100M indices)
        local available_disk_gb=$(df -BG . | tail -1 | awk '{print $4}' | sed 's/G//')
        if [ "$available_disk_gb" -lt 300 ]; then
            echo "ERROR: Insufficient disk space for 100M dataset. Need 300GB+, have ${available_disk_gb}GB"
            df -h .
            return 1
        fi
        
        # Check GPU memory (need at least 20GB free)
        local gpu_free_mb=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)
        local gpu_free_gb=$((gpu_free_mb / 1024))
        if [ "$gpu_free_gb" -lt 20 ]; then
            echo "ERROR: Insufficient GPU memory for 100M dataset. Need 20GB+, have ${gpu_free_gb}GB"
            nvidia-smi
            return 1
        fi
    fi
    
    echo "✓ Prerequisites met for ${dataset_size}"
    return 0
}

# CAGRA job file creation optimized for 100M scale
create_cagra_job_file() {
    local dataset_size=$1
    local flush_freq=$2
    local cagra_degree=$3
    local threads=$4
    local cuvs_threads=$5
    local intermediate_degree=$((cagra_degree * 2))
    local job_file="job_cagra_${dataset_size}_f${flush_freq}_${cagra_degree}_${threads}_${cuvs_threads}.json"
    
    # Aggressive memory optimizations for 100M vectors
    local load_in_memory="false"  # Never load 100M vectors in memory
    local cagra_search_width=32   # Reduced search width for memory efficiency
    local cagra_itopk=64          # Reduced intermediate topK for memory efficiency
    
    # Adjust for smaller datasets
    if [ "$dataset_size" = "openai_4M" ]; then
        cagra_search_width=64
        cagra_itopk=128
    fi
    
    cat > $job_file << EOF
{
  "benchmarkID" : "ANN_${dataset_size}_F${flush_freq}_CAGRA_HNSW_${cagra_degree}_T${threads}_C${cuvs_threads}",
  "datasetFile" : "${DATASETS[$dataset_size]}",
  "indexOfVector" : 3,
  "vectorColName" : "article_vector",
  "numDocs" : ${NUM_DOCS[$dataset_size]},
  "vectorDimension" : ${VECTOR_DIMENSIONS[$dataset_size]},
  "queryFile" : "${QUERIES[$dataset_size]}",
  "numQueriesToRun" : 1000,
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
  "groundTruthFile" : "${GROUNDTRUTH[$dataset_size]}",
  "cuvsIndexDirPath" : "cuvsIndex",
  "hnswIndexDirPath" : "hnswIndex",
  "hnswMaxConn" : 16,
  "hnswBeamWidth" : 64,
  "cagraIntermediateGraphDegree" : ${intermediate_degree},
  "cagraGraphDegree" : ${cagra_degree},
  "cagraITopK" : ${cagra_itopk},
  "cagraSearchWidth" : ${cagra_search_width},
  "cagraHnswLayers" : 1,
  "loadVectorsInMemory": ${load_in_memory}
}
EOF
    echo $job_file
}

# Lucene job file creation optimized for 100M scale
create_lucene_job_file() {
    local dataset_size=$1
    local flush_freq=$2
    local m_value=$3
    local ef_value=$4
    local job_file="job_lucene_${dataset_size}_f${flush_freq}_${m_value}_${ef_value}.json"
    
    # Memory optimizations for large datasets
    local load_in_memory="false"
    
    cat > $job_file << EOF
{
  "benchmarkID" : "ANN_${dataset_size}_F${flush_freq}_LUCENE_HNSW_M${m_value}_EF${ef_value}",
  "datasetFile" : "${DATASETS[$dataset_size]}",
  "indexOfVector" : 3,
  "vectorColName" : "article_vector",
  "numDocs" : ${NUM_DOCS[$dataset_size]},
  "vectorDimension" : ${VECTOR_DIMENSIONS[$dataset_size]},
  "queryFile" : "${QUERIES[$dataset_size]}",
  "numQueriesToRun" : 1000,
  "flushFreq" : ${flush_freq},
  "topK" : 100,
  "numIndexThreads" : 1,
  "cuvsWriterThreads" : 8,
  "queryThreads" : 1,
  "createIndexInMemory" : false,
  "cleanIndexDirectory" : true,
  "saveResultsOnDisk" : false,
  "hasColNames" : true,
  "algoToRun" : "LUCENE_HNSW",
  "groundTruthFile" : "${GROUNDTRUTH[$dataset_size]}",
  "cuvsIndexDirPath" : "cuvsIndex",
  "hnswIndexDirPath" : "hnswIndex",
  "hnswMaxConn" : ${m_value},
  "hnswBeamWidth" : ${ef_value},
  "cagraIntermediateGraphDegree" : 128,
  "cagraGraphDegree" : 64,
  "cagraITopK" : 256,
  "cagraSearchWidth" : 128,
  "cagraHnswLayers" : 1,
  "loadVectorsInMemory": ${load_in_memory}
}
EOF
    echo $job_file
}

# Calculate total experiments with 100M focus
total_experiments=0
current_experiment=0

for dataset_size in "100M" "openai_4M"; do
    cagra_experiments=$((${#flush_frequencies[@]} * ${#cagra_degrees[@]} * ${#indexing_threads[@]} * ${#cuvs_writer_threads[@]}))
    
    # Parse Lucene parameters for this dataset
    IFS=' ' read -ra m_values <<< "${LUCENE_M_VALUES[$dataset_size]}"
    IFS=' ' read -ra ef_values <<< "${LUCENE_EF_VALUES[$dataset_size]}"
    lucene_experiments=$((${#flush_frequencies[@]} * ${#m_values[@]} * ${#ef_values[@]}))
    
    total_experiments=$((total_experiments + cagra_experiments + lucene_experiments))
done

echo "=================================================================="
echo "SIFT-100M + OpenAI FLUSH FREQUENCY BENCHMARK"
echo "=================================================================="
echo "Datasets: SIFT-100M (128D×100M), OpenAI-4.6M (1536D×4.6M)"
echo "Algorithms: CAGRA-HNSW, Lucene HNSW"
echo "Flush Frequencies: ${flush_frequencies[@]} (500K vs 2M comparison)"
echo "Focus: Large-scale performance with memory optimization"
echo "Total experiments: $total_experiments"
echo "Results will be saved to: $results_file"
echo "=================================================================="
echo ""

# System information
echo "System Information:"
echo "=================="
echo "Memory:"
free -h
echo ""
echo "GPU:"
nvidia-smi --query-gpu=name,memory.total,memory.free,utilization.gpu --format=csv,noheader,nounits
echo ""
echo "Disk Space:"
df -h . | head -2
echo ""

# Main benchmark loop
for dataset_size in "100M" "openai_4M"; do
    
    echo ""
    echo "###################################################################"
    echo "### STARTING BENCHMARKS FOR ${dataset_size} DATASET ###"  
    echo "### (${NUM_DOCS[$dataset_size]} vectors × ${VECTOR_DIMENSIONS[$dataset_size]}D) ###"
    echo "###################################################################"
    echo ""
    
    # Check prerequisites for this dataset
    if ! check_dataset_prerequisites "$dataset_size"; then
        echo "Skipping ${dataset_size} due to missing prerequisites"
        continue
    fi
    
    # Parse Lucene parameters for this dataset
    IFS=' ' read -ra m_values <<< "${LUCENE_M_VALUES[$dataset_size]}"
    IFS=' ' read -ra ef_values <<< "${LUCENE_EF_VALUES[$dataset_size]}"
    
    cagra_experiments=$((${#flush_frequencies[@]} * ${#cagra_degrees[@]} * ${#indexing_threads[@]} * ${#cuvs_writer_threads[@]}))
    lucene_experiments=$((${#flush_frequencies[@]} * ${#m_values[@]} * ${#ef_values[@]}))
    dataset_experiments=$((cagra_experiments + lucene_experiments))
    
    echo "Dataset: ${dataset_size} (${NUM_DOCS[$dataset_size]} vectors × ${VECTOR_DIMENSIONS[$dataset_size]}D)"
    echo "Timeout per experiment: ${TIMEOUTS[$dataset_size]}"
    echo "Flush frequencies to test: ${flush_frequencies[@]}"
    echo "CAGRA-HNSW experiments: $cagra_experiments"
    echo "Lucene HNSW experiments: $lucene_experiments"
    echo "Total for this dataset: $dataset_experiments"
    echo ""
    
    # Run CAGRA-HNSW experiments with flush frequency variations
    echo "========== STARTING CAGRA-HNSW EXPERIMENTS (${dataset_size}) =========="
    
    for flush_freq in "${flush_frequencies[@]}"; do
        echo "--- Testing Flush Frequency: $flush_freq ---"
        for cagra_degree in "${cagra_degrees[@]}"; do
            for threads in "${indexing_threads[@]}"; do
                for cuvs_threads in "${cuvs_writer_threads[@]}"; do
                    current_experiment=$((current_experiment + 1))
                    intermediate_degree=$((cagra_degree * 2))
                    
                    echo "======================================================================"
                    echo "=== CAGRA-HNSW Experiment $current_experiment/$total_experiments ==="
                    echo "=== Dataset: ${dataset_size} | FlushFreq: $flush_freq ==="
                    echo "======================================================================"
                    echo "Parameters: FlushFreq=$flush_freq, CagraDegree=$cagra_degree (IntDegree=$intermediate_degree), IndexThreads=$threads, CuvsWriterThreads=$cuvs_threads"
                    echo "Started at: $(date)"
                    
                    # Monitor system before experiment
                    echo "Pre-experiment system status:"
                    free -h | head -2
                    nvidia-smi --query-gpu=memory.used,memory.free --format=csv,noheader,nounits | head -1
                    
                    # Aggressive cleanup before each experiment
                    cleanup_resources
                    
                    # Create job file
                    job_file=$(create_cagra_job_file "$dataset_size" $flush_freq $cagra_degree $threads $cuvs_threads)
                    benchmark_id="ANN_${dataset_size}_F${flush_freq}_CAGRA_HNSW_${cagra_degree}_T${threads}_C${cuvs_threads}"
                    
                    echo "Job file: $job_file"
                    echo "Running: mvn exec:java -Dexec.mainClass=\"com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks\" -Dexec.args=\"$job_file\""
                    
                    # Run with timeout and memory monitoring
                    start_mem=$(free -m | awk '/^Mem:/{print $3}')
                    if output=$(timeout ${TIMEOUTS[$dataset_size]} mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" -Dexec.args="$job_file" 2>&1); then
                        end_mem=$(free -m | awk '/^Mem:/{print $3}')
                        peak_mem_gb=$(( (end_mem > start_mem ? end_mem : start_mem) / 1024 ))
                        
                        metrics=$(extract_metrics "$output")
                        indexing_time=$(echo "$metrics" | cut -d',' -f1)
                        query_time=$(echo "$metrics" | cut -d',' -f2)
                        recall_accuracy=$(echo "$metrics" | cut -d',' -f3)
                        error_details=$(echo "$metrics" | cut -d',' -f4)
                        
                        if [ "$indexing_time" != "ERROR" ] && [ "$indexing_time" != "OOM" ] && [ -n "$indexing_time" ]; then
                            echo "✓ SUCCESS: IndexingTime=${indexing_time}ms, QueryTime=${query_time}ms, Recall=${recall_accuracy}%, PeakMemory=${peak_mem_gb}GB"
                            echo "CAGRA_HNSW,${dataset_size},${VECTOR_DIMENSIONS[$dataset_size]},${NUM_DOCS[$dataset_size]},$flush_freq,$cagra_degree,$intermediate_degree,$threads,$cuvs_threads,N/A,N/A,$indexing_time,$query_time,$recall_accuracy,$benchmark_id,$error_details" >> $results_file
                        else
                            echo "✗ FAILED: $error_details (PeakMemory=${peak_mem_gb}GB)"
                            echo "CAGRA_HNSW,${dataset_size},${VECTOR_DIMENSIONS[$dataset_size]},${NUM_DOCS[$dataset_size]},$flush_freq,$cagra_degree,$intermediate_degree,$threads,$cuvs_threads,N/A,N/A,$indexing_time,$query_time,$recall_accuracy,$benchmark_id,$error_details" >> $results_file
                            
                            # Save detailed error log
                            echo "=== ERROR LOG for $benchmark_id ===" >> error_log_${benchmark_id}_100M.txt
                            echo "Start Memory: ${start_mem}MB" >> error_log_${benchmark_id}_100M.txt
                            echo "End Memory: ${end_mem}MB" >> error_log_${benchmark_id}_100M.txt
                            echo "Peak Memory: ${peak_mem_gb}GB" >> error_log_${benchmark_id}_100M.txt
                            echo "$output" >> error_log_${benchmark_id}_100M.txt
                            echo "=== END ERROR LOG ===" >> error_log_${benchmark_id}_100M.txt
                        fi
                    else
                        echo "✗ TIMEOUT: Experiment timed out after ${TIMEOUTS[$dataset_size]}"
                        end_mem=$(free -m | awk '/^Mem:/{print $3}')
                        peak_mem_gb=$(( (end_mem > start_mem ? end_mem : start_mem) / 1024 ))
                        echo "CAGRA_HNSW,${dataset_size},${VECTOR_DIMENSIONS[$dataset_size]},${NUM_DOCS[$dataset_size]},$flush_freq,$cagra_degree,$intermediate_degree,$threads,$cuvs_threads,N/A,N/A,TIMEOUT,TIMEOUT,TIMEOUT,$benchmark_id,TIMEOUT" >> $results_file
                    fi
                    
                    rm -f $job_file
                    echo "Completed at: $(date)"
                    echo ""
                done
            done
        done
    done
    
    # Run Lucene HNSW experiments with flush frequency variations
    echo "========== STARTING LUCENE HNSW EXPERIMENTS (${dataset_size}) =========="
    
    for flush_freq in "${flush_frequencies[@]}"; do
        echo "--- Testing Flush Frequency: $flush_freq ---"
        for m_value in "${m_values[@]}"; do
            for ef_value in "${ef_values[@]}"; do
                current_experiment=$((current_experiment + 1))
                
                echo "======================================================================"
                echo "=== Lucene HNSW Experiment $current_experiment/$total_experiments ==="
                echo "=== Dataset: ${dataset_size} | FlushFreq: $flush_freq ==="
                echo "======================================================================"
                echo "Parameters: FlushFreq=$flush_freq, M=$m_value, EF=$ef_value"
                echo "Started at: $(date)"
                
                # Monitor system before experiment
                echo "Pre-experiment system status:"
                free -h | head -2
                
                cleanup_resources
                
                job_file=$(create_lucene_job_file "$dataset_size" $flush_freq $m_value $ef_value)
                benchmark_id="ANN_${dataset_size}_F${flush_freq}_LUCENE_HNSW_M${m_value}_EF${ef_value}"
                
                echo "Job file: $job_file"
                echo "Running: mvn exec:java -Dexec.mainClass=\"com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks\" -Dexec.args=\"$job_file\""
                
                start_mem=$(free -m | awk '/^Mem:/{print $3}')
                if output=$(timeout ${TIMEOUTS[$dataset_size]} mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" -Dexec.args="$job_file" 2>&1); then
                    end_mem=$(free -m | awk '/^Mem:/{print $3}')
                    peak_mem_gb=$(( (end_mem > start_mem ? end_mem : start_mem) / 1024 ))
                    
                    metrics=$(extract_metrics "$output")
                    indexing_time=$(echo "$metrics" | cut -d',' -f1)
                    query_time=$(echo "$metrics" | cut -d',' -f2)
                    recall_accuracy=$(echo "$metrics" | cut -d',' -f3)
                    error_details=$(echo "$metrics" | cut -d',' -f4)
                    
                    if [ "$indexing_time" != "ERROR" ] && [ "$indexing_time" != "OOM" ] && [ -n "$indexing_time" ]; then
                        echo "✓ SUCCESS: IndexingTime=${indexing_time}ms, QueryTime=${query_time}ms, Recall=${recall_accuracy}%, PeakMemory=${peak_mem_gb}GB"
                        echo "LUCENE_HNSW,${dataset_size},${VECTOR_DIMENSIONS[$dataset_size]},${NUM_DOCS[$dataset_size]},$flush_freq,N/A,N/A,N/A,N/A,$m_value,$ef_value,$indexing_time,$query_time,$recall_accuracy,$benchmark_id,$error_details" >> $results_file
                    else
                        echo "✗ FAILED: $error_details (PeakMemory=${peak_mem_gb}GB)"
                        echo "LUCENE_HNSW,${dataset_size},${VECTOR_DIMENSIONS[$dataset_size]},${NUM_DOCS[$dataset_size]},$flush_freq,N/A,N/A,N/A,N/A,$m_value,$ef_value,$indexing_time,$query_time,$recall_accuracy,$benchmark_id,$error_details" >> $results_file
                        
                        echo "=== ERROR LOG for $benchmark_id ===" >> error_log_${benchmark_id}_100M.txt
                        echo "Start Memory: ${start_mem}MB" >> error_log_${benchmark_id}_100M.txt
                        echo "End Memory: ${end_mem}MB" >> error_log_${benchmark_id}_100M.txt
                        echo "Peak Memory: ${peak_mem_gb}GB" >> error_log_${benchmark_id}_100M.txt
                        echo "$output" >> error_log_${benchmark_id}_100M.txt
                        echo "=== END ERROR LOG ===" >> error_log_${benchmark_id}_100M.txt
                    fi
                else
                    echo "✗ TIMEOUT: Experiment timed out after ${TIMEOUTS[$dataset_size]}"
                    end_mem=$(free -m | awk '/^Mem:/{print $3}')
                    peak_mem_gb=$(( (end_mem > start_mem ? end_mem : start_mem) / 1024 ))
                    echo "LUCENE_HNSW,${dataset_size},${VECTOR_DIMENSIONS[$dataset_size]},${NUM_DOCS[$dataset_size]},$flush_freq,N/A,N/A,N/A,N/A,$m_value,$ef_value,TIMEOUT,TIMEOUT,TIMEOUT,$benchmark_id,TIMEOUT" >> $results_file
                fi
                
                rm -f $job_file
                echo "Completed at: $(date)"
                echo ""
            done
        done
    done
    
    echo ""
    echo "###################################################################"
    echo "### COMPLETED BENCHMARKS FOR ${dataset_size} DATASET ###"
    echo "###################################################################"
    echo ""
done

echo "========================================="
echo "ALL 100M FLUSH FREQUENCY EXPERIMENTS COMPLETED!"
echo "========================================="
echo "Results saved to: $results_file"
echo "Error logs saved to: error_log_*_100M.txt files"

# Final comprehensive summary
echo ""
echo "Final Results Summary (100M + OpenAI Scale):"
echo "============================================="
total_successful=$(grep -v "ERROR\|TIMEOUT\|OOM" $results_file | tail -n +2 | wc -l)
total_failed=$(grep -E "ERROR|TIMEOUT|OOM" $results_file | wc -l)

echo "✓ Successful runs: $total_successful/$total_experiments"
echo "✗ Failed runs: $total_failed/$total_experiments"
echo ""

# Per-dataset and per-flush-frequency summary
for dataset_size in "100M" "openai_4M"; do
    echo "${dataset_size} Results:"
    dataset_successful=$(grep ",$dataset_size," $results_file | grep -v "ERROR\|TIMEOUT\|OOM" | wc -l)
    dataset_total=$(grep ",$dataset_size," $results_file | wc -l)
    echo "  Successful: $dataset_successful/$dataset_total"
    
    echo "  Flush Frequency Breakdown:"
    for flush_freq in "${flush_frequencies[@]}"; do
        flush_successful=$(grep ",$dataset_size,.*,$flush_freq," $results_file | grep -v "ERROR\|TIMEOUT\|OOM" | wc -l)
        flush_total=$(grep ",$dataset_size,.*,$flush_freq," $results_file | wc -l)
        echo "    - FlushFreq $flush_freq: $flush_successful/$flush_total"
    done
    
    cagra_successful=$(grep "CAGRA_HNSW,$dataset_size," $results_file | grep -v "ERROR\|TIMEOUT\|OOM" | wc -l)
    cagra_total=$(grep "CAGRA_HNSW,$dataset_size," $results_file | wc -l)
    echo "    - CAGRA-HNSW: $cagra_successful/$cagra_total"
    
    lucene_successful=$(grep "LUCENE_HNSW,$dataset_size," $results_file | grep -v "ERROR\|TIMEOUT\|OOM" | wc -l)
    lucene_total=$(grep "LUCENE_HNSW,$dataset_size," $results_file | wc -l)
    echo "    - Lucene HNSW: $lucene_successful/$lucene_total"
done

echo ""
echo "========== RAW RESULTS TABLE =========="
cat $results_file

