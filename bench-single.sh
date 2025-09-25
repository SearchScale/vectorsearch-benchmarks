#!/bin/bash

mvn clean compile

# Use Maven to run with proper classpath instead of fat JAR to handle multi-release JARs correctly
PASSED_ARGUMENT=$1
BENCHMARK_ID=$2  # Optional benchmark ID

if   [ -d "${PASSED_ARGUMENT}" ]
then
    PASSED_ARGUMENT=$(realpath -s $PASSED_ARGUMENT)
    echo "${PASSED_ARGUMENT} is a directory.";
    for config_file in "$PASSED_ARGUMENT"/*; do
        echo "####################### Now running: $config_file #######################"
        if [ -n "$BENCHMARK_ID" ]; then
            mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
            -Dexec.args="$config_file $BENCHMARK_ID" \
            -Dexec.jvmArgs="-Xmx192G --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED"
        else
            mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
            -Dexec.args="$config_file" \
            -Dexec.jvmArgs="-Xmx192G --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED"
        fi
    done
elif [ -f "${PASSED_ARGUMENT}" ]
then
    echo "${PASSED_ARGUMENT} is a file. Running now.";
    if [ -n "$BENCHMARK_ID" ]; then
        mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
        -Dexec.args="$PASSED_ARGUMENT $BENCHMARK_ID" \
        -Dexec.jvmArgs="-Xmx192G --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED"
    else
        mvn exec:java -Dexec.mainClass="com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks" \
        -Dexec.args="$PASSED_ARGUMENT" \
        -Dexec.jvmArgs="-Xmx192G --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED"
    fi
fi
