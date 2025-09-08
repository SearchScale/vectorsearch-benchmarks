package com.searchscale.lucene.cuvs.benchmarks.bench;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Materializes sweep configurations into individual run configurations
 */
public class Materializer {
    
    private final ObjectMapper json = new ObjectMapper();
    
    private static final Set<String> LUCENE_ONLY = Set.of(
        "hnswMaxConn", "hnswBeamWidth");
    
    private static final Set<String> CAGRA_ONLY = Set.of(
        "cagraGraphDegree", "cagraIntermediateGraphDegree",
        "cuvsWriterThreads", "cagraHnswLayers",
        "cagraITopK", "cagraSearchWidth");
    
    /**
     * Materialize a sweep configuration into individual run configs
     */
    public List<MaterializedRun> materializeSweep(SweepLoader.SweepConfig sweepConfig) {
        List<Map<String, Object>> combinations = new SweepLoader().expandMatrix(sweepConfig.matrix);
        List<MaterializedRun> runs = new ArrayList<>();
        
        for (Map<String, Object> combo : combinations) {
            Map<String, Object> materialized = new LinkedHashMap<>(sweepConfig.base);
            materialized.putAll(combo);
            
            // Drop parameters that don't belong to the selected algorithm
            pruneByAlgo(materialized);
            
            // Ensure flushFreq is valid for Lucene IW config: ≥ 2
            ensureValidFlushFreq(materialized);
            
            // Validate topK ≤ GT depth before running (fast fail)
            validateTopK(materialized);
            
            // Generate stable run ID
            String runId = generateRunId(materialized);
            
            // Generate friendly name
            String friendlyName = generateFriendlyName(materialized);
            
            runs.add(new MaterializedRun(runId, friendlyName, materialized, sweepConfig.meta));
        }
        
        return runs;
    }
    
    /**
     * Materialize a single configuration (not a sweep)
     */
    public MaterializedRun materializeConfig(Map<String, Object> config, Map<String, Object> meta) {
        // Ensure flushFreq is valid
        ensureValidFlushFreq(config);
        
        // Validate topK
        validateTopK(config);
        
        // Generate run ID and name
        String runId = generateRunId(config);
        String friendlyName = generateFriendlyName(config);
        
        return new MaterializedRun(runId, friendlyName, config, meta);
    }
    
    private void pruneByAlgo(Map<String, Object> config) {
        Object algo = config.get("algoToRun");
        if (algo == null) return;
        
        String algoStr = String.valueOf(algo);
        if ("LUCENE_HNSW".equalsIgnoreCase(algoStr)) {
            // Drop CAGRA keys
            List<String> drop = CAGRA_ONLY.stream().filter(config::containsKey).toList();
            if (!drop.isEmpty()) {
                System.err.println("[WARN] Dropping CAGRA-only keys for LUCENE_HNSW: " + drop);
                drop.forEach(config::remove);
            }
        } else if ("CAGRA_HNSW".equalsIgnoreCase(algoStr)) {
            // Drop Lucene keys
            List<String> drop = LUCENE_ONLY.stream().filter(config::containsKey).toList();
            if (!drop.isEmpty()) {
                System.err.println("[WARN] Dropping Lucene-only keys for CAGRA_HNSW: " + drop);
                drop.forEach(config::remove);
            }
        }
    }
    
    private void ensureValidFlushFreq(Map<String, Object> config) {
        Object ffObj = config.get("flushFreq");
        int ff = (ffObj instanceof Number) ? ((Number) ffObj).intValue()
            : (ffObj != null ? Integer.parseInt(String.valueOf(ffObj)) : 0);
        if (ff < 2) {
            config.put("flushFreq", 2);
        }
    }
    
    private void validateTopK(Map<String, Object> config) {
        Object topK = config.get("topK");
        if (topK == null) {
            throw new IllegalArgumentException("topK missing");
        }
        int k = (topK instanceof Number) ? ((Number) topK).intValue() : Integer.parseInt(topK.toString());
        if (k <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }
        // TODO: Add ground truth depth validation if needed
    }
    
    private String generateRunId(Map<String, Object> config) {
        try {
            String canonicalJson = json.writeValueAsString(config);
            String codeStamp = getCodeStamp();
            String input = canonicalJson + "|" + codeStamp;
            
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate run ID", e);
        }
    }
    
    private String generateFriendlyName(Map<String, Object> config) {
        List<String> parts = new ArrayList<>();
        
        // Dataset short name
        String dataset = getDatasetShortName(config);
        parts.add(dataset);
        
        // Algorithm
        String algo = String.valueOf(config.getOrDefault("algoToRun", "ALG"));
        parts.add(algo.toLowerCase());
        
        // Key parameters
        addIfPresent(parts, "cagraGraphDegree", config);
        addIfPresent(parts, "cagraIntermediateGraphDegree", config);
        addIfPresent(parts, "hnswMaxConn", config);
        addIfPresent(parts, "hnswBeamWidth", config);
        addIfPresent(parts, "topK", config);
        addIfPresent(parts, "numIndexThreads", config);
        addIfPresent(parts, "queryThreads", config);
        
        return String.join("_", parts);
    }
    
    private String getDatasetShortName(Map<String, Object> config) {
        String datasetFile = String.valueOf(config.getOrDefault("datasetFile", "ds"));
        String[] parts = datasetFile.replace('\\', '/').split("/");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "-" + parts[parts.length - 1];
        }
        return parts[parts.length - 1];
    }
    
    private void addIfPresent(List<String> parts, String key, Map<String, Object> config) {
        if (config.containsKey(key)) {
            parts.add(key + config.get(key));
        }
    }
    
    private String getCodeStamp() {
        // Include JVM info for reproducibility
        return System.getProperty("java.vendor") + " " + System.getProperty("java.version");
    }
    
    /**
     * Represents a materialized run configuration
     */
    public static class MaterializedRun {
        public final String runId;
        public final String friendlyName;
        public final Map<String, Object> config;
        public final Map<String, Object> meta;
        public final String createdAt;
        
        public MaterializedRun(String runId, String friendlyName, Map<String, Object> config, Map<String, Object> meta) {
            this.runId = runId;
            this.friendlyName = friendlyName;
            this.config = config;
            this.meta = meta;
            this.createdAt = Instant.now().toString();
        }
        
        public String getDataset() {
            return String.valueOf(meta.getOrDefault("dataset", 
                config.getOrDefault("vectorDimension", "unknown")));
        }
        
        public String getAlgorithm() {
            return String.valueOf(config.getOrDefault("algoToRun", "UNKNOWN"));
        }
        
        public Map<String, Object> getParams() {
            // Return only the parameters that differ from base config
            Map<String, Object> params = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : config.entrySet()) {
                String key = entry.getKey();
                if (!isBaseParameter(key)) {
                    params.put(key, entry.getValue());
                }
            }
            return params;
        }
        
        private boolean isBaseParameter(String key) {
            return Set.of(
                "datasetFile", "queryFile", "groundTruthFile", "numDocs", "vectorDimension",
                "numWarmUpQueries", "numQueriesToRun", "topK", "createIndexInMemory",
                "cleanIndexDirectory", "saveResultsOnDisk", "queryThreads", "numIndexThreads",
                "loadVectorsInMemory", "skipIndexing", "algoToRun", "cuvsIndexDirPath", "hnswIndexDirPath"
            ).contains(key);
        }
    }
}
