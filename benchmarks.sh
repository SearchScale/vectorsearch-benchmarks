mvn clean package

CUVS_VERSION=25.02.0
CUVS_JAVA_JAR=$HOME/.m2/repository/com/nvidia/cuvs/cuvs-java/$CUVS_VERSION/cuvs-java-$CUVS_VERSION.jar
BENCHMARK_JAR=target/vectorsearch-benchmarks-1.0-jar-with-dependencies.jar

"$JAVA_HOME"/bin/java -cp "$CUVS_JAVA_JAR":"$BENCHMARK_JAR" \
--add-modules=jdk.incubator.vector \
--enable-native-access=ALL-UNNAMED \
com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks \
$1 # the jobs.json file to use
