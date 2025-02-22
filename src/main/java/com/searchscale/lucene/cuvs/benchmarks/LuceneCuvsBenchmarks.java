package com.searchscale.lucene.cuvs.benchmarks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.nio.charset.Charset;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;
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
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.sandbox.vectorsearch.CuVSCodec;
import org.apache.lucene.sandbox.vectorsearch.CuVSKnnFloatVectorQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollectorManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.PrintStreamInfoStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;

public class LuceneCuvsBenchmarks {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static boolean RESULTS_DEBUGGING = false; // when enabled, titles are indexed and printed after search
  private static boolean INDEX_WRITER_INFO_STREAM = false; // when enabled, prints information about merges, deletes, etc

  @SuppressWarnings("resource")
  public static void main(String[] args) throws Throwable {
    BenchmarkConfiguration config = new ObjectMapper().readValue(new File(args[0]), BenchmarkConfiguration.class);
    Map<String, Object> metrics = new HashMap<String, Object>();
    List<QueryResult> queryResults = Collections.synchronizedList(new ArrayList<QueryResult>());
    config.debugPrintArguments();
    
    // [0] Pre-check
    if (!new File(config.datasetFile).exists()) {
      log.warn(config.datasetFile + " is not found. Not proceeding.");
      System.exit(1);
    }
    
    if (!new File(config.queryFile).exists()) {
      log.warn(config.queryFile + " is not found. Not proceeding.");
      System.exit(1);
    }
    
    if (!new File(config.groundTruthFile).exists()) {
      log.warn(config.groundTruthFile + " is not found. Not proceeding.");
      System.exit(1);
    }

    // [1] Read CSV file and parse data set
    log.info("Parsing CSV file ...");
    List<String> titles = new ArrayList<String>();
    List<float[]> vectorColumn = new ArrayList<float[]>();
    long parseStartTime = System.currentTimeMillis();

    if (config.datasetFile.endsWith(".csv") || config.datasetFile.endsWith(".csv.gz")) {
      parseCSVFile(config, titles, vectorColumn);
    } else if (config.datasetFile.contains("fvecs") || config.datasetFile.contains("bvecs")) {
      readBaseFile(config, titles, vectorColumn);
    }

    System.out.println("Time taken for parsing dataset: " + (System.currentTimeMillis() - parseStartTime + " ms"));

    // [2] Benchmarking setup

    // HNSW Writer:
    IndexWriterConfig hnswWriterConfig = new IndexWriterConfig(new StandardAnalyzer());
    hnswWriterConfig.setCodec(getHnswCodec(config));
    hnswWriterConfig.setUseCompoundFile(false);
    hnswWriterConfig.setMaxBufferedDocs(config.commitFreq);
    hnswWriterConfig.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);

    // CuVS Writer:
    // IndexWriterConfig cuvsIndexWriterConfig = new IndexWriterConfig(new
    // StandardAnalyzer()).setCodec(new CuVSCodec(
    // config.cuvsWriterThreads, config.cagraIntermediateGraphDegree,
    // config.cagraGraphDegree, config.mergeStrategy));
    IndexWriterConfig cuvsIndexWriterConfig = new IndexWriterConfig(new StandardAnalyzer());
    cuvsIndexWriterConfig.setCodec(new CuVSCodec());
    cuvsIndexWriterConfig.setUseCompoundFile(false);
    cuvsIndexWriterConfig.setMaxBufferedDocs(config.commitFreq);
    cuvsIndexWriterConfig.setRAMBufferSizeMB(IndexWriterConfig.DISABLE_AUTO_FLUSH);

    if (INDEX_WRITER_INFO_STREAM) {
      hnswWriterConfig.setInfoStream(new PrintStreamInfoStream(System.out));
      cuvsIndexWriterConfig.setInfoStream(new PrintStreamInfoStream(System.out));
    }

//    if (config.mergeStrategy.equals(MergeStrategy.NON_TRIVIAL_MERGE)) {
//      hnswWriterConfig.setMergePolicy(NoMergePolicy.INSTANCE);
//      cuvsIndexWriterConfig.setMergePolicy(NoMergePolicy.INSTANCE);
//    }

    IndexWriter hnswIndexWriter;
    IndexWriter cuvsIndexWriter;

    if (!config.createIndexInMemory) {
      Path hnswIndex = Path.of(config.hnswIndexDirPath);
      Path cuvsIndex = Path.of(config.cuvsIndexDirPath);
      if (config.cleanIndexDirectory) {
        FileUtils.deleteDirectory(hnswIndex.toFile());
        FileUtils.deleteDirectory(cuvsIndex.toFile());
      }
      hnswIndexWriter = new IndexWriter(FSDirectory.open(hnswIndex), hnswWriterConfig);
      cuvsIndexWriter = new IndexWriter(FSDirectory.open(cuvsIndex), cuvsIndexWriterConfig);
    } else {
      hnswIndexWriter = new IndexWriter(new ByteBuffersDirectory(), hnswWriterConfig);
      cuvsIndexWriter = new IndexWriter(new ByteBuffersDirectory(), cuvsIndexWriterConfig);
    }

    ArrayList<IndexWriter> writers = new ArrayList<IndexWriter>();

    if ("ALL".equalsIgnoreCase(config.algoToRun)) {
      writers.add(cuvsIndexWriter);
      writers.add(hnswIndexWriter);
    } else if ("HNSW".equalsIgnoreCase(config.algoToRun)) {
      writers.add(hnswIndexWriter);
    } else if ("CAGRA".equalsIgnoreCase(config.algoToRun)) {
      writers.add(cuvsIndexWriter);
    } else {
      throw new IllegalArgumentException("Please pass an acceptable option for `algoToRun`. Choices: ALL, HNSW, CAGRA");
    }

    for (IndexWriter writer : writers) {
      Codec codec = writer.getConfig().getCodec();
      String codecName = codec.getClass().getSimpleName().isEmpty() ? codec.getClass().getSuperclass().getSimpleName()
          : codec.getClass().getSimpleName();
      log.info("----------\nIndexing documents using {} ...", codecName); // error for different coloring
      long indexStartTime = System.currentTimeMillis();
      indexDocuments(writer, config, titles, vectorColumn, config.commitFreq);
      long indexTimeTaken = System.currentTimeMillis() - indexStartTime;
      if (codec instanceof CuVSCodec) {
        metrics.put("cuvs-indexing-time", indexTimeTaken);
      } else {
        metrics.put("hnsw-indexing-time", indexTimeTaken);
      }

      log.info("Time taken for index building (end to end): " + indexTimeTaken + " ms");

      try {
        if (hnswIndexWriter.getDirectory() instanceof FSDirectory
            && cuvsIndexWriter.getDirectory() instanceof FSDirectory) {
          Path indexPath = writer == cuvsIndexWriter ? Paths.get(config.cuvsIndexDirPath) : Paths.get(config.hnswIndexDirPath);
          long directorySize;
          try (var stream = Files.walk(indexPath, FileVisitOption.FOLLOW_LINKS)) {
            directorySize = stream.filter(p -> p.toFile().isFile())
                .mapToLong(p -> p.toFile().length())
                .sum();
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
        log.error("Failed to calculate directory size for {}", writer == cuvsIndexWriter ? config.cuvsIndexDirPath : config.hnswIndexDirPath,
            e);
      }
      log.info("Querying documents using {}...", codecName); // error for different coloring
      query(writer.getDirectory(), config, codec instanceof CuVSCodec, metrics, queryResults,
          readGroundTruthFile(config.groundTruthFile, config.numDocs));
    }

    String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(Calendar.getInstance().getTime());
    File results = new File("results");
    if (!results.exists()) {
      results.mkdir();
    }

    double minPrecision = 100;
    double maxPrecision = 0;
    double avgPrecision = 0;
    double minRecall = 100;
    double maxRecall = 0;
    double avgRecall = 0;

    for (QueryResult result : queryResults) {
      minPrecision = Math.min(minPrecision, result.precision);
      maxPrecision = Math.max(maxPrecision, result.precision);
      avgPrecision += result.precision;
      minRecall = Math.min(minRecall, result.recall);
      maxRecall = Math.max(maxRecall, result.recall);
      avgRecall += result.recall;
    }
    avgPrecision = avgPrecision / queryResults.size();
    avgRecall = avgRecall / queryResults.size();

    metrics.put("min-precision", minPrecision);
    metrics.put("max-precision", maxPrecision);
    metrics.put("avg-precision", avgPrecision);

    metrics.put("min-recall", minRecall);
    metrics.put("max-recall", maxRecall);
    metrics.put("avg-recall", avgRecall);

    String resultsJson = new ObjectMapper().writerWithDefaultPrettyPrinter()
        .writeValueAsString(Map.of("configuration", config, "metrics", metrics));

    if (config.saveResultsOnDisk) {
      writeCSV(queryResults, results.toString() + "/" + config.benchmarkID + "_neighbors_" + timeStamp + ".csv");
      FileUtils.write(
          new File(results.toString() + "/" + config.benchmarkID + "_benchmark_results_" + timeStamp + ".json"),
          resultsJson, Charset.forName("UTF-8"));
    }

    log.info("\n-----\nOverall metrics: " + metrics + "\nMetrics: \n" + resultsJson + "\n-----");
  }

  private static List<int[]> readGroundTruthFile(String groundTruthFile, int numRows) throws IOException {
    List<int[]> rst = new ArrayList<int[]>();
    if (groundTruthFile.endsWith("csv")) {
      for (String line : FileUtils.readFileToString(new File(groundTruthFile), "UTF-8").split("\n")) {
        rst.add(parseIntArrayFromStringArray(line));
      }
    } else if (groundTruthFile.endsWith("ivecs")) {
      rst = FBIvecsReader.readIvecs(groundTruthFile, numRows);
    } else {
      log.warn("Not parsing groundtruth file. Are you passing the correct file path?");
      System.exit(1);
    }
    return rst;
  }

  private static void readBaseFile(BenchmarkConfiguration config, List<String> titles, List<float[]> vectorColumn) {
    if (config.datasetFile.contains("fvecs")) {
      vectorColumn.addAll(FBIvecsReader.readFvecs(config.datasetFile, config.numDocs));
    } else if (config.datasetFile.contains("bvecs")) {
      vectorColumn.addAll(FBIvecsReader.readBvecs(config.datasetFile, config.numDocs));
    }
    titles.add(config.vectorColName);
  }

  private static void parseCSVFile(BenchmarkConfiguration config, List<String> titles, List<float[]> vectorColumn)
      throws IOException, CsvValidationException {
    InputStreamReader isr = null;
    ZipFile zipFile = null;
    if (config.datasetFile.endsWith(".zip")) {
      zipFile = new ZipFile(config.datasetFile);
      isr = new InputStreamReader(zipFile.getInputStream(zipFile.entries().nextElement()));
    } else if (config.datasetFile.endsWith(".gz")) {
      isr = new InputStreamReader(new GZIPInputStream(new FileInputStream(config.datasetFile)));
    } else {
      isr = new InputStreamReader(new FileInputStream(config.datasetFile));
    }

    try (CSVReader csvReader = new CSVReader(isr)) {
      String[] csvLine;
      int countOfDocuments = 0;
      while ((csvLine = csvReader.readNext()) != null) {
        if ((countOfDocuments++) == 0) // skip the first line of the file, it is a header
          continue;
        try {
          titles.add(csvLine[1]);
          vectorColumn.add(reduceDimensionVector(parseFloatArrayFromStringArray(csvLine[config.indexOfVector]),
              config.vectorDimension));
        } catch (Exception e) {
          System.out.print("#");
          countOfDocuments -= 1;
        }
        if (countOfDocuments % 1000 == 0)
          System.out.print(".");
        
        if (countOfDocuments == config.numDocs + 1)
          break;
      }
      System.out.println();
    }
    if (zipFile != null)
      zipFile.close();
  }


  private static void indexDocuments(IndexWriter writer, BenchmarkConfiguration config, List<String> titles,
      List<float[]> vecCol, int commitFrequency) throws IOException, InterruptedException {

    int threads = writer.getConfig().getCodec() instanceof CuVSCodec ? 1 : config.hnswThreads;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger docsIndexed = new AtomicInteger(0);
    AtomicInteger remainingDocs = new AtomicInteger(0);
    AtomicBoolean commitBeingCalled = new AtomicBoolean(false);

    for (int i = 0; i <= config.numDocs; i++) {
      final int index = i;
      pool.submit(() -> {
        Document document = new Document();
        document.add(new StringField("id", String.valueOf(index), Field.Store.YES));
        if (RESULTS_DEBUGGING)
          document.add(new StringField("title", titles.get(index), Field.Store.YES));
        document
            .add(new KnnFloatVectorField(config.vectorColName, vecCol.get(index), VectorSimilarityFunction.EUCLIDEAN));
        try {
          while (commitBeingCalled.get())
            ; // block until commit is over
          writer.addDocument(document);
          int docs = docsIndexed.incrementAndGet();
          remainingDocs.incrementAndGet();
          // if (docs % 100 == 0) log.info("Docs added: " + docs);

          synchronized (pool) {

            if ((docs % commitFrequency == 0) && !commitBeingCalled.get()) {
              log.info(docs + " Docs indexed. Commit called..." + index);
              if (commitBeingCalled.get() == false) {
                try {
                  commitBeingCalled.set(true);
                  writer.commit();
                  remainingDocs.set(0);
                  commitBeingCalled.set(false);
                } catch (IOException ex) {
                  ex.printStackTrace();
                }
              }
              log.info(docs + ": Done commit!");
            }
          }

        } catch (IOException ex) {
          ex.printStackTrace();
        }
      });

    }
    pool.shutdown();
    pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

    // HERE IS THE PROBLEM!
    if (remainingDocs.get() != 0) {
      log.info("{} Docs remaining. Calling commit.", remainingDocs.get());
      writer.commit();
    }

    writer.close();
  }

  static class QueryResult {
    @JsonProperty("codec")
    final String codec;
    @JsonProperty("query-id")
    final public int queryId;
    @JsonProperty("docs")
    final List<Integer> docs;
    @JsonProperty("ground-truth")
    final int[] groundTruth;
    @JsonProperty("scores")
    final List<Float> scores;
    @JsonProperty("latency")
    final double latencyMs;
    @JsonProperty("precision")
    double precision;
    @JsonProperty("recall")
    double recall;

    public QueryResult(String codec, int id, List<Integer> docs, int[] groundTruth, List<Float> scores,
        double latencyMs) {
      this.codec = codec;
      this.queryId = id;
      this.docs = docs;
      this.groundTruth = groundTruth;
      this.scores = scores;
      this.latencyMs = latencyMs;
      calculatePrecisionAndRecall();
    }

    private void calculatePrecisionAndRecall() {

      ArrayList<Integer> topKGroundtruthValues = new ArrayList<Integer>();
      for (int i = 0; i < docs.size(); i++) { // docs.size() is the topK value
        topKGroundtruthValues.add(groundTruth[i]);
      }

      int precisionMatches = 0;
      for (int g : groundTruth) {
        if (docs.contains(g)) {
          precisionMatches += 1;
        }
      }
      this.precision = ((float) precisionMatches / (float) docs.size()) * 100.0;

      Set<Integer> matchingRecallValues = docs.stream().distinct().filter(topKGroundtruthValues::contains)
          .collect(Collectors.toSet());

      this.recall = ((float) matchingRecallValues.size() / (float) topKGroundtruthValues.size()) * 100.0;
    }

    @Override
    public String toString() {
      try {
        return new ObjectMapper().writeValueAsString(this);
      } catch (JsonProcessingException e) {
        throw new RuntimeException("Problem with converting the result to a string", e);
      }
    }
  }

  private static void query(Directory directory, BenchmarkConfiguration config, boolean useCuVS,
      Map<String, Object> metrics, List<QueryResult> queryResults, List<int[]> groundTruth) {
    try (IndexReader indexReader = DirectoryReader.open(directory)) {
      IndexSearcher indexSearcher = new IndexSearcher(indexReader);
      List<Pair<Integer, float[]>> queries = new ArrayList<Pair<Integer, float[]>>();

      int i = 0;
      if (config.queryFile.endsWith(".csv")) {
        for (String line : FileUtils.readFileToString(new File(config.queryFile), "UTF-8").split("\n")) {
          float queryVector[] = reduceDimensionVector(parseFloatArrayFromStringArray(line), config.vectorDimension);
          queries.add(Pair.of(i++, queryVector));
          if (config.numQueriesToRun == i) {
            break;           
          }
        }
      } else if (config.queryFile.contains("fvecs")) {
        ArrayList<float[]> qries = FBIvecsReader.readFvecs(config.queryFile, -1);
        for (int j = 0; j < qries.size(); j++) {
          queries.add(Pair.of(i++, qries.get(j)));
          if (config.numQueriesToRun == i) {
            break;           
          }
        }
      } else if (config.queryFile.contains("bvecs")) {
        ArrayList<float[]> qries = FBIvecsReader.readBvecs(config.queryFile, -1);
        for (int j = 0; j < qries.size(); j++) {
          queries.add(Pair.of(i++, qries.get(j)));
          if (config.numQueriesToRun == i) {
            break;           
          }
        }
      } else {
        log.warn("Not parsing any query file. Have you passed the correct query file path?");
      }
      
      if(queries.size() < config.numQueriesToRun) {
        log.warn("Number of queries less then the instructed queries to run.");
      }
      log.info("Queries to run: " + queries.size());

      int qThreads = config.queryThreads;
      if (useCuVS)
        qThreads = 1;
      ExecutorService pool = Executors.newFixedThreadPool(qThreads);
      AtomicInteger queriesFinished = new AtomicInteger(0);
      ConcurrentHashMap<Integer, Double> queryLatencies = new ConcurrentHashMap<Integer, Double>();

      long startTime = System.currentTimeMillis();
      for (Pair<Integer, float[]> queryPair : queries) {
        final Pair<Integer, float[]> pair = queryPair;
        pool.submit(() -> {
          int queryId = pair.getLeft();
          KnnFloatVectorQuery query;
          if (useCuVS) {
            query = new CuVSKnnFloatVectorQuery(config.vectorColName, pair.getRight(), config.topK, config.cagraITopK,
                config.cagraSearchWidth);
          } else {
            query = new KnnFloatVectorQuery(config.vectorColName, pair.getRight(), config.topK);
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
          queryLatencies.put(queryId, searchTimeTakenMs);
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
          
          QueryResult result = new QueryResult(useCuVS ? "lucene_cuvs" : "lucene_hnsw", queryId,
              useCuVS ? neighbors.reversed() : neighbors, groundTruth.get(queryId),
              useCuVS ? scores.reversed() : scores, searchTimeTakenMs);
          queryResults.add(result);
        });
      }

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
    }
  }

  private static Lucene101Codec getHnswCodec(BenchmarkConfiguration config) {
    return new Lucene101Codec(Mode.BEST_SPEED) {
      @Override
      public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
        KnnVectorsFormat knnFormat = new Lucene99HnswVectorsFormat(config.hnswMaxConn, config.hnswBeamWidth);
        return new HighDimensionKnnVectorsFormat(knnFormat, config.vectorDimension);
      }
    };
  }

  private static float[] parseFloatArrayFromStringArray(String str) {
    float[] titleVector = ArrayUtils.toPrimitive(
        Arrays.stream(str.replace("[", "").replace("]", "").split(", ")).map(Float::valueOf).toArray(Float[]::new));
    return titleVector;
  }

  private static int[] parseIntArrayFromStringArray(String str) {
    String[] s = str.split(", ");
    int[] titleVector = new int[s.length];
    for (int i = 0; i < s.length; i++) {
      titleVector[i] = Integer.parseInt(s[i].trim());
    }
    return titleVector;
  }
  
  public static float[] reduceDimensionVector(float[] vector, int dim) {
    float out[] = new float[dim];
    for (int i = 0; i < dim && i < vector.length; i++)
      out[i] = vector[i];
    return out;
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

  private static void writeCSV(List<QueryResult> list, String filename) throws Exception {
    JsonNode jsonTree = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(list));
    CsvSchema.Builder csvSchemaBuilder = CsvSchema.builder();
    JsonNode firstObject = jsonTree.elements().next();
    firstObject.fieldNames().forEachRemaining(fieldName -> {
      csvSchemaBuilder.addColumn(fieldName);
    });
    CsvSchema csvSchema = csvSchemaBuilder.build().withHeader();
    CsvMapper csvMapper = new CsvMapper();
    csvMapper.writerFor(JsonNode.class).with(csvSchema).writeValue(new File(filename), jsonTree);
  }
}