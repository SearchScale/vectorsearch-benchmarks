package com.searchscale.lucene.cuvs.benchmarks.bench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the catalog of benchmark runs stored in runs/catalog.jsonl
 */
public class Catalog {
    
    private final Path runsDir;
    private final Path catalogFile;
    private final ObjectMapper json = new ObjectMapper();
    
    public Catalog(Path runsDir) {
        this.runsDir = runsDir;
        this.catalogFile = runsDir.resolve("catalog.jsonl");
    }
    
    /**
     * List runs with optional filtering
     */
    public void listRuns(String whereClause) throws IOException {
        List<RunEntry> runs = loadRuns();
        
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            runs = filterRuns(runs, whereClause);
        }
        
        if (runs.isEmpty()) {
            System.out.println("No runs found matching criteria");
            return;
        }
        
        System.out.printf("%-12s %-20s %-15s %-12s %-20s%n", 
            "Run ID", "Name", "Dataset", "Algorithm", "Created");
        System.out.println("-".repeat(80));
        
        for (RunEntry run : runs) {
            System.out.printf("%-12s %-20s %-15s %-12s %-20s%n",
                run.runId,
                truncate(run.name, 20),
                truncate(run.dataset, 15),
                run.algo,
                formatTimestamp(run.createdAt)
            );
        }
        
        System.out.printf("%nTotal: %d runs%n", runs.size());
    }
    
    /**
     * Get a specific run by ID
     */
    public RunEntry getRun(String runId) throws IOException {
        List<RunEntry> runs = loadRuns();
        return runs.stream()
            .filter(r -> r.runId.equals(runId))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Add a new run to the catalog
     */
    public void addRun(RunEntry run) throws IOException {
        ensureCatalogExists();
        
        String line = json.writeValueAsString(run) + System.lineSeparator();
        Files.writeString(catalogFile, line, StandardCharsets.UTF_8, 
            java.nio.file.StandardOpenOption.APPEND);
    }
    
    /**
     * Update an existing run in the catalog (replaces the entire catalog file)
     */
    public void updateRun(RunEntry updatedRun) throws IOException {
        List<RunEntry> runs = loadRuns();
        
        // Find and replace the existing entry
        boolean found = false;
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).runId.equals(updatedRun.runId)) {
                runs.set(i, updatedRun);
                found = true;
                break;
            }
        }
        
        if (!found) {
            // If not found, add as new entry
            runs.add(updatedRun);
        }
        
        // Rewrite the entire catalog file
        ensureCatalogExists();
        Files.write(catalogFile, new byte[0]); // Clear the file
        
        for (RunEntry run : runs) {
            String line = json.writeValueAsString(run) + System.lineSeparator();
            Files.writeString(catalogFile, line, StandardCharsets.UTF_8, 
                java.nio.file.StandardOpenOption.APPEND);
        }
    }
    
    /**
     * Get runs by dataset and algorithm
     */
    public List<RunEntry> getRunsByDatasetAndAlgo(String dataset, String algo) throws IOException {
        List<RunEntry> runs = loadRuns();
        return runs.stream()
            .filter(r -> r.dataset.equals(dataset) && r.algo.equals(algo))
            .collect(Collectors.toList());
    }
    
    /**
     * Get best runs by recall threshold
     */
    public Map<String, RunEntry> getBestRunsByRecall(String dataset, String algo, double recallThreshold) throws IOException {
        List<RunEntry> runs = getRunsByDatasetAndAlgo(dataset, algo);
        
        // Group by topK and find best indexing time and query time for each
        Map<String, RunEntry> bestRuns = new HashMap<>();
        
        runs.stream()
            .filter(r -> r.recall >= recallThreshold)
            .collect(Collectors.groupingBy(r -> String.valueOf(r.topK)))
            .forEach((topK, topKRuns) -> {
                // Best indexing time
                RunEntry bestIndexing = topKRuns.stream()
                    .min(Comparator.comparingDouble(r -> r.indexingTime))
                    .orElse(null);
                
                // Best query time
                RunEntry bestQuery = topKRuns.stream()
                    .min(Comparator.comparingDouble(r -> r.queryTime))
                    .orElse(null);
                
                if (bestIndexing != null) {
                    bestRuns.put(String.format("%s_%s_top%s_best_indexing", dataset, algo, topK), bestIndexing);
                }
                if (bestQuery != null) {
                    bestRuns.put(String.format("%s_%s_top%s_best_query", dataset, algo, topK), bestQuery);
                }
            });
        
        return bestRuns;
    }
    
    public List<RunEntry> loadRuns() throws IOException {
        if (!Files.exists(catalogFile)) {
            return new ArrayList<>();
        }
        
        return Files.lines(catalogFile, StandardCharsets.UTF_8)
            .filter(line -> !line.trim().isEmpty())
            .map(line -> {
                try {
                    return json.readValue(line, RunEntry.class);
                } catch (Exception e) {
                    System.err.println("Failed to parse catalog line: " + line);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    private List<RunEntry> filterRuns(List<RunEntry> runs, String whereClause) {
        // Simple filtering - can be enhanced with proper query parsing
        return runs.stream()
            .filter(run -> matchesFilter(run, whereClause))
            .collect(Collectors.toList());
    }
    
    private boolean matchesFilter(RunEntry run, String whereClause) {
        String clause = whereClause.toLowerCase();
        
        // Simple keyword matching - can be enhanced
        if (clause.contains("dataset=")) {
            String dataset = extractValue(clause, "dataset=");
            if (!run.dataset.toLowerCase().contains(dataset.toLowerCase())) {
                return false;
            }
        }
        
        if (clause.contains("algo=")) {
            String algo = extractValue(clause, "algo=");
            if (!run.algo.toLowerCase().contains(algo.toLowerCase())) {
                return false;
            }
        }
        
        return true;
    }
    
    private String extractValue(String clause, String key) {
        int start = clause.indexOf(key) + key.length();
        int end = clause.indexOf(' ', start);
        if (end == -1) end = clause.length();
        return clause.substring(start, end).replaceAll("['\"]", "");
    }
    
    private void ensureCatalogExists() throws IOException {
        Files.createDirectories(runsDir);
        if (!Files.exists(catalogFile)) {
            Files.createFile(catalogFile);
        }
    }
    
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
    
    private String formatTimestamp(String timestamp) {
        try {
            Instant instant = Instant.parse(timestamp);
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(instant.atZone(java.time.ZoneId.systemDefault()));
        } catch (Exception e) {
            return timestamp;
        }
    }
    
    /**
     * Represents a catalog entry
     */
    public static class RunEntry {
        public String runId;
        public String name;
        public String dataset;
        public String algo;
        public Map<String, Object> params;
        public String createdAt;
        
        // Metrics (populated after run completion)
        public double indexingTime = -1;
        public double queryTime = -1;
        public double recall = -1;
        public int topK = -1;
        public double qps = -1;
        public double meanLatency = -1;
        
        public RunEntry() {}
        
        public RunEntry(String runId, String name, String dataset, String algo, 
                       Map<String, Object> params, String createdAt) {
            this.runId = runId;
            this.name = name;
            this.dataset = dataset;
            this.algo = algo;
            this.params = params;
            this.createdAt = createdAt;
        }
    }
}
