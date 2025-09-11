package com.searchscale.lucene.cuvs.benchmarks;

public class BenchmarkConfiguration {

  public String benchmarkID;
  public String datasetFile;
  public int indexOfVector;
  public String vectorColName;
  public int numDocs;
  public int vectorDimension;
  public String queryFile;
  public int numQueriesToRun;
  public int numWarmUpQueries;
  public int flushFreq;
  public int topK;
  public int numIndexThreads;
  public int cuvsWriterThreads;
  public int queryThreads;
  public boolean createIndexInMemory;
  public boolean cleanIndexDirectory;
  public boolean saveResultsOnDisk;
  public String resultsDirectory;
  public boolean hasColNames;
  public String algoToRun;              // keep as String
  public String groundTruthFile;
  public String cuvsIndexDirPath;
  public String hnswIndexDirPath;
  public boolean loadVectorsInMemory;
  public boolean skipIndexing;

  // Lucene HNSW parameters
  public int hnswMaxConn;               // 16 default (max 512)
  public int hnswBeamWidth;             // 100 default (max 3200)

  // CAGRA parameters
  public int cagraIntermediateGraphDegree; // 128 default
  public int cagraGraphDegree;             // 64 default
  public int cagraITopK;
  public int cagraSearchWidth;
  public int cagraHnswLayers;             // layers in CAGRA->HNSW conversion
  public int efSearch;

  private boolean isLucene() {
    return "LUCENE_HNSW".equalsIgnoreCase(algoToRun);
  }
  private boolean isCagra() {
    return "CAGRA_HNSW".equalsIgnoreCase(algoToRun);
  }

  public int getEffectiveEfSearch() {
    if (efSearch > 0) {
      return efSearch;
    }
    return Math.max(topK, (int) Math.ceil(topK * 1.5));
  }

  public String prettyString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Benchmark ID: ").append(benchmarkID).append('\n');
    sb.append("Dataset file used is: ").append(datasetFile).append('\n');
    sb.append("Index of vector field is: ").append(indexOfVector).append('\n');
    sb.append("Name of the vector field is: ").append(vectorColName).append('\n');
    sb.append("Number of documents to be indexed are: ").append(numDocs).append('\n');
    sb.append("Number of dimensions are: ").append(vectorDimension).append('\n');
    sb.append("Query file used is: ").append(queryFile).append('\n');
    sb.append("Number of queries to run: ").append(numQueriesToRun).append('\n');
    sb.append("Number of warmup queries: ").append(numWarmUpQueries).append('\n');
    sb.append("Flush frequency (every n documents): ").append(flushFreq).append('\n');
    sb.append("TopK value is: ").append(topK).append('\n');
    sb.append("numIndexThreads is: ").append(numIndexThreads).append('\n');
    sb.append("Query threads: ").append(queryThreads).append('\n');
    sb.append("Create index in memory: ").append(createIndexInMemory).append('\n');
    sb.append("Clean index directory: ").append(cleanIndexDirectory).append('\n');
    sb.append("Save results on disk: ").append(saveResultsOnDisk).append('\n');
    sb.append("Has column names in the dataset file: ").append(hasColNames).append('\n');
    sb.append("algoToRun {Choices: HNSW | CAGRA}: ").append(algoToRun).append('\n');
    sb.append("Ground Truth file used is: ").append(groundTruthFile).append('\n');
    if (cuvsIndexDirPath != null) sb.append("CuVS index directory path is: ").append(cuvsIndexDirPath).append('\n');
    if (hnswIndexDirPath != null) sb.append("HNSW index directory path is: ").append(hnswIndexDirPath).append('\n');
    sb.append("Load vectors in memory before indexing: ").append(loadVectorsInMemory).append('\n');
    sb.append("Skip indexing (and use existing index for search): ").append(skipIndexing).append('\n');

    sb.append("------- algo parameters ------\n");
    if (isLucene()) {
      sb.append("hnswMaxConn: ").append(hnswMaxConn).append('\n');
      sb.append("hnswBeamWidth: ").append(hnswBeamWidth).append('\n');
    } else if (isCagra()) {
      sb.append("cagraIntermediateGraphDegree: ").append(cagraIntermediateGraphDegree).append('\n');
      sb.append("cagraGraphDegree: ").append(cagraGraphDegree).append('\n');
      sb.append("cuvsWriterThreads: ").append(cuvsWriterThreads).append('\n');
      sb.append("cagraITopK: ").append(cagraITopK).append('\n');
      sb.append("cagraSearchWidth: ").append(cagraSearchWidth).append('\n');
      sb.append("cagraHnswLayers: ").append(cagraHnswLayers).append('\n');
    }
    return sb.toString();
  }

  @Override public String toString() { return prettyString(); }

  public void debugPrintArguments() {
    // keep a single source of truth for printing
    System.out.print(prettyString());
  }
}
