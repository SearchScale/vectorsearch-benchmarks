mvn clean package

CUVS_JAVA_JAR=$HOME/.m2/repository/com/nvidia/cuvs/cuvs-java/25.02.0/cuvs-java-25.02.0.jar
BENCHMARK_JAR=target/vectorsearch-benchmarks-1.0-jar-with-dependencies.jar

"$JAVA_HOME"/bin/java -cp "$CUVS_JAVA_JAR":"$BENCHMARK_JAR" \
--add-modules=jdk.incubator.vector \
--enable-native-access=ALL-UNNAMED \
com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks \
jobs.json
