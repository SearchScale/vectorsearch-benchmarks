#!/bin/bash

# An end-to-end script to benchmark Solr's indexing performance

# Get dataset if not already existing
nvidia-smi

DATA_DIR="data"
if [ ! -d "$DATA_DIR" ]; then
  mkdir $DATA_DIR
fi
DATASET_FILENAME="wiki_all_10M.tar"
DATASET_ENDPOINT="https://data.rapids.ai/raft/datasets/wiki_all_10M/$DATASET_FILENAME"
if [ ! -f "$DATA_DIR/$DATASET_FILENAME" ]; then
  echo "==> File '$DATASET_FILENAME' does not exist in the $DATA_DIR dir, downloading..."
  cd $DATA_DIR
  axel $DATASET_ENDPOINT
  tar -xvf $DATASET_FILENAME
  cd ..
fi

# Get Javabin files generator if not already existing and build it
JFG_DIR="solr-javabin-generator"
JFG_GITHUB_URL="https://github.com/SearchScale/solr-javabin-generator.git"

if [ ! -d "$JFG_DIR" ]; then
  echo "repo '$JFG_DIR' does not exist."
  git clone $JFG_GITHUB_URL $JFG_DIR
  # build
  cd $JFG_DIR
  mvn clean package
  cd ..
fi

# Get Solr's PR branch containing the cuvs module if not already existing
SOLR_DIR="solr"
SOLR_GITHUB_REPO="https://github.com/SearchScale/solr.git"
SOLR_CUVS_MODULE_BRANCH="searchscale/SOLR-17892-add-cuvs-lucene-support"

# Download and extract and build Solr
SOLR_ROOT=solr-10.0.0-SNAPSHOT
if [ ! -d "$SOLR_DIR" ]; then
  echo "repo '$SOLR_DIR' does not exist."
  git clone $SOLR_GITHUB_REPO $SOLR_DIR
  # build
  cd $SOLR_DIR
  git checkout $SOLR_CUVS_MODULE_BRANCH
  ./gradlew clean distTar
  mv solr/packaging/build/distributions/$SOLR_ROOT.tgz ../
  cd ..
fi

rm -rf $SOLR_ROOT
tar -xf $SOLR_ROOT.tgz

# Load cuvs module, start Solr
cd $SOLR_ROOT
cp modules/cuvs/lib/*.jar server/solr-webapp/webapp/WEB-INF/lib/
bin/solr start -m 29G
cd ..

SOLR_URL="http://localhost:8983"
# Create collection
(cd cagra-hnsw && zip -r - *) | curl -X POST --header "Content-Type:application/octet-stream" --data-binary @- "$SOLR_URL/solr/admin/configs?action=UPLOAD&name=cuvs"
curl "$SOLR_URL/solr/admin/collections?action=CREATE&name=test&numShards=1&collection.configName=cuvs"

start_time=$(date +%s%N) # Record start time in nanoseconds

# Use the javabin file generator to generate javabin files
JAVABIN_FILES_DIR="wiki_batches"
DOCS_COUNT=10000000
BATCH_SIZE=500000
if [ ! -d "$JAVABIN_FILES_DIR" ]; then
  time java -jar $JFG_DIR/target/javabin-generator-1.0-SNAPSHOT-jar-with-dependencies.jar data_file=$DATA_DIR/base.10M.fbin output_dir=$JAVABIN_FILES_DIR batch_size=$BATCH_SIZE docs_count=$DOCS_COUNT threads=all
fi

URL="$SOLR_URL/solr/test/update?commit=true&overwrite=false"
# Loop through each file in the directory and post it in the background
for FILE in "$JAVABIN_FILES_DIR"/*; do
    if [ -f "$FILE" ]; then  # Check if it's a file
        echo "Uploading $FILE..."
        time http --ignore-stdin POST "$URL" Content-Type:application/javabin @"$FILE" &
    fi
done

# Wait for all background processes to finish
wait
end_time=$(date +%s%N)   # Record end time in nanoseconds

duration=$(( (end_time - start_time) / 1000000 )) # Calculate duration in milliseconds
echo "Execution time: $duration ms"
echo "Done!"

# Cleanup
rm -rf $JAVABIN_FILES_DIR

cd $SOLR_ROOT
bin/solr stop
rm -rf $SOLR_ROOT

wait
ps -ef | grep solr
