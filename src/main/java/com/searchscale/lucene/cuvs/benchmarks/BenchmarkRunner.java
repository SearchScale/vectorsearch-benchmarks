package com.searchscale.lucene.cuvs.benchmarks;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BenchmarkRunner {
    
    private static final ObjectMapper json = new ObjectMapper();
    
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: BenchmarkRunner <config_file>");
            System.exit(1);
        }
        
        String configFile = args[0];
        try {
            runBenchmark(configFile);
        } catch (Exception e) {
            System.err.println("Benchmark failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void runBenchmark(String configFile) throws Exception {
        String configJson = new String(Files.readAllBytes(Paths.get(configFile)));
        Map<String, Object> config = json.readValue(configJson, Map.class);
        
        String runId = (String) config.get("run_id");
        String algorithm = (String) config.get("algorithm");
        
        System.out.println("Starting benchmark: " + runId + " (" + algorithm + ")");
        
        String sweepId = (String) config.get("sweep_id");
        String resultsDir = "results/raw/" + sweepId + "/" + runId;
        new File(resultsDir).mkdirs();
        MetricsCollector metrics = new MetricsCollector();
        int samplingRate = (Integer) config.getOrDefault("samplingRate", 2);
        metrics.startSampling(samplingRate);
        
            try {
                Map<String, Object> results = runBenchmarkCore(config);
                metrics.stopSampling();
                saveResults(resultsDir, runId, config, results, metrics);
                
                // Clean up index directories after benchmark
                cleanupIndexDirectories();
                
                System.out.println("Benchmark completed: " + runId);
            } catch (Exception e) {
                metrics.stopSampling();
                
                // Clean up index directories even on failure
                cleanupIndexDirectories();
                
                throw e;
            }
    }
    
    private static Map<String, Object> runBenchmarkCore(Map<String, Object> config) throws Exception {
        String algorithm = (String) config.get("algorithm");
        
        if ("CAGRA_HNSW".equals(algorithm)) {
            return runCagraBenchmark(config);
        } else if ("LUCENE_HNSW".equals(algorithm)) {
            return runLuceneBenchmark(config);
        } else {
            throw new IllegalArgumentException("Unknown algorithm: " + algorithm);
        }
    }
    
    private static Map<String, Object> runCagraBenchmark(Map<String, Object> config) throws Exception {
        String tempConfigFile = createTempConfigFile(config);
        
        try {
            try {
                LuceneCuvsBenchmarks.main(new String[]{tempConfigFile});
            } catch (Throwable t) {
                throw new Exception("Benchmark execution failed", t);
            }
            
                   return Map.of("status", "completed");
               } finally {
                   new File(tempConfigFile).delete();
               }
    }
    
    private static Map<String, Object> runLuceneBenchmark(Map<String, Object> config) throws Exception {
        String tempConfigFile = createTempConfigFile(config);
        
        try {
            try {
                LuceneCuvsBenchmarks.main(new String[]{tempConfigFile});
            } catch (Throwable t) {
                throw new Exception("Benchmark execution failed", t);
            }
            
                   return Map.of("status", "completed");
               } finally {
                   new File(tempConfigFile).delete();
               }
    }
    
    private static String createTempConfigFile(Map<String, Object> config) throws IOException {
        Map<String, Object> benchConfig = new java.util.LinkedHashMap<>();
        
        benchConfig.put("numDocs", config.getOrDefault("numDocs", 1000000));
        benchConfig.put("numQueriesToRun", config.getOrDefault("numQueriesToRun", 1000));
        benchConfig.put("numWarmUpQueries", config.getOrDefault("numWarmUpQueries", 20));
        benchConfig.put("topK", config.getOrDefault("topK", 100));
        benchConfig.put("efSearch", config.getOrDefault("efSearch", 150));
        benchConfig.put("queryThreads", config.getOrDefault("queryThreads", 1));
        benchConfig.put("flushFreq", config.getOrDefault("flushFreq", 500000));
        benchConfig.put("numIndexThreads", config.getOrDefault("numIndexThreads", 8));
        
        Map<String, Object> datasetInfo = (Map<String, Object>) config.get("dataset_info");
        benchConfig.put("datasetFile", datasetInfo.get("base_file"));
        benchConfig.put("queryFile", datasetInfo.get("query_file"));
        benchConfig.put("groundTruthFile", datasetInfo.get("ground_truth_file"));
        benchConfig.put("vectorDimension", datasetInfo.get("vector_dimension"));
        
        Map<String, Object> params = (Map<String, Object>) config.get("parameters");
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!"samplingRate".equals(entry.getKey())) {
                benchConfig.put(entry.getKey(), entry.getValue());
            }
        }
        
        benchConfig.put("algoToRun", config.get("algorithm"));
        benchConfig.put("benchmarkID", config.get("run_id"));
        benchConfig.put("resultsDirectory", "results/raw/" + config.get("sweep_id") + "/" + config.get("run_id"));
        benchConfig.put("saveResultsOnDisk", true);
        
        benchConfig.put("hasColNames", false);
        benchConfig.put("vectorColName", "vector");
        benchConfig.put("hnswIndexDirPath", "hnswIndex");
        benchConfig.put("cuvsIndexDirPath", "cuvsIndex");
        benchConfig.put("cleanIndexDirectory", true);
        benchConfig.put("loadVectorsInMemory", false);
        benchConfig.put("skipIndexing", false);
        
        String tempFile = "temp_config_" + config.get("run_id") + ".json";
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(json.writeValueAsString(benchConfig));
        }
        
        return tempFile;
    }
    
    private static void saveResults(String resultsDir, String runId, 
                                  Map<String, Object> config, 
                                  Map<String, Object> results, 
                                  MetricsCollector metrics) throws IOException {
        
        copyDetailedResults(config, resultsDir);
        
        String resultsFile = resultsDir + "/results.json";
        try (FileWriter writer = new FileWriter(resultsFile)) {
            Map<String, Object> fullResults = Map.of(
                "configuration", config,
                "results", results,
                "run_id", runId,
                "timestamp", System.currentTimeMillis()
            );
            writer.write(json.writeValueAsString(fullResults));
        }
        
        String metricsFile = resultsDir + "/metrics.json";
        try (FileWriter writer = new FileWriter(metricsFile)) {
            writer.write(json.writeValueAsString(metrics.getAllMetrics()));
        }
        
        String memoryFile = resultsDir + "/memory_metrics.json";
        try (FileWriter writer = new FileWriter(memoryFile)) {
            writer.write(json.writeValueAsString(metrics.getMemoryMetrics()));
        }
        
        String cpuFile = resultsDir + "/cpu_metrics.json";
        try (FileWriter writer = new FileWriter(cpuFile)) {
            writer.write(json.writeValueAsString(metrics.getCpuMetrics()));
        }
    }
    
    private static class MetricsCollector {
        private ScheduledExecutorService scheduler;
        private List<Map<String, Object>> memorySamples;
        private List<Map<String, Object>> cpuSamples;
        private boolean sampling = false;
        private OperatingSystemMXBean osBean;
        
        public MetricsCollector() {
            scheduler = Executors.newScheduledThreadPool(1);
            memorySamples = new ArrayList<>();
            cpuSamples = new ArrayList<>();
            osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        }
        
        public void startSampling(int intervalSeconds) {
            if (sampling) return;
            
            sampling = true;
            memorySamples.clear();
            cpuSamples.clear();
            
            scheduler.scheduleAtFixedRate(this::collectMetrics, 0, intervalSeconds, TimeUnit.SECONDS);
        }
        
        public void stopSampling() {
            if (!sampling) return;
            
            sampling = false;
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        private void collectMetrics() {
            if (!sampling) return;
            
            long timestamp = System.currentTimeMillis();
            
            Map<String, Object> memorySample = new HashMap<>();
            memorySample.put("timestamp", timestamp);
            
            Runtime rt = Runtime.getRuntime();
            long heapUsed = rt.totalMemory() - rt.freeMemory();
            long heapTotal = rt.totalMemory();
            long heapMax = rt.maxMemory();
            
            memorySample.put("heapUsed", heapUsed);
            memorySample.put("heapTotal", heapTotal);
            memorySample.put("heapMax", heapMax);
            
            long offHeapUsed = 0;
            try {
                for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
                    String name = pool.getName().toLowerCase();
                    if (name.contains("direct") || name.contains("mapped") || 
                        name.contains("code") || name.contains("metaspace")) {
                        offHeapUsed += pool.getUsage().getUsed();
                    }
                }
            } catch (Exception e) {
                offHeapUsed = 0;
            }
            
            memorySample.put("offHeapUsed", offHeapUsed);
            memorySample.put("totalMemoryUsed", heapUsed + offHeapUsed);
            memorySamples.add(memorySample);
            
            Map<String, Object> cpuSample = new HashMap<>();
            cpuSample.put("timestamp", timestamp);
            
            try {
                cpuSample.put("cpuUsagePercent", osBean.getProcessCpuLoad() * 100);
                cpuSample.put("systemCpuUsagePercent", osBean.getSystemCpuLoad() * 100);
                cpuSample.put("availableProcessors", osBean.getAvailableProcessors());
            } catch (Exception e) {
                cpuSample.put("cpuUsagePercent", 0.0);
                cpuSample.put("systemCpuUsagePercent", 0.0);
                cpuSample.put("availableProcessors", rt.availableProcessors());
            }
            
            cpuSamples.add(cpuSample);
        }
        
        public Map<String, Object> getAllMetrics() {
            Map<String, Object> result = new HashMap<>();
            result.put("memory_samples", memorySamples);
            result.put("cpu_samples", cpuSamples);
            return result;
        }
        
        public Map<String, Object> getMemoryMetrics() {
            Map<String, Object> result = new HashMap<>();
            result.put("memory_samples", memorySamples);
            return result;
        }
        
        public Map<String, Object> getCpuMetrics() {
            Map<String, Object> result = new HashMap<>();
            result.put("cpu_samples", cpuSamples);
            return result;
        }
    }
    
    private static void copyDetailedResults(Map<String, Object> config, String resultsDir) throws IOException {
        File dir = new File(resultsDir);
        File[] files = dir.listFiles((d, name) -> name.contains("_benchmark_results_") && name.endsWith(".json"));
        
        if (files != null && files.length > 0) {
            File latest = files[0];
            for (File f : files) {
                if (f.lastModified() > latest.lastModified()) {
                    latest = f;
                }
            }
            
            String content = new String(Files.readAllBytes(latest.toPath()));
            try (FileWriter writer = new FileWriter(resultsDir + "/detailed_results.json")) {
                writer.write(content);
            }
        }
    }
    
    private static void cleanupIndexDirectories() {
        try {
            // Clean up HNSW index directory
            File hnswDir = new File("hnswIndex");
            if (hnswDir.exists()) {
                deleteDirectory(hnswDir);
                System.out.println("Cleaned up hnswIndex directory");
            }
            
            // Clean up CUVS index directory
            File cuvsDir = new File("cuvsIndex");
            if (cuvsDir.exists()) {
                deleteDirectory(cuvsDir);
                System.out.println("Cleaned up cuvsIndex directory");
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to clean up index directories: " + e.getMessage());
        }
    }
    
    private static void deleteDirectory(File dir) throws IOException {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        if (!dir.delete()) {
            throw new IOException("Failed to delete: " + dir.getAbsolutePath());
        }
    }
}
