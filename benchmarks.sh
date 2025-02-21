mvn clean package

java -cp $HOME/.m2/repository/com/nvidia/cuvs/cuvs-java/25.02.0/cuvs-java-25.02.0.jar:target/vectorsearch-benchmarks-1.0-jar-with-dependencies.jar com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks jobs.json
