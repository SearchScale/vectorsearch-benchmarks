# Vectorsearch Benchmarks

## Prerequisites
- [CuVS libraries](https://docs.rapids.ai/api/cuvs/stable/build/#build-from-source)
- [maven 3.9.6 or above](https://maven.apache.org/download.cgi)
- [JDK 22](https://openjdk.org/projects/jdk/22/)

## Before running

### Build libcuvs libraries and CuVS Java API
```
git clone git@github.com:rapidsai/cuvs.git \
&& cd cuvs \
&& git checkout branch-25.02 \
&& ./build.sh libcuvs java
```
### Build Lucene-CuVS
```
git clone git@github.com:SearchScale/lucene.git \
&& cd lucene \
&& git checkout cuvs-integration-main \
&& ./gradlew compileJava mavenToLocal
```

### Download the Wikipedia Dataset (5M vectors x 2048 dimensions), queries (100 x 2048 dimensions), and groundtruth (100 x 64 topk)
```
wget https://accounts.searchscale.com/datasets/wikipedia/queries_100.csv \
&& wget https://accounts.searchscale.com/datasets/wikipedia/ground_truth_100x64.csv \
&& wget https://accounts.searchscale.com/datasets/wikipedia/wiki_dump_5Mx2048D.csv.gz
```

## Running Manually
Steps:
- Add your benchmark job configuration in the `jobs.json` file
- do `./benchmarks.sh`
- If `saveResultsOnDisk` is set as `true` (in `jobs.json`) then you can find your benchmark results in the `results` folder. For each successful benchmark run, two files are created `${benchmark_id}__benchmark_results_${timestamp}.json` and `${benchmark_id}__neighbors_${timestamp}.csv`
