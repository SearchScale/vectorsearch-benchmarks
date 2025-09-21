# Vector Search Benchmarks

Benchmark system for comparing CAGRA (GPU) vs Lucene HNSW (CPU) vector search algorithms.

## Setup

1. **Prerequisites:**
   - JDK 22+
   - CUDA libraries
   - Python 3.7+
   - pip install pyyaml

2. **Set library paths:**
   ```bash
   export LD_LIBRARY_PATH="/path/to/cuvs/build:/path/to/cuda/lib64:/path/to/conda/lib:$LD_LIBRARY_PATH"
   ```

3. **Run benchmark:**

    ./run_sweep.sh --data-dir /data2/vsbench-datasets --datasets datasets.json --sweeps sweeps.json --configs-dir configs --results-dir results --run-benchmarks

## Adding Datasets

Edit `datasets.json`:

## Creating Sweeps

Edit (or copy+edit) `sweep.json`:

