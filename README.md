# Vector Search Benchmarks

Benchmark system for comparing CAGRA (GPU) vs Lucene HNSW (CPU) vector search algorithms.

## Setup

1. **Prerequisites:**
   - JDK 22+
   - CUDA libraries
   - Python 3.7+

2. **Set library paths:**
   ```bash
   export LD_LIBRARY_PATH="/path/to/cuvs/build:/path/to/cuda/lib64:/path/to/conda/lib:$LD_LIBRARY_PATH"
   ```

3. **Run benchmark:**
   ```bash
   ./run_sweep.sh configs/sweep_templates/multi-dataset-sweep.yaml
   ```

4. **View results:**
   ```bash
   cd web-ui-new && python3 unified-server.py 8000
   # Open http://localhost:8000
   ```

## Adding Datasets

Edit `datasets/datasets.yaml`:

**If you already have the dataset files, specify the paths (set avaliable = true for benchmarks to run on the dataset):**
```yaml
datasets:
  my-dataset:
    name: "My Dataset"
    base_file: "/path/to/base.fvecs"
    query_file: "/path/to/query.fvecs"
    ground_truth_file: "/path/to/groundtruth.ivecs"
    num_docs: 1000000
    vector_dimension: 128
    top_k_ground_truth: 1000
    available: true
```

**If files are not found, the system will auto-download (add these fields):**
```yaml
    download_url: "https://example.com/dataset.tar"
    extract_commands: "tar -xf dataset.tar"
    target_directory: "datasets/my-dataset"
    expected_base_file: "datasets/my-dataset/base.fvecs"
    expected_query_file: "datasets/my-dataset/query.fvecs"
    expected_ground_truth_file: "datasets/my-dataset/groundtruth.ivecs"
```

**Note:** If the files exist at the specified paths, no download will occur.

## Creating Sweeps

Create `configs/sweep_templates/my-sweep.yaml`:

```yaml
datasets:
  - my-dataset

common_params:
  numQueriesToRun: 1000
  topK: 100
  efSearch: 150

algorithms:
  CAGRA_HNSW:
    cagraGraphDegree: [32, 64]
    cagraIntermediateGraphDegree: [64, 128]
    
  LUCENE_HNSW:
    hnswMaxConn: [16, 32]
    hnswBeamWidth: [100, 200]
```

## Results

Results are saved in `results/raw/{sweep-id}/`:
- Individual run directories with logs and metrics
- Consolidated CSV file with all runs
- Web UI for interactive analysis


# Access via http://localhost:8000 locally
```