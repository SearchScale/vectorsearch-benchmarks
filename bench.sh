#!/bin/bash

# Vector Search Benchmarks CLI Wrapper
# This script provides a convenient way to run benchmarks without the long java command

JAR_FILE="target/vectorsearch-benchmarks-1.0-jar-with-dependencies.jar"

if [ ! -f "$JAR_FILE" ]; then
    echo "JAR file not found. Building project..."
    mvn clean package -q
    if [ $? -ne 0 ]; then
        echo "Build failed!"
        exit 1
    fi
fi

# Run the benchmark CLI with all arguments passed through
# Use Maven exec:java like benchmarks.sh for proper dependency resolution
# Set up CuVS native library path
export LD_LIBRARY_PATH=/home/puneet/code/cuvs-repo/cuvs/cpp/build:/home/puneet/code/cuvs-repo/cuvs/cpp/build/_deps/rmm-build:/usr/local/cuda-12.5/lib64:/home/puneet/miniforge3/lib:$LD_LIBRARY_PATH
mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.bench.BenchCLI" \
    -Dexec.args="$*" \
    -Dexec.jvmArgs="-Xmx64g --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED"


