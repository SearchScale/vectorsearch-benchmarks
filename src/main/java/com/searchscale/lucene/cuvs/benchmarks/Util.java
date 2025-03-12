package com.searchscale.lucene.cuvs.benchmarks;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.mapdb.IndexTreeList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;

public class Util {

  private static final Logger log = LoggerFactory.getLogger(Util.class.getName());
  public static final int DEFAULT_BUFFER_SIZE = 65536;

  public static void parseCSVFile(BenchmarkConfiguration config, List<String> titles, List<float[]> vectors)
      throws IOException, CsvValidationException {
    InputStreamReader isr = null;
    ZipFile zipFile = null;

    if (config.datasetFile.endsWith(".zip")) {
      zipFile = new ZipFile(config.datasetFile);
      isr = new InputStreamReader(zipFile.getInputStream(zipFile.entries().nextElement()));
    } else if (config.datasetFile.endsWith(".gz")) {
      var fis = new FileInputStream(config.datasetFile);
      var bis = new BufferedInputStream(fis, DEFAULT_BUFFER_SIZE);
      isr = new InputStreamReader(new GZIPInputStream(bis));
    } else {
      var fis = new FileInputStream(config.datasetFile);
      var bis = new BufferedInputStream(fis, DEFAULT_BUFFER_SIZE);
      isr = new InputStreamReader(bis);
    }

    try (CSVReader csvReader = new CSVReader(isr)) {
      String[] csvLine;
      int countOfDocuments = 0;
      while ((csvLine = csvReader.readNext()) != null) {
        if ((countOfDocuments++) == 0) // skip the first line of the file, it is a header
          continue;
        try {
          titles.add(csvLine[1]);
          vectors.add(Util.parseFloatArrayFromStringArray(csvLine[config.indexOfVector]));
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

  public static void writeCSV(List<QueryResult> list, String filename) throws Exception {

    if (list.size() == 0) {
      log.warn("@@@ QueryResult list IS EMPTY!. Not generating the csv file. @@@");
      return;
    }

    JsonNode jsonTree = newObjectMapper().readTree(newObjectMapper().writeValueAsString(list));
    CsvSchema.Builder csvSchemaBuilder = CsvSchema.builder();
    JsonNode firstObject = jsonTree.elements().next();
    firstObject.fieldNames().forEachRemaining(fieldName -> {
      csvSchemaBuilder.addColumn(fieldName);
    });
    CsvSchema csvSchema = csvSchemaBuilder.build().withHeader();
    CsvMapper csvMapper = new CsvMapper();
    csvMapper.writerFor(JsonNode.class).with(csvSchema).writeValue(new File(filename), jsonTree);
  }

  static ObjectMapper newObjectMapper() {
    var objectMapper = new ObjectMapper();
    objectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    objectMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    return objectMapper;
  }

  public static float[] parseFloatArrayFromStringArray(String str) {
    float[] arr = ArrayUtils.toPrimitive(
        Arrays.stream(str.replace("[", "").replace("]", "").split(", ")).map(Float::valueOf).toArray(Float[]::new));
    return arr;
  }

  public static int[] parseIntArrayFromStringArray(String str) {
    String[] s = str.split(", ");
    int[] titleVector = new int[s.length];
    for (int i = 0; i < s.length; i++) {
      titleVector[i] = Integer.parseInt(s[i].trim());
    }
    return titleVector;
  }

  public static List<int[]> readGroundTruthFile(String groundTruthFile) throws IOException {
    List<int[]> rst = new ArrayList<int[]>();
    if (groundTruthFile.endsWith("csv")) {
      log.info("Seems like a csv groundtruth file. Reading ...");
      for (String line : FileUtils.readFileToString(new File(groundTruthFile), "UTF-8").split("\n")) {
        rst.add(Util.parseIntArrayFromStringArray(line));
      }
    } else if (groundTruthFile.endsWith("ivecs")) {
      log.info("Seems like a ivecs groundtruth file. Reading ...");
      rst = FBIvecsReader.readIvecs(groundTruthFile, -1);
    } else {
      throw new RuntimeException("Not parsing groundtruth file and stopping. Are you passing the correct file path?");
    }
    log.info("{} number of entries in the groundtruth file.", rst.size());
    return rst;
  }

  public static void readBaseFile(BenchmarkConfiguration config, List<String> titles, List<float[]> vectors) {
    if (config.datasetFile.contains("fvecs")) {
      log.info("Seems like an fvecs base file. Reading ...");
      FBIvecsReader.readFvecs(config.datasetFile, config.numDocs, vectors);
    } else if (config.datasetFile.contains("bvecs")) {
      log.info("Seems like an bvecs base file. Reading ...");
      FBIvecsReader.readBvecs(config.datasetFile, config.numDocs, vectors);
    }
    titles.add(config.vectorColName);
  }

  public static void preCheck(BenchmarkConfiguration config) {
    log.info("Doing prechecks ...");

    if (!new File(config.datasetFile).exists()) {
      throw new RuntimeException(config.datasetFile + "is not found. Not proceeding.");
    }

    if (!new File(config.queryFile).exists()) {
      throw new RuntimeException(config.queryFile + "is not found. Not proceeding.");
    }

    if (!new File(config.groundTruthFile).exists()) {
      throw new RuntimeException(config.groundTruthFile + " is not found. Not proceeding.");
    }
  }

  /**
   * Adds recall values to the metrics map
   * 
   * @param queryResults
   * @param metrics
   */
  public static void calculateRecallAccuracy(List<QueryResult> queryResults, Map<String, Object> metrics,
      boolean useCuVS) {

    double totalRecall = 0;
    for (QueryResult result : queryResults) {
      totalRecall += result.getRecall();
    }

    double percentRecallAccuracy = (totalRecall / (double)queryResults.size()) * 100.0;
    metrics.put((useCuVS ? "cuvs" : "hnsw") + "-recall-accuracy", percentRecallAccuracy);
  }
}
