package com.searchscale.lucene.cuvs.benchmarks;

import static org.apache.lucene.index.VectorSimilarityFunction.EUCLIDEAN;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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
import org.apache.lucene.codecs.FilterCodec;
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
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.sandbox.vectorsearch.CuVSKnnFloatVectorQuery;
import org.apache.lucene.sandbox.vectorsearch.CuVSVectorsFormat;
import org.apache.lucene.sandbox.vectorsearch.CuVSVectorsWriter.IndexType;
import org.apache.lucene.sandbox.vectorsearch.CuVSVectorsWriter.MergeStrategy;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.PrintStreamInfoStream;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.IndexTreeList;
import org.mapdb.QueueLong.Node.SERIALIZER;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LuceneCuvsBenchmarks {

  private static final Logger log = LoggerFactory.getLogger(LuceneCuvsBenchmarks.class.getName());

  private static boolean RESULTS_DEBUGGING = false; // when enabled, titles are indexed and printed after search
  private static boolean INDEX_WRITER_INFO_STREAM = false; // when enabled, prints information about merges, deletes,

  @SuppressWarnings("resource")
  public static void main(String[] args) throws Throwable {

    if (args.length != 1) {
      System.err.println("Usage: ./benchmarks.sh <jobs-file>");
      return;
    }

    BenchmarkConfiguration config = Util.newObjectMapper().readValue(new File(args[0]), BenchmarkConfiguration.class);
    Map<String, Object> metrics = new LinkedHashMap<String, Object>();
    List<QueryResult> queryResults = Collections.synchronizedList(new ArrayList<QueryResult>());
    config.debugPrintArguments();

    // [0] Pre-check
    Util.preCheck(config);

    String datasetMapdbFile = config.datasetFile + ".mapdb";

    // [1] Parse/load data set
    List<String> titles = new ArrayList<String>();
    DB db;
    List<float[]> vectors;

    long parseStartTime = System.currentTimeMillis();

    if (new File(datasetMapdbFile).exists() == false) {
      log.info("Mapdb file not found for dataset. Preparing one ...");
      db = DBMaker.fileDB(datasetMapdbFile).make();
      vectors = db.indexTreeList("vectors", SERIALIZER.FLOAT_ARRAY).createOrOpen();
      if (config.datasetFile.endsWith(".csv") || config.datasetFile.endsWith(".csv.gz")) {
        Util.parseCSVFile(config, titles, vectors);
      } else if (config.datasetFile.contains("fvecs") || config.datasetFile.contains("bvecs")) {
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
      log.info("Mapdb loaded. Now loading all vectors in memory (loadVectorsInMemory is enabled)");
      long start = System.currentTimeMillis();
      List<float[]> loadedVectors = new ArrayList<float[]>(vectors.size());
      for (int i = 0; i < vectors.size(); i++) {
        loadedVectors.add(vectors.get(i));
      }
      vectors = loadedVectors;
      log.info("Time taken to load the vectors in-memory is: {}", (System.currentTimeMillis() - start));
    }

    log.info("Time taken for parsing/loading dataset is {} ms", (System.currentTimeMillis() - parseStartTime));

    // [2] Benchmarking setup
    try {

      // HNSW Writer:
      IndexWriterConfig luceneHNSWWriterConfig = new IndexWriterConfig(new StandardAnalyzer());
      luceneHNSWWriterConfig.setCodec(getLuceneHnswCodec(config));
      luceneHNSWWriterConfig.setUseCompoundFile(false);
      luceneHNSWWriterConfig.setMaxBufferedDocs(config.flushFreq);
      luceneHNSWWriterConfig.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);

      // CuVS Writer:
      // IndexWriterConfig cuvsIndexWriterConfig = new IndexWriterConfig(new
      // StandardAnalyzer()).setCodec(new CuVSCodec(
      // config.cuvsWriterThreads, config.cagraIntermediateGraphDegree,
      // config.cagraGraphDegree, config.mergeStrategy));
      IndexWriterConfig cuvsIndexWriterConfig = new IndexWriterConfig(new StandardAnalyzer());
      cuvsIndexWriterConfig.setCodec(getCuVSCodec(config));
      cuvsIndexWriterConfig.setUseCompoundFile(false);
      cuvsIndexWriterConfig.setMaxBufferedDocs(config.flushFreq);
      cuvsIndexWriterConfig.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);

      if (INDEX_WRITER_INFO_STREAM) {
        luceneHNSWWriterConfig.setInfoStream(new PrintStreamInfoStream(System.out));
        cuvsIndexWriterConfig.setInfoStream(new PrintStreamInfoStream(System.out));
      }

      // if (config.mergeStrategy.equals(MergeStrategy.NON_TRIVIAL_MERGE)) {
      // luceneHNSWWriterConfig.setMergePolicy(NoMergePolicy.INSTANCE);
      // cuvsIndexWriterConfig.setMergePolicy(NoMergePolicy.INSTANCE);
      // }

      IndexWriter luceneHnswIndexWriter = null;
      IndexWriter cuvsIndexWriter = null;

      // if (!config.createIndexInMemory) {
      // Path hnswIndex = Path.of(config.hnswIndexDirPath);
      // Path cuvsIndex = Path.of(config.cuvsIndexDirPath);
      // if (config.cleanIndexDirectory) {
      // FileUtils.deleteDirectory(hnswIndex.toFile());
      // FileUtils.deleteDirectory(cuvsIndex.toFile());
      // }
      // luceneHnswIndexWriter = new IndexWriter(FSDirectory.open(hnswIndex),
      // luceneHNSWWriterConfig);
      // cuvsIndexWriter = new IndexWriter(FSDirectory.open(cuvsIndex),
      // cuvsIndexWriterConfig);
      // } else {
      // luceneHnswIndexWriter = new IndexWriter(new ByteBuffersDirectory(),
      // luceneHNSWWriterConfig);
      // cuvsIndexWriter = new IndexWriter(new ByteBuffersDirectory(),
      // cuvsIndexWriterConfig);
      // }

      if (config.algoToRun.equalsIgnoreCase("HNSW")) {
        if (!config.createIndexInMemory) {
          Path hnswIndex = Path.of(config.hnswIndexDirPath);
          if (config.cleanIndexDirectory) {
            FileUtils.deleteDirectory(hnswIndex.toFile());
          }
          luceneHnswIndexWriter = new IndexWriter(FSDirectory.open(hnswIndex), luceneHNSWWriterConfig);
        } else {
          luceneHnswIndexWriter = new IndexWriter(new ByteBuffersDirectory(), luceneHNSWWriterConfig);
        }
      } else if (config.algoToRun.equalsIgnoreCase("CAGRA")) {
        if (!config.createIndexInMemory) {
          Path cuvsIndex = Path.of(config.cuvsIndexDirPath);
          if (config.cleanIndexDirectory) {
            FileUtils.deleteDirectory(cuvsIndex.toFile());
          }
          cuvsIndexWriter = new IndexWriter(FSDirectory.open(cuvsIndex), cuvsIndexWriterConfig);
        } else {
          cuvsIndexWriter = new IndexWriter(new ByteBuffersDirectory(), cuvsIndexWriterConfig);
        }
      }

      ArrayList<IndexWriter> writers = new ArrayList<IndexWriter>();

      if ("HNSW".equalsIgnoreCase(config.algoToRun)) {
        writers.add(luceneHnswIndexWriter);
      } else if ("CAGRA".equalsIgnoreCase(config.algoToRun)) {
        writers.add(cuvsIndexWriter);
      } else {
        throw new IllegalArgumentException("Please pass an acceptable option for `algoToRun`. Choices: HNSW, CAGRA");
      }

      for (IndexWriter writer : writers) {
        var formatName = writer.getConfig().getCodec().knnVectorsFormat().getName();
        boolean isCuVS = formatName.equals("CuVSVectorsFormat");
        log.info("Indexing documents using {} ...", formatName); // error for different coloring
        long indexStartTime = System.currentTimeMillis();
        indexDocuments(writer, config, titles, vectors);
        long indexTimeTaken = System.currentTimeMillis() - indexStartTime;
        if (isCuVS) {
          metrics.put("cuvs-indexing-time", indexTimeTaken);
        } else {
          metrics.put("hnsw-indexing-time", indexTimeTaken);
        }

        log.info("Time taken for index building (end to end): {} ms", indexTimeTaken);

        boolean usingFSDirectory = luceneHnswIndexWriter != null
            ? luceneHnswIndexWriter.getDirectory() instanceof FSDirectory
            : cuvsIndexWriter.getDirectory() instanceof FSDirectory;

        try {
          if (usingFSDirectory) {
            Path indexPath = writer == cuvsIndexWriter ? Paths.get(config.cuvsIndexDirPath)
                : Paths.get(config.hnswIndexDirPath);
            long directorySize;
            try (var stream = Files.walk(indexPath, FileVisitOption.FOLLOW_LINKS)) {
              directorySize = stream.filter(p -> p.toFile().isFile()).mapToLong(p -> p.toFile().length()).sum();
            }
            double directorySizeGB = directorySize / 1_073_741_824.0;
            if (writer == cuvsIndexWriter) {
              metrics.put("cuvs-index-size", directorySizeGB);
            } else {
              metrics.put("hnsw-index-size", directorySizeGB);
            }
            log.info("Size of {}: {} GB", indexPath.toString(), directorySizeGB);
          }
        } catch (IOException e) {
          log.error("Failed to calculate directory size for {}",
              writer == cuvsIndexWriter ? config.cuvsIndexDirPath : config.hnswIndexDirPath, e);
        }
        log.info("Querying documents using {} ...", formatName);
        query(writer.getDirectory(), config, isCuVS, metrics, queryResults,
            Util.readGroundTruthFile(config.groundTruthFile));

        Util.calculateRecallAccuracy(queryResults, metrics, writer == cuvsIndexWriter);
      }

      String resultsJson = Util.newObjectMapper().writerWithDefaultPrettyPrinter()
          .writeValueAsString(Map.of("configuration", config, "metrics", metrics));

      if (config.saveResultsOnDisk) {
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(Calendar.getInstance().getTime());
        File results = new File("results");
        if (!results.exists()) {
          results.mkdir();
        }

        Util.writeCSV(queryResults, results.toString() + "/" + config.benchmarkID + "_neighbors_" + timeStamp + ".csv");

        FileUtils.write(
            new File(results.toString() + "/" + config.benchmarkID + "_benchmark_results_" + timeStamp + ".json"),
            resultsJson, Charset.forName("UTF-8"));
      }

      log.info("\n-----\nOverall metrics: " + metrics + "\nMetrics: \n" + resultsJson + "\n-----");
    } finally {
      if (db != null) {
        db.close();
      }
    }
  }

  private static void indexDocuments(IndexWriter writer, BenchmarkConfiguration config, List<String> titles,
      List<float[]> vectors) throws IOException, InterruptedException {

    int threads = config.numIndexThreads;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger numDocsIndexed = new AtomicInteger(0);
    log.info("Starting indexing with {} threads.", threads);
    final int numDocsToIndex = config.numDocs;

    for (int i = 0; i <= threads; i++) {
      pool.submit(() -> {
        while (true) {
          int id = numDocsIndexed.getAndIncrement();
          if (id >= numDocsToIndex) {
            break; // done
          }
          float[] vector = Objects.requireNonNull(vectors.get(id));
          Document doc = new Document();
          doc.add(new StringField("id", String.valueOf(id), Field.Store.YES));
          doc.add(new KnnFloatVectorField(config.vectorColName, vector, EUCLIDEAN));
          if (RESULTS_DEBUGGING)
            doc.add(new StringField("title", titles.get(id), Field.Store.YES));
          try {
            writer.addDocument(doc);
            if ((id + 1) % 25000 == 0) {
              log.info("Done indexing {} documents.", (id + 1));
            }
          } catch (IOException ex) {
            throw new UncheckedIOException(ex);
          }
        }
      });
    }
    pool.shutdown();
    pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

    // log.info("Calling forceMerge(1).");
    // writer.forceMerge(1);
    log.info("Calling commit.");
    writer.commit();
    writer.close();
  }

  private static void query(Directory directory, BenchmarkConfiguration config, boolean useCuVS,
      Map<String, Object> metrics, List<QueryResult> queryResults, List<int[]> groundTruth) {

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
          for (String line : FileUtils.readFileToString(new File(config.queryFile), "UTF-8").split("\n")) {
            queries.add(Util.parseFloatArrayFromStringArray(line));
          }
        } else if (config.queryFile.contains("fvecs")) {
          FBIvecsReader.readFvecs(config.queryFile, -1, queries);
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

      int qThreads = config.queryThreads;
      if (useCuVS)
        qThreads = 1;
      ExecutorService pool = Executors.newFixedThreadPool(qThreads);
      AtomicInteger queriesFinished = new AtomicInteger(0);
      ConcurrentHashMap<Integer, Double> queryLatencies = new ConcurrentHashMap<Integer, Double>();

      long startTime = System.currentTimeMillis();
      AtomicInteger queryId = new AtomicInteger(0);
      queries.stream().limit(config.numQueriesToRun).forEach((queryVector) -> {
        pool.submit(() -> {
          KnnFloatVectorQuery query;

          if (useCuVS) {
            query = new CuVSKnnFloatVectorQuery(config.vectorColName, queryVector, config.topK, config.cagraITopK,
                config.cagraSearchWidth);
          } else {
            query = new KnnFloatVectorQuery(config.vectorColName, queryVector, config.topK);
          }

          TopDocs topDocs;
          long searchStartTime = System.nanoTime();
          try {
            TopScoreDocCollectorManager collectorManager = new TopScoreDocCollectorManager(config.topK, null,
                Integer.MAX_VALUE, true);
            topDocs = indexSearcher.search(query, collectorManager);
          } catch (IOException e) {
            throw new RuntimeException("Problem during executing a query: ", e);
          }
          double searchTimeTakenMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - searchStartTime);
          // log.info("End to end search took: " + searchTimeTakenMs);
          queryLatencies.put(queryId.get(), searchTimeTakenMs);
          queriesFinished.incrementAndGet();

          ScoreDoc[] hits = topDocs.scoreDocs;
          List<Integer> neighbors = new ArrayList<>();
          List<Float> scores = new ArrayList<>();
          for (ScoreDoc hit : hits) {
            try {
              Document d = indexReader.storedFields().document(hit.doc);
              neighbors.add(Integer.parseInt(d.get("id")));
            } catch (IOException e) {
              e.printStackTrace();
            }
            scores.add(hit.score);
          }

          var s = useCuVS ? "lucene_cuvs" : "lucene_hnsw";
          QueryResult result = new QueryResult(s, queryId.get(), neighbors, groundTruth.get(queryId.get()), scores,
              searchTimeTakenMs);
          queryResults.add(result);
          queryId.addAndGet(1);
        });
      });

      pool.shutdown();
      pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

      long endTime = System.currentTimeMillis();

      metrics.put((useCuVS ? "cuvs" : "hnsw") + "-query-time", (endTime - startTime));
      metrics.put((useCuVS ? "cuvs" : "hnsw") + "-query-throughput",
          (queriesFinished.get() / ((endTime - startTime) / 1000.0)));
      double avgLatency = new ArrayList<>(queryLatencies.values()).stream().reduce(0.0, Double::sum)
          / queriesFinished.get();
      metrics.put((useCuVS ? "cuvs" : "hnsw") + "-mean-latency", avgLatency);

    } catch (Exception e) {
      e.printStackTrace();
      log.error("Exception during querying", e);
    } finally {
      if (db != null) {
        db.close();
      }
    }
  }

  private static Lucene101Codec getLuceneHnswCodec(BenchmarkConfiguration config) {
    return new Lucene101Codec(Mode.BEST_SPEED) {

      static final int DEFAULT_MAX_CONN = Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN; // 16
      static final int DEFAULT_BEAM_WIDTH = Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH; // 100

      @Override
      public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
        KnnVectorsFormat knnFormat = new Lucene99HnswVectorsFormat(config.hnswMaxConn, config.hnswBeamWidth);
        // KnnVectorsFormat knnFormat = new Lucene99HnswVectorsFormat(DEFAULT_MAX_CONN,
        // DEFAULT_BEAM_WIDTH);
        return new HighDimensionKnnVectorsFormat(knnFormat, config.vectorDimension);
      }
    };
  }

  private static Codec getCuVSCodec(BenchmarkConfiguration config) {
    return new CuVSCodec(config.cuvsWriterThreads, config.cagraIntermediateGraphDegree, config.cagraGraphDegree);
  }

  static final class CuVSCodec extends FilterCodec {

    static final int CUVS_WRITER_THREADS = 32;

    // Unused
    static final KnnVectorsFormat KNN_FORMAT = new CuVSVectorsFormat(CUVS_WRITER_THREADS, 128, 64,
        MergeStrategy.NON_TRIVIAL_MERGE, IndexType.CAGRA);

    int cuvsWriterThreads = CUVS_WRITER_THREADS;
    int cuvsIntermediateGraphDegree = 128;
    int cuvsGraphDegree = 64;

    CuVSCodec() {
      this("CuVSCodec", new Lucene101Codec());
    }

    CuVSCodec(int cuvsWriterThreads, int cuvsIntermediateGraphDegree, int cuvsGraphDegree) {
      this("CuVSCodec", new Lucene101Codec());
      this.cuvsWriterThreads = cuvsWriterThreads;
      this.cuvsIntermediateGraphDegree = cuvsIntermediateGraphDegree;
      this.cuvsGraphDegree = cuvsGraphDegree;
    }

    private CuVSCodec(String name, Codec delegate) {
      super(name, delegate);
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
      return new CuVSVectorsFormat(cuvsWriterThreads, cuvsIntermediateGraphDegree, cuvsGraphDegree,
          MergeStrategy.NON_TRIVIAL_MERGE, IndexType.CAGRA);
    }
  }

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