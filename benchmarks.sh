#!/bin/bash

mvn clean package

CUVS_VERSION=25.02.0
CUVS_JAVA_JAR=$HOME/.m2/repository/com/nvidia/cuvs/cuvs-java/$CUVS_VERSION/cuvs-java-$CUVS_VERSION.jar
BENCHMARK_JAR=target/vectorsearch-benchmarks-1.0-jar-with-dependencies.jar
PASSED_ARGUMENT=$1

if   [ -d "${PASSED_ARGUMENT}" ]
then
    PASSED_ARGUMENT=$(realpath -s $PASSED_ARGUMENT)
    echo "${PASSED_ARGUMENT} is a directory.";
    for config_file in "$PASSED_ARGUMENT"/*; do
        echo "####################### Now running: $config_file #######################"
        "$JAVA_HOME"/bin/java -cp "$CUVS_JAVA_JAR":"$BENCHMARK_JAR" \
        --add-modules=jdk.incubator.vector \
        --enable-native-access=ALL-UNNAMED \
        com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks \
        $config_file # the job configuration file from the directory picked up for this run
    done
elif [ -f "${PASSED_ARGUMENT}" ]
then
    echo "${PASSED_ARGUMENT} is a file. Running now.";
    "$JAVA_HOME"/bin/java -cp "$CUVS_JAVA_JAR":"$BENCHMARK_JAR" \
    --add-modules=jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED \
    com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks \
    $PASSED_ARGUMENT # the job configuration file to run
fi
