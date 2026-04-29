package com.searchscale.lucene.cuvs.benchmarks;

import static org.apache.lucene.index.VectorSimilarityFunction.EUCLIDEAN;

import com.nvidia.cuvs.CuVSIvfPqIndexParams;
import com.nvidia.cuvs.CuVSIvfPqParams;
import com.nvidia.cuvs.CuVSIvfPqSearchParams;
import com.nvidia.cuvs.lucene.AcceleratedHNSWParams;
import com.nvidia.cuvs.lucene.CuVS2510GPUSearchCodec;
import com.nvidia.cuvs.lucene.GPUKnnFloatVectorQuery;
import com.nvidia.cuvs.lucene.GPUSearchParams;
import com.nvidia.cuvs.lucene.Lucene101AcceleratedHNSWCodec;
import com.nvidia.cuvs.lucene.LuceneAcceleratedHNSWBinaryQuantizedCodec;
import com.nvidia.cuvs.lucene.LuceneAcceleratedHNSWScalarQuantizedCodec;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene101.Lucene101Codec;
import org.apache.lucene.codecs.lucene101.Lucene101Codec.Mode;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.PrintStreamInfoStream;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.IndexTreeList;
import org.mapdb.QueueLong.Node.SERIALIZER;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LuceneCuvsBenchmarks {

  private static final Logger log = LoggerFactory.getLogger(LuceneCuvsBenchmarks.class.getName());
  private static ExecutorService executorService = null;

  public enum Codex {
    LUCENE_HNSW,
    CAGRA_HNSW,
    CAGRA_SEARCH,
    CAGRA_HNSW_BINARY,
    CAGRA_HNSW_SCALAR
  }

  /**
   * Uses reflection to bypass the 2048MB hard limit for per-thread RAM buffer.
   * This is a workaround to allow larger segments to be created before flushing.
   */
  private static void setPerThreadRAMLimit(IndexWriterConfig config, int limitMB) {
    try {
      java.lang.reflect.Field field = null;
      Class<?> clazz = config.getClass();
      while (clazz != null && field == null) {
        try {
          field = clazz.getDeclaredField("perThreadHardLimitMB");
        } catch (NoSuchFieldException e) {
          clazz = clazz.getSuperclass();
        }
      }
      if (field == null) {
        clazz = config.getClass().getSuperclass();
        while (clazz != null && field == null) {
          try {
            field = clazz.getDeclaredField("perThreadHardLimitMB");
          } catch (NoSuchFieldException e) {
            clazz = clazz.getSuperclass();
          }
        }
      }
      if (field != null) {
        field.setAccessible(true);
        field.setInt(config, limitMB);
        log.info("Successfully set perThreadHardLimitMB to {} MB using reflection", limitMB);
      } else {
        log.error("Could not find perThreadHardLimitMB field using reflection");
      }
    } catch (Exception e) {
      log.error("Failed to set per-thread RAM limit using reflection", e);
    }
  }

  // ── Writer-config helpers ──────────────────────────────────────────────────

  /**
   * Builds an {@link IndexWriterConfig} for the flush phase.
   * Uses {@link NoMergePolicy} unless {@code enableTieredMerge} is set,
   * in which case background auto-merges are allowed during indexing.
   * forceMerge is never triggered here — that is the responsibility of
   * the separate merge writer created in {@code main()}.
   */
  private static IndexWriterConfig buildFlushWriterConfig(
      BenchmarkConfiguration config, Codec codec) {
    IndexWriterConfig iwc = new IndexWriterConfig(new StandardAnalyzer());
    iwc.setCodec(codec);
    iwc.setMaxBufferedDocs(config.flushFreq);
    iwc.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);
    if (config.enableTieredMerge) {
      iwc.setMergePolicy(new TieredMergePolicy());
    } else {
      iwc.setMergePolicy(NoMergePolicy.INSTANCE);
    }
    setPerThreadRAMLimit(iwc, 10240); // 10 GB per thread
    if (config.enableIndexWriterInfoStream) {
      iwc.setInfoStream(new PrintStreamInfoStream(System.out));
    }
    log.info(
        "Configured flush writer — MaxBufferedDocs: {}, RAMBufferSizeMB: {}, PerThreadRAMLimit: {}"
            + " MB",
        iwc.getMaxBufferedDocs(),
        iwc.getRAMBufferSizeMB(),
        iwc.getRAMPerThreadHardLimitMB());
    return iwc;
  }

  /**
   * Builds an {@link IndexWriterConfig} for the forceMerge phase.
   * Always uses a {@link TieredMergePolicy} tuned so that the policy ceiling
   * never silently caps a single-segment merge of a large index.
   */
  private static IndexWriterConfig buildMergeWriterConfig(
      BenchmarkConfiguration config, Codec mergeCodec) {
    IndexWriterConfig iwc = new IndexWriterConfig(new StandardAnalyzer());
    iwc.setCodec(mergeCodec);
    TieredMergePolicy tmp = new TieredMergePolicy();
    if (config.forceMerge == 1) {
      // With 10M x 1536-dim vectors the full index is ~60+ GiB.
      // Raise the ceiling above the full index size so the policy never
      // silently caps forceMerge(1) at ~6 segments.
      tmp.setMaxMergedSegmentMB(150 * 1024); // 150 GiB in MB
      tmp.setSegmentsPerTier(2);
      tmp.setMaxMergeAtOnce(20);
    }
    iwc.setMergePolicy(tmp);
    setPerThreadRAMLimit(iwc, 10240);
    if (config.enableIndexWriterInfoStream) {
      iwc.setInfoStream(new PrintStreamInfoStream(System.out));
    }
    return iwc;
  }

  // ── Main ──────────────────────────────────────────────────────────────────

  public static void main(String[] args) throws Throwable {

    if (args.length < 1 || args.length > 3) {
      System.err.println("Usage: ./benchmarks.sh <jobs-file> [benchmarkID] [resultsDir]");
      return;
    }

    BenchmarkConfiguration config =
        Util.newObjectMapper().readValue(new File(args[0]), BenchmarkConfiguration.class);

    if (args.length >= 2) {
      config.benchmarkID = args[1];
    }
    if (args.length >= 3) {
      config.resultsDirectory = args[2];
    }

    Map<String, Object> metrics = new LinkedHashMap<String, Object>();
    List<QueryResult> queryResults = Collections.synchronizedList(new ArrayList<QueryResult>());
    config.debugPrintArguments();

    // [0] Pre-check
    Util.preCheck(config);

    String datasetMapdbFile = config.datasetFile + ".mapdb";

    // [1] Parse/load data set
    List<String> titles = new ArrayList<String>();
    VectorProvider vectorProvider;

    long parseStartTime = System.currentTimeMillis();

    if (config.datasetFile.contains("fvecs") || config.datasetFile.contains("fbin")) {
      log.info("Detected .fvecs or .fbin file format. Loading directly without MapDB...");
      if (config.loadVectorsInMemory) {
        log.info("Loading all vectors in memory (loadVectorsInMemory is enabled)");
        long start = System.currentTimeMillis();
        List<float[]> loadedVectors = new ArrayList<float[]>();
        if (config.datasetFile.contains("fbin")) {
          FBIvecsReader.readFbin(config.datasetFile, config.numDocs, loadedVectors);
        } else {
          FBIvecsReader.readFvecs(config.datasetFile, config.numDocs, loadedVectors);
        }
        vectorProvider = new MemoryVectorProvider(loadedVectors);
        log.info(
            "Time taken to load {} vectors in-memory: {} ms",
            loadedVectors.size(),
            (System.currentTimeMillis() - start));
      } else {
        log.info("Creating streaming vector provider (loadVectorsInMemory is disabled)");
        vectorProvider = new StreamingVectorProvider(config.datasetFile, config.numDocs);
      }
      titles.add(config.vectorColName);
    } else {
      DB db;
      IndexTreeList<float[]> vectors;
      if (new File(datasetMapdbFile).exists() == false) {
        log.info("Mapdb file not found for dataset. Preparing one ...");
        db = DBMaker.fileDB(datasetMapdbFile).make();
        vectors = db.indexTreeList("vectors", SERIALIZER.FLOAT_ARRAY).createOrOpen();
        if (config.datasetFile.endsWith(".csv") || config.datasetFile.endsWith(".csv.gz")) {
          Util.parseCSVFile(config, titles, vectors);
        } else if (config.datasetFile.contains("bvecs")) {
          Util.readBaseFile(config, titles, vectors);
        }
        log.info("Created a mapdb file with {} number of vectors.", vectors.size());
      } else {
        log.info("Mapdb file found for vectors. Loading ...");
        db = DBMaker.fileDB(datasetMapdbFile).make();
        vectors = db.indexTreeList("vectors", SERIALIZER.FLOAT_ARRAY).createOrOpen();
        log.info("{} vectors available from the mapdb file", vectors.size());
      }
      if (config.loadVectorsInMemory) {
        log.info(
            "Mapdb loaded. Now loading all vectors in memory (loadVectorsInMemory is enabled)");
        long start = System.currentTimeMillis();
        List<float[]> loadedVectors = new ArrayList<float[]>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
          loadedVectors.add(vectors.get(i));
        }
        vectorProvider = new MemoryVectorProvider(loadedVectors);
        db.close();
        log.info(
            "Time taken to load the vectors in-memory is: {}",
            (System.currentTimeMillis() - start));
      } else {
        vectorProvider = new MapDBVectorProvider(vectors, db);
      }
    }
    log.info(
        "Time taken for parsing/loading dataset is {} ms",
        (System.currentTimeMillis() - parseStartTime));

    try {
      // [2] Benchmarking setup — indexing
      if (!config.skipIndexing) {

        // ── Phase 1: flush (no forceMerge) ────────────────────────────────────
        // Build the flush writer with the flush-time codec. The merge policy is
        // NoMergePolicy (or TieredMergePolicy if enableTieredMerge), but forceMerge
        // is never called on this writer. That happens in Phase 2 below, using a
        // separate writer that carries the merge-time nLists/nProbes.
        Codec flushCodec = getCodec(config, false);
        IndexWriterConfig flushWriterConfig = buildFlushWriterConfig(config, flushCodec);

        Directory indexDirectory;
        if (!config.createIndexInMemory) {
          indexDirectory = FSDirectory.open(Path.of(config.indexDirPath));
        } else {
          indexDirectory = new ByteBuffersDirectory();
        }

        IndexWriter flushWriter = new IndexWriter(indexDirectory, flushWriterConfig);
        var formatName = flushWriter.getConfig().getCodec().knnVectorsFormat().getName();
        log.info("Indexing documents using {} ...", formatName);

        long indexStartTime = System.currentTimeMillis();
        indexDocuments(flushWriter, config, titles, vectorProvider);
        long indexTimeTaken = System.currentTimeMillis() - indexStartTime;
        metrics.put(config.algoToRun + "-indexing-time", indexTimeTaken);
        log.info("Time taken for index building (end to end): {} ms", indexTimeTaken);

        // ── Phase 2: forceMerge with merge-time codec ──────────────────────────
        // Opens a fresh IndexWriter on the same directory so the codec — and
        // therefore nLists/nProbes — can differ from the flush writer above.
        // Only runs when forceMerge > 0.
        if (config.forceMerge > 0) {
          int effectiveNLists =
              config.cuVSIvfPqIndexParamsForceMergeNLists > 0
                  ? config.cuVSIvfPqIndexParamsForceMergeNLists
                  : config.cuVSIvfPqIndexParamsNLists;
          int effectiveNProbes =
              config.cuVSIvfPqSearchParamsForceMergeNProbes > 0
                  ? config.cuVSIvfPqSearchParamsForceMergeNProbes
                  : config.cuVSIvfPqSearchParamsNProbes;
          log.info(
              "Starting forceMerge phase — target segments: {}, nLists: {} (flush was: {}),"
                  + " nProbes: {} (flush was: {})",
              config.forceMerge,
              effectiveNLists,
              config.cuVSIvfPqIndexParamsNLists,
              effectiveNProbes,
              config.cuVSIvfPqSearchParamsNProbes);

          Codec mergeCodec = getCodec(config, true);
          IndexWriterConfig mergeWriterConfig = buildMergeWriterConfig(config, mergeCodec);
          IndexWriter mergeWriter = new IndexWriter(indexDirectory, mergeWriterConfig);
          long mergeStart = System.currentTimeMillis();
          mergeWriter.forceMerge(config.forceMerge);
          mergeWriter.commit();
          mergeWriter.close();
          log.info("forceMerge completed in {} ms", (System.currentTimeMillis() - mergeStart));
        }

        // ── Index size reporting ───────────────────────────────────────────────
        try {
          if (indexDirectory instanceof FSDirectory) {
            Path indexPath = Paths.get(config.indexDirPath);
            long directorySize;
            try (var stream = Files.walk(indexPath, FileVisitOption.FOLLOW_LINKS)) {
              directorySize =
                  stream.filter(p -> p.toFile().isFile()).mapToLong(p -> p.toFile().length()).sum();
            }
            double directorySizeGB = directorySize / 1_073_741_824.0;
            metrics.put(config.algoToRun + "-index-size", directorySizeGB);
            log.info("Size of {}: {} GB", indexPath.toString(), directorySizeGB);
          }
        } catch (IOException e) {
          log.error("Failed to calculate directory size for {}", config.indexDirPath, e);
        }
      }

      // [3] Search — one run per efSearch value
      List<Integer> efSearchValues = config.getEfSearchValues();
      log.info(
          "Will run search with {} efSearch value(s): {}", efSearchValues.size(), efSearchValues);

      List<int[]> groundTruth = Util.readGroundTruthFile(config.groundTruthFile);
      Directory indexDir = MMapDirectory.open(Path.of(config.indexDirPath));
      log.info("Index directory is: {} (using memory-mapped files)", indexDir);

      Map<String, Object> indexingMetrics = new LinkedHashMap<>(metrics);

      for (int efSearch : efSearchValues) {
        log.info("--- Running search with efSearch={} ---", efSearch);

        List<QueryResult> efSearchQueryResults =
            Collections.synchronizedList(new ArrayList<QueryResult>());
        Map<String, Object> efSearchMetrics = new LinkedHashMap<String, Object>();
        efSearchMetrics.putAll(indexingMetrics);
        efSearchMetrics.put("efSearch", efSearch);

        log.info("Querying documents using {} with efSearch={} ...", config.algoToRun, efSearch);
        search(indexDir, config, efSearchMetrics, efSearchQueryResults, groundTruth, efSearch);

        Util.calculateRecallAccuracy(efSearchQueryResults, efSearchMetrics, config.algoToRun);

        String resultsJson =
            Util.newObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("configuration", config, "metrics", efSearchMetrics));

        if (config.saveResultsOnDisk) {
          String resultsDir = config.resultsDirectory != null ? config.resultsDirectory : "results";
          if (efSearchValues.size() > 1) {
            resultsDir = resultsDir + "/efSearch_" + efSearch;
          }
          File results = new File(resultsDir);
          if (!results.exists()) {
            results.mkdirs();
          }
          FileUtils.write(
              new File(results.toString() + "/results.json"),
              resultsJson,
              Charset.forName("UTF-8"));
          Util.writeCSV(efSearchQueryResults, results.toString() + "/neighbors.csv");
          log.info("Results for efSearch={} saved to directory: {}", efSearch, resultsDir);
        }

        log.info(
            "\n-----\nMetrics for efSearch={}: {}\n{}\n-----",
            efSearch,
            efSearchMetrics,
            resultsJson);

        for (Map.Entry<String, Object> entry : efSearchMetrics.entrySet()) {
          if (efSearchValues.size() > 1) {
            metrics.put("efSearch_" + efSearch + "/" + entry.getKey(), entry.getValue());
          } else {
            metrics.put(entry.getKey(), entry.getValue());
          }
        }
      }

      log.info("\n-----\nOverall metrics: {}\n-----", metrics);
      indexDir.close();

      if (config.cleanIndexDirectory && !config.createIndexInMemory) {
        Path indexPath = Path.of(config.indexDirPath);
        if (indexPath != null) {
          try {
            log.info("Cleaning index directory: {}", indexPath);
            FileUtils.deleteDirectory(indexPath.toFile());
            log.info("Successfully cleaned index directory: {}", indexPath);
          } catch (IOException e) {
            log.error("Failed to clean index directory: {}", indexPath, e);
          }
        }
      }
    } finally {
      if (vectorProvider != null) {
        vectorProvider.close();
      }
    }

    if (executorService != null && !executorService.isShutdown()) {
      executorService.shutdown();
      executorService.close();
      // Temporary fix: prevents process getting stuck on LUCENE_HNSW with multiple merge threads
      System.exit(0);
    }
  }

  // ── Indexing (flush only — forceMerge intentionally removed) ──────────────

  /**
   * Indexes all documents into the given writer, commits, and closes it.
   *
   * <p>forceMerge is intentionally <b>not</b> called here. It is handled in
   * {@code main()} by a separate {@link IndexWriter} that carries different
   * IVF-PQ parameters (nLists / nProbes) via the merge codec.
   */
  private static void indexDocuments(
      IndexWriter writer,
      BenchmarkConfiguration config,
      List<String> titles,
      VectorProvider vectorProvider)
      throws IOException, InterruptedException {

    int threads = config.numIndexThreads;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger numDocsIndexed = new AtomicInteger(0);
    log.info("Starting indexing with {} threads.", threads);
    log.info(
        "IndexWriter config - MaxBufferedDocs: {}, RAMBufferSizeMB: {}",
        writer.getConfig().getMaxBufferedDocs(),
        writer.getConfig().getRAMBufferSizeMB());
    final int numDocsToIndex = Math.min(config.numDocs, vectorProvider.size());

    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            while (true) {
              int id = numDocsIndexed.getAndIncrement();
              if (id >= numDocsToIndex) {
                break;
              }
              float[] vector;
              try {
                vector = Objects.requireNonNull(vectorProvider.get(id));
              } catch (IOException e) {
                throw new UncheckedIOException("Failed to read vector at index " + id, e);
              }
              Document doc = new Document();
              doc.add(new StringField("id", String.valueOf(id), Field.Store.YES));
              doc.add(new KnnFloatVectorField(config.vectorColName, vector, EUCLIDEAN));
              try {
                writer.addDocument(doc);
                if ((id + 1) % 25000 == 0) {
                  log.info(
                      "Done indexing {} documents. Pending docs: {}",
                      (id + 1),
                      writer.getPendingNumDocs());
                }
                if ((id + 1) == config.flushFreq || (id + 1) == 2 * config.flushFreq) {
                  log.info("Expected flush point reached at {} documents", (id + 1));
                }
              } catch (IOException ex) {
                throw new UncheckedIOException(ex);
              }
            }
          });
    }
    pool.shutdown();
    pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

    log.info("Calling commit.");
    writer.commit();
    writer.close();
  }

  // ── Search ────────────────────────────────────────────────────────────────

  /**
   * Runs search queries against the given index directory using the specified efSearch value.
   *
   * @param directory    the Lucene index directory to search
   * @param config       benchmark configuration
   * @param metrics      map to populate with search performance metrics
   * @param queryResults list to populate with per-query results
   * @param groundTruth  ground truth neighbor lists for recall calculation
   * @param efSearch     the efSearch (number of candidates) to use for this search run
   */
  private static void search(
      Directory directory,
      BenchmarkConfiguration config,
      Map<String, Object> metrics,
      List<QueryResult> queryResults,
      List<int[]> groundTruth,
      int efSearch) {

    DB db = null;
    try (IndexReader indexReader = DirectoryReader.open(directory)) {
      IndexSearcher indexSearcher = new IndexSearcher(indexReader);

      IndexTreeList<float[]> queries;
      String queryMapdbFile = config.queryFile + ".mapdb";

      if (new File(queryMapdbFile).exists() == false) {
        log.info("No mapdb file found for queries. Reading source files to build one ...");
        db = DBMaker.fileDB(queryMapdbFile).make();
        queries = db.indexTreeList("vectors", SERIALIZER.FLOAT_ARRAY).createOrOpen();
        if (config.queryFile.endsWith(".csv")) {
          for (String line :
              FileUtils.readFileToString(new File(config.queryFile), "UTF-8").split("\n")) {
            queries.add(Util.parseFloatArrayFromStringArray(line));
          }
        } else if (config.queryFile.contains("fvecs")) {
          FBIvecsReader.readFvecs(config.queryFile, -1, queries);
        } else if (config.queryFile.contains("fbin")) {
          FBIvecsReader.readFbin(config.queryFile, -1, queries);
        } else if (config.queryFile.contains("bvecs")) {
          FBIvecsReader.readBvecs(config.queryFile, -1, queries);
        }
        log.info("Mapdb file created with {} number of queries", queries.size());
      } else {
        log.info("Mapdb file found for queries. Loading ...");
        db = DBMaker.fileDB(queryMapdbFile).make();
        queries = db.indexTreeList("vectors", SERIALIZER.FLOAT_ARRAY).createOrOpen();
        log.info("{} queries available from the mapdb file", queries.size());
      }

      ExecutorService pool = Executors.newFixedThreadPool(config.queryThreads);
      AtomicInteger queriesFinished = new AtomicInteger(0);
      ConcurrentHashMap<Integer, Double> queryLatencies = new ConcurrentHashMap<Integer, Double>();
      ConcurrentHashMap<Integer, Double> retrievalLatencies =
          new ConcurrentHashMap<Integer, Double>();

      long startTime = System.currentTimeMillis();
      AtomicInteger queryId = new AtomicInteger(0);

      for (int t = 0; t < config.queryThreads; t++) {
        pool.submit(
            () -> {
              while (true) {
                int currentQueryId = queryId.getAndIncrement();
                if (currentQueryId >= config.numQueriesToRun) {
                  break;
                }
                try {
                  KnnFloatVectorQuery query;
                  if (config.algoToRun.equals(Codex.CAGRA_SEARCH)) {
                    query =
                        new GPUKnnFloatVectorQuery(
                            config.vectorColName,
                            queries.get(currentQueryId),
                            efSearch,
                            null,
                            config.cagraITopK,
                            config.cagraSearchWidth);
                  } else {
                    query =
                        new KnnFloatVectorQuery(
                            config.vectorColName, queries.get(currentQueryId), efSearch);
                  }

                  TopDocs topDocs;
                  long searchStartTime = System.nanoTime();
                  try {
                    TopScoreDocCollectorManager collectorManager =
                        new TopScoreDocCollectorManager(efSearch, null, Integer.MAX_VALUE, true);
                    topDocs = indexSearcher.search(query, collectorManager);
                  } catch (IOException e) {
                    throw new RuntimeException("Problem during executing a query: ", e);
                  }
                  double searchTimeTakenMs = (System.nanoTime() - searchStartTime) / 1_000_000.0;

                  if (currentQueryId > config.numWarmUpQueries) {
                    queryLatencies.put(currentQueryId, searchTimeTakenMs);
                  }
                  int finishedCount = queriesFinished.incrementAndGet();
                  if (finishedCount % 1000 == 0 || finishedCount == config.numQueriesToRun) {
                    log.info(
                        "Done querying "
                            + finishedCount
                            + " out of "
                            + config.numQueriesToRun
                            + " queries.");
                  }

                  ScoreDoc[] hits = topDocs.scoreDocs;
                  List<Integer> neighbors = new ArrayList<>();
                  List<Float> scores = new ArrayList<>();

                  if (queryId.get() == 0) {
                    log.info(
                        "Debug: First query returned "
                            + hits.length
                            + " hits (ef-search candidates)");
                    log.info(
                        "Debug: Will select top "
                            + config.topK
                            + " from "
                            + hits.length
                            + " candidates");
                  }
                  int numResultsToTake = Math.min(config.topK, hits.length);
                  long retrievalStartTime = System.nanoTime();
                  for (int i = 0; i < numResultsToTake; i++) {
                    ScoreDoc hit = hits[i];
                    try {
                      Document d = indexReader.storedFields().document(hit.doc);
                      neighbors.add(Integer.parseInt(d.get("id")));
                    } catch (IOException e) {
                      e.printStackTrace();
                    }
                    scores.add(hit.score);
                  }
                  double retrievalTimeTakenMs =
                      (System.nanoTime() - retrievalStartTime) / 1_000_000.0;
                  if (currentQueryId > config.numWarmUpQueries) {
                    retrievalLatencies.put(currentQueryId, retrievalTimeTakenMs);
                  }

                  log.debug(
                      "Query "
                          + currentQueryId
                          + " - First 5 neighbors: "
                          + neighbors.subList(0, Math.min(5, neighbors.size())));
                  log.debug(
                      "Query "
                          + currentQueryId
                          + " - First 5 distances: "
                          + scores.subList(0, Math.min(5, scores.size())));
                  int[] expectedNeighbors = groundTruth.get(currentQueryId);
                  log.debug(
                      "Query "
                          + currentQueryId
                          + " - Expected neighbors: "
                          + java.util.Arrays.toString(
                              java.util.Arrays.copyOf(
                                  expectedNeighbors, Math.min(5, expectedNeighbors.length))));

                  if (currentQueryId > config.numWarmUpQueries) {
                    QueryResult result =
                        new QueryResult(
                            config.algoToRun.toString(),
                            currentQueryId,
                            neighbors,
                            groundTruth.get(currentQueryId),
                            scores,
                            searchTimeTakenMs);
                    queryResults.add(result);
                  } else {
                    log.info("Skipping warmup query: {}", currentQueryId);
                  }
                } catch (Exception e) {
                  log.error(
                      "Exception during query {}: {} - {}",
                      currentQueryId,
                      e.getClass().getSimpleName(),
                      e.getMessage(),
                      e);
                }
              }
            });
      }

      pool.shutdown();
      pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
      long endTime = System.currentTimeMillis();

      metrics.put(config.algoToRun + "-query-time", (endTime - startTime));
      metrics.put(
          config.algoToRun + "-query-throughput",
          (endTime - startTime) > 0
              ? (config.numQueriesToRun / ((endTime - startTime) / 1000.0))
              : 0.0);
      double avgLatency =
          queryLatencies.isEmpty()
              ? 0.0
              : new ArrayList<>(queryLatencies.values()).stream().reduce(0.0, Double::sum)
                  / queryLatencies.size();
      double avgRetLatency =
          retrievalLatencies.isEmpty()
              ? 0.0
              : new ArrayList<>(retrievalLatencies.values()).stream().reduce(0.0, Double::sum)
                  / retrievalLatencies.size();
      metrics.put(config.algoToRun + "-mean-latency", avgLatency);
      metrics.put(config.algoToRun + "-mean-retrieval-latency", avgRetLatency);

      if (queryLatencies.isEmpty()) {
        log.error(
            "WARNING: Zero queries completed successfully! "
                + "Check the query thread exception logs above for the root cause. "
                + "queriesFinished={}, queryResults.size={}",
            queriesFinished.get(),
            queryResults.size());
      }

      int segmentCount = indexReader.leaves().size();
      metrics.put(config.algoToRun + "-segment-count", segmentCount);

    } catch (Exception e) {
      e.printStackTrace();
      log.error("Exception during querying", e);
    } finally {
      if (db != null) {
        db.close();
      }
    }
  }

  // ── Codec factory ─────────────────────────────────────────────────────────

  /**
   * Builds the Lucene {@link Codec} for the given phase.
   *
   * @param config   benchmark configuration
   * @param forMerge {@code true}  → substitute
   *                 {@link BenchmarkConfiguration#cuVSIvfPqIndexParamsForceMergeNLists} and
   *                 {@link BenchmarkConfiguration#cuVSIvfPqSearchParamsForceMergeNProbes}
   *                 when non-zero (merge-time codec);
   *                 {@code false} → always use the flush-time values.
   */
  private static Codec getCodec(BenchmarkConfiguration config, boolean forMerge) throws Exception {
    if (config.algoToRun.equals(Codex.LUCENE_HNSW)) {
      log.info("<<< Using Lucene101Codec >>>");
      return new Lucene101Codec(Mode.BEST_SPEED) {
        @Override
        public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
          KnnVectorsFormat knnFormat;
          if (config.hnswMergeThreads > 1) {
            executorService = Executors.newFixedThreadPool(config.hnswMergeThreads);
            knnFormat =
                new Lucene99HnswVectorsFormat(
                    config.hnswMaxConn,
                    config.hnswBeamWidth,
                    config.hnswMergeThreads,
                    executorService);
          } else {
            knnFormat = new Lucene99HnswVectorsFormat(config.hnswMaxConn, config.hnswBeamWidth);
          }
          return new HighDimensionKnnVectorsFormat(knnFormat, config.vectorDimension);
        }
      };
    }

    // ── Resolve effective nLists / nProbes ─────────────────────────────────
    int effectiveNLists =
        (forMerge && config.cuVSIvfPqIndexParamsForceMergeNLists > 0)
            ? config.cuVSIvfPqIndexParamsForceMergeNLists
            : config.cuVSIvfPqIndexParamsNLists;

    int effectiveNProbes =
        (forMerge && config.cuVSIvfPqSearchParamsForceMergeNProbes > 0)
            ? config.cuVSIvfPqSearchParamsForceMergeNProbes
            : config.cuVSIvfPqSearchParamsNProbes;

    if (forMerge) {
      log.info(
          "<<< Merge codec — nLists: {} (flush: {}), nProbes: {} (flush: {}) >>>",
          effectiveNLists,
          config.cuVSIvfPqIndexParamsNLists,
          effectiveNProbes,
          config.cuVSIvfPqSearchParamsNProbes);
    }

    CuVSIvfPqIndexParams ciip =
        new CuVSIvfPqIndexParams.Builder()
            .withAddDataOnBuild(config.cuVSIvfPqIndexParamsAddDataOnBuild)
            .withCodebookKind(config.cuVSIvfPqIndexParamsCodebookKind)
            .withConservativeMemoryAllocation(
                config.cuVSIvfPqIndexParamsConservativeMemoryAllocation)
            .withForceRandomRotation(config.cuVSIvfPqIndexParamsForceRandomRotation)
            .withKmeansNIters(config.cuVSIvfPqIndexParamsKmeansNIters)
            .withKmeansTrainsetFraction(config.cuVSIvfPqIndexParamsKmeansTrainsetFraction)
            .withMaxTrainPointsPerPqCode(config.cuVSIvfPqIndexParamsMaxTrainPointsPerPqCode)
            .withMetric(config.cuVSIvfPqIndexParamsMetric)
            .withMetricArg(config.cuVSIvfPqIndexParamsMetricArg)
            .withNLists(effectiveNLists) // ← flush or merge value
            .withPqBits(config.cuVSIvfPqIndexParamsPqBits)
            .withPqDim(config.cuVSIvfPqIndexParamsPqDim)
            .build();

    CuVSIvfPqSearchParams cisp =
        new CuVSIvfPqSearchParams.Builder()
            .withInternalDistanceDtype(config.cuVSIvfPqSearchParamsInternalDistanceDtype)
            .withLutDtype(config.cuVSIvfPqSearchParamsLutDtype)
            .withNProbes(effectiveNProbes) // ← flush or merge value
            .withPreferredShmemCarveout(config.cuVSIvfPqSearchParamsPreferredShmemCarveout)
            .build();

    CuVSIvfPqParams cip =
        new CuVSIvfPqParams.Builder()
            .withCuVSIvfPqIndexParams(ciip)
            .withCuVSIvfPqSearchParams(cisp)
            .withRefinementRate(config.cuVSIvfPqParamsRefinementRate)
            .build();

    AcceleratedHNSWParams params =
        new AcceleratedHNSWParams.Builder()
            .withWriterThreads(config.cuvsWriterThreads)
            .withIntermediateGraphDegree(config.cagraIntermediateGraphDegree)
            .withGraphDegree(config.cagraGraphDegree)
            .withHNSWLayer(config.cagraHnswLayers)
            .withMaxConn(config.hnswMaxConn)
            .withBeamWidth(config.hnswBeamWidth)
            .withCagraGraphBuildAlgo(config.cagraGraphBuildAlgo)
            .withCuVSIvfPqParams(cip)
            .build();

    if (config.algoToRun.equals(Codex.CAGRA_HNSW)) {
      log.info("<<< Using Lucene101AcceleratedHNSWCodec >>>");
      return new Lucene101AcceleratedHNSWCodec(params);
    } else if (config.algoToRun.equals(Codex.CAGRA_SEARCH)) {
      log.info("<<< Using CuVS2510GPUSearchCodec >>>");
      GPUSearchParams gpuParams =
          new GPUSearchParams.Builder()
              .withCagraGraphBuildAlgo(config.cagraGraphBuildAlgo)
              .withWriterThreads(config.cuvsWriterThreads)
              .withIntermediateGraphDegree(config.cagraIntermediateGraphDegree)
              .withGraphDegree(config.cagraGraphDegree)
              .build();
      return new CuVS2510GPUSearchCodec(gpuParams);
    } else if (config.algoToRun.equals(Codex.CAGRA_HNSW_BINARY)) {
      log.info("<<< Using LuceneAcceleratedHNSWBinaryQuantizedCodec >>>");
      return new LuceneAcceleratedHNSWBinaryQuantizedCodec(params);
    } else if (config.algoToRun.equals(Codex.CAGRA_HNSW_SCALAR)) {
      log.info("<<< Using LuceneAcceleratedHNSWScalarQuantizedCodec >>>");
      return new LuceneAcceleratedHNSWScalarQuantizedCodec(params);
    }

    return null;
  }

  // ── Inner helpers ─────────────────────────────────────────────────────────

  private static class HighDimensionKnnVectorsFormat extends KnnVectorsFormat {
    private final KnnVectorsFormat knnFormat;
    private final int maxDimensions;

    public HighDimensionKnnVectorsFormat(KnnVectorsFormat knnFormat, int maxDimensions) {
      super(knnFormat.getName());
      this.knnFormat = knnFormat;
      this.maxDimensions = maxDimensions;
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
      return knnFormat.fieldsWriter(state);
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
      return knnFormat.fieldsReader(state);
    }

    @Override
    public int getMaxDimensions(String fieldName) {
      return maxDimensions;
    }
  }
}
