#!/usr/bin/env bash
# Build Solr javabin batches from datasets/wiki-10m/base.10M.fbin (10M vectors).
# Run from the vectorsearch-benchmarks repo root.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAR="$ROOT/solr-javabin-generator/target/javabin-generator-1.0-SNAPSHOT-jar-with-dependencies.jar"
FBIN="$ROOT/datasets/wiki-10m/base.10M.fbin"
OUT="${1:-wiki-10m_from_dataset_batches}"
BATCH_SIZE="${BATCH_SIZE:-500000}"
DOCS_COUNT="${DOCS_COUNT:-10000000}"

if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR — build with: (cd solr-javabin-generator && mvn -q package)"
  exit 1
fi
if [[ ! -f "$FBIN" ]]; then
  echo "Missing $FBIN"
  exit 1
fi

rm -rf "$OUT"
start=$(date +%s%N)
java -jar "$JAR" \
  "data_file=$FBIN" \
  "output_dir=$ROOT/$OUT" \
  "batch_size=$BATCH_SIZE" \
  "docs_count=$DOCS_COUNT" \
  threads=all
end=$(date +%s%N)
ms=$(( (end - start) / 1000000 ))
echo "$ms" > "${OUT}_preparation_time.txt"
echo "Wrote ${OUT}_preparation_time.txt ($ms ms). Use batches dir: $OUT"
