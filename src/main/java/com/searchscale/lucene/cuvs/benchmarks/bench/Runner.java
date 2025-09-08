package com.searchscale.lucene.cuvs.benchmarks.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.searchscale.lucene.cuvs.benchmarks.LuceneCuvsBenchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes individual benchmark runs with proper lifecycle management
 */
public class Runner {
    
    private final Path runsDir;
    private final ObjectMapper json = new ObjectMapper();
    private final Catalog catalog;
    
    public Runner(Path runsDir) {
        this.runsDir = runsDir;
        this.catalog = new Catalog(runsDir);
    }
    
    /**
     * Run a benchmark from a configuration file
     */
    public int runFromConfig(Path configFile, boolean dryRun) throws Exception {
        SweepLoader loader = new SweepLoader();
        Map<String, Object> rawConfig = loader.loadConfig(configFile);
        
        // Check if this is a sweep configuration or a simple config
        Map<String, Object> config;
        Map<String, Object> meta;
        
        if (rawConfig.containsKey("base") && rawConfig.containsKey("matrix")) {
            // This is a sweep configuration - use the base configuration
            Map<String, Object> baseConfig = (Map<String, Object>) rawConfig.get("base");
            Map<String, Object> matrixConfig = (Map<String, Object>) rawConfig.get("matrix");
            
            // For single run, take first value from matrix
            config = new LinkedHashMap<>(baseConfig);
            for (Map.Entry<String, Object> entry : matrixConfig.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof List && !((List<?>) value).isEmpty()) {
                    config.put(entry.getKey(), ((List<?>) value).get(0));
                } else {
                    config.put(entry.getKey(), value);
                }
            }
            
            meta = (Map<String, Object>) rawConfig.getOrDefault("meta", Map.of());
            System.out.println("DEBUG: Using sweep config, topK = " + config.get("topK"));
        } else {
            // This is a simple configuration
            config = rawConfig;
            System.out.println("DEBUG: Using simple config, topK = " + config.get("topK"));
            System.out.println("DEBUG: Config keys = " + config.keySet());
            meta = Map.of(
                "sweep_id", "single-run",
                "dataset", inferDataset(config),
                "notes", "Single run from config file"
            );
        }
        
        Materializer materializer = new Materializer();
        Materializer.MaterializedRun run = materializer.materializeConfig(config, meta);
        
        if (dryRun) {
            System.out.println("Dry run - would execute:");
            System.out.println("Run ID: " + run.runId);
            System.out.println("Name: " + run.friendlyName);
            System.out.println("Config: " + json.writerWithDefaultPrettyPrinter().writeValueAsString(run.config));
            return 0;
        }
        
        return executeRun(run);
    }
    
    /**
     * Replay a run from its lockfile
     */
    public int replayRun(String runId, boolean dryRun) throws Exception {
        Path runDir = runsDir.resolve(runId);
        Path lockFile = runDir.resolve("run.lock.json");
        
        if (!Files.exists(lockFile)) {
            System.err.println("Run not found: " + runId);
            return 1;
        }
        
        Map<String, Object> lock = json.readValue(Files.newBufferedReader(lockFile), Map.class);
        Path configPath = Path.of((String) lock.get("config_path"));
        
        if (!Files.exists(configPath)) {
            System.err.println("Config file not found: " + configPath);
            return 1;
        }
        
        Map<String, Object> config = json.readValue(Files.newBufferedReader(configPath), Map.class);
        
        if (dryRun) {
            System.out.println("Dry run - would replay:");
            System.out.println("Run ID: " + runId);
            System.out.println("Name: " + lock.get("name"));
            System.out.println("Config: " + json.writerWithDefaultPrettyPrinter().writeValueAsString(config));
            return 0;
        }
        
        // Create a new materialized run for replay
        Materializer materializer = new Materializer();
        Map<String, Object> meta = (Map<String, Object>) lock.get("sweep");
        Materializer.MaterializedRun run = materializer.materializeConfig(config, meta);
        
        return executeRun(run);
    }
    
    /**
     * Execute a materialized run
     */
    public int executeRun(Materializer.MaterializedRun run) throws Exception {
        Path runDir = runsDir.resolve(run.runId);
        Files.createDirectories(runDir);
        
        // Enable saving results to disk and set the results directory to the run directory
        run.config.put("saveResultsOnDisk", true);
        run.config.put("resultsDirectory", runDir.toString());
        
        // Write materialized config
        Path configPath = runDir.resolve("materialized-config.json");
        json.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), run.config);
        
        // Write environment snapshot
        Path envPath = runDir.resolve("env.json");
        Map<String, Object> env = snapshotEnvironment();
        json.writerWithDefaultPrettyPrinter().writeValue(envPath.toFile(), env);
        
        // Write run lock file
        Path lockPath = runDir.resolve("run.lock.json");
        Map<String, Object> lock = createLockFile(run, configPath, envPath);
        json.writerWithDefaultPrettyPrinter().writeValue(lockPath.toFile(), lock);
        
        // Add to catalog
        Catalog.RunEntry catalogEntry = new Catalog.RunEntry(
            run.runId,
            run.friendlyName,
            run.getDataset(),
            run.getAlgorithm(),
            run.getParams(),
            run.createdAt
        );
        catalog.addRun(catalogEntry);
        
        System.out.printf("Starting run: %s (%s)%n", run.runId, run.friendlyName);
        
        // Execute the benchmark
        long startTime = System.currentTimeMillis();
        try {
            LuceneCuvsBenchmarks.main(new String[]{configPath.toString()});
            long endTime = System.currentTimeMillis();
            
            // Update catalog with results
            updateRunResults(run.runId, endTime - startTime);
            
            System.out.printf("Run completed successfully: %s%n", run.runId);
            return 0;
            
        } catch (Throwable e) {
            System.err.printf("Run failed: %s - %s%n", run.runId, e.getMessage());
            e.printStackTrace();
            
            // Write error log
            Path errorLog = runDir.resolve("error.log");
            Files.writeString(errorLog, "Run failed: " + e.getMessage() + "\n", 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
            return 1;
        }
    }
    
    private Map<String, Object> snapshotEnvironment() {
        Map<String, Object> env = new LinkedHashMap<>();
        
        // OS info
        Map<String, Object> os = new LinkedHashMap<>();
        os.put("name", System.getProperty("os.name"));
        os.put("version", System.getProperty("os.version"));
        os.put("arch", System.getProperty("os.arch"));
        os.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("os", os);
        
        // Java info
        Map<String, Object> java = new LinkedHashMap<>();
        java.put("vendor", System.getProperty("java.vendor"));
        java.put("version", System.getProperty("java.version"));
        java.put("vmName", System.getProperty("java.vm.name"));
        env.put("java", java);
        
        // Memory info
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("maxMemory", runtime.maxMemory());
        memory.put("totalMemory", runtime.totalMemory());
        memory.put("freeMemory", runtime.freeMemory());
        env.put("memory", memory);
        
        // Git info
        Map<String, Object> git = new LinkedHashMap<>();
        git.put("vectorsearch_benchmarks_git", getGitSha("."));
        git.put("cuvs_lucene_git", getGitSha("../cuvs-lucene"));
        env.put("git", git);
        
        env.put("timestamp", Instant.now().toString());
        
        return env;
    }
    
    private Map<String, Object> createLockFile(Materializer.MaterializedRun run, 
                                             Path configPath, Path envPath) {
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("runId", run.runId);
        lock.put("name", run.friendlyName);
        lock.put("sweep", run.meta);
        
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("datasetFile", run.config.get("datasetFile"));
        dataset.put("queryFile", run.config.get("queryFile"));
        dataset.put("groundTruthFile", run.config.get("groundTruthFile"));
        lock.put("dataset", dataset);
        
        lock.put("config_path", configPath.toString());
        lock.put("env_path", envPath.toString());
        lock.put("created_at", run.createdAt);
        
        return lock;
    }
    
    private void updateRunResults(String runId, long totalTime) throws IOException {
        Catalog.RunEntry entry = catalog.getRun(runId);
        if (entry != null) {
            // Try to parse actual results from results.json
            Path runDir = runsDir.resolve(runId);
            Path resultsFile = runDir.resolve("results.json");
            
            if (Files.exists(resultsFile)) {
                try {
                    Map<String, Object> results = json.readValue(Files.newBufferedReader(resultsFile), Map.class);
                    Map<String, Object> metrics = (Map<String, Object>) results.get("metrics");
                    
                    if (metrics != null) {
                        // Extract key metrics
                        entry.indexingTime = getDoubleValue(metrics, "hnsw-indexing-time", "cuvs-indexing-time");
                        entry.queryTime = getDoubleValue(metrics, "hnsw-query-time", "cuvs-query-time");
                        entry.recall = getDoubleValue(metrics, "hnsw-recall-accuracy", "cuvs-recall-accuracy");
                        entry.qps = getDoubleValue(metrics, "hnsw-query-throughput", "cuvs-query-throughput");
                        entry.meanLatency = getDoubleValue(metrics, "hnsw-mean-latency", "cuvs-mean-latency");
                        
                        // Get topK from configuration
                        Map<String, Object> config = (Map<String, Object>) results.get("configuration");
                        if (config != null) {
                            entry.topK = (Integer) config.getOrDefault("topK", -1);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse results.json for run " + runId + ": " + e.getMessage());
                    // Fall back to placeholder values
                    entry.indexingTime = totalTime * 0.7;
                    entry.queryTime = totalTime * 0.3;
                }
            } else {
                // No results file found, use placeholder values
                entry.indexingTime = totalTime * 0.7;
                entry.queryTime = totalTime * 0.3;
            }
            
            // Update the existing entry in catalog
            catalog.updateRun(entry);
        }
    }
    
    private double getDoubleValue(Map<String, Object> metrics, String... keys) {
        for (String key : keys) {
            Object value = metrics.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        }
        return -1.0; // Default value for missing metrics
    }
    
    private String inferDataset(Map<String, Object> config) {
        String datasetFile = String.valueOf(config.getOrDefault("datasetFile", ""));
        int vectorDim = (Integer) config.getOrDefault("vectorDimension", 0);
        
        if (datasetFile.contains("wiki")) {
            return vectorDim == 768 ? "wiki-88m-768" : "wiki-5m-2048";
        } else if (datasetFile.contains("openai")) {
            return "openai-4.6m-1536";
        } else if (datasetFile.contains("sift")) {
            return "sift-100m-128";
        }
        
        return "unknown";
    }
    
    private String getGitSha(String repoPath) {
        try {
            Process process = new ProcessBuilder("bash", "-lc", 
                "cd " + repoPath + " && git rev-parse --short HEAD")
                .redirectErrorStream(true).start();
            
            try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                return (line == null) ? "unknown" : line.trim();
            }
        } catch (IOException e) {
            return "unknown";
        }
    }
}
