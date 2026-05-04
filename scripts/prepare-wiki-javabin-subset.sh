#!/usr/bin/env bash
# Build javabin batches from datasets/wiki-10m/base.10M.fbin for a doc subset (e.g. 1M or 2M).
# Usage: ./prepare-wiki-javabin-subset.sh <docs_count> <output_dir> [batch_size]
# Example: ./prepare-wiki-javabin-subset.sh 2000000 wiki-2m_batches 500000
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DOCS="${1:?docs_count}"
OUT="${2:?output_dir}"
BATCH="${3:-500000}"

JAR="$ROOT/solr-javabin-generator/target/javabin-generator-1.0-SNAPSHOT-jar-with-dependencies.jar"
FBIN="$ROOT/datasets/wiki-10m/base.10M.fbin"

[[ -f "$JAR" ]] || { echo "Missing $JAR — (cd solr-javabin-generator && mvn -q package)"; exit 1; }
[[ -f "$FBIN" ]] || { echo "Missing $FBIN"; exit 1; }

rm -rf "$OUT"
start=$(date +%s%N)
java -jar "$JAR" \
  "data_file=$FBIN" \
  "output_dir=$ROOT/$OUT" \
  "batch_size=$BATCH" \
  "docs_count=$DOCS" \
  threads=all
end=$(date +%s%N)
ms=$(( (end - start) / 1000000 ))
echo "$ms" >"${OUT}_preparation_time.txt"
echo "Wrote $OUT ($(ls -1 "$OUT" | wc -l) files) and ${OUT}_preparation_time.txt (${ms} ms)"
