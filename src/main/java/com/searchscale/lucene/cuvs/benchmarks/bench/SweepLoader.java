package com.searchscale.lucene.cuvs.benchmarks.bench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads and validates sweep configurations from YAML files
 */
public class SweepLoader {
    
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    
    /**
     * Load and validate a sweep configuration
     */
    public SweepConfig loadSweep(Path sweepYaml) throws IOException {
        Map<String, Object> sweep = yaml.readValue(Files.newBufferedReader(sweepYaml), new TypeReference<>() {});
        
        SweepConfig config = new SweepConfig();
        config.meta = asMap(sweep.get("meta"));
        config.base = asMap(sweep.get("base"));
        config.matrix = asMap(sweep.get("matrix"));
        
        if (config.meta.containsKey("dataset")) {
            String datasetId = (String) config.meta.get("dataset");
            try {
                DatasetManager datasetManager = new DatasetManager();
                DatasetManager.DatasetConfig dataset = datasetManager.getDataset(datasetId);
                
                if (dataset != null && dataset.isAvailable()) {
                    if (!config.base.containsKey("datasetFile")) {
                        config.base.put("datasetFile", dataset.base_file);
                    }
                    if (!config.base.containsKey("queryFile")) {
                        config.base.put("queryFile", dataset.query_file);
                    }
                    if (!config.base.containsKey("groundTruthFile")) {
                        config.base.put("groundTruthFile", dataset.ground_truth_file);
                    }
                    if (!config.base.containsKey("numDocs")) {
                        config.base.put("numDocs", dataset.num_docs);
                    }
                    if (!config.base.containsKey("vectorDimension")) {
                        config.base.put("vectorDimension", dataset.vector_dimension);
                    }
                }
            } catch (IOException e) {
                System.err.println("Warning: Could not load dataset configuration: " + e.getMessage());
            }
        }
        
        validateSweep(config);
        return config;
    }
    
    /**
     * Load a simple configuration file (not a sweep)
     */
    public Map<String, Object> loadConfig(Path configFile) throws IOException {
        if (configFile.toString().endsWith(".yml") || configFile.toString().endsWith(".yaml")) {
            return yaml.readValue(Files.newBufferedReader(configFile), new TypeReference<>() {});
        } else {
            // Assume JSON
            ObjectMapper json = new ObjectMapper();
            return json.readValue(Files.newBufferedReader(configFile), new TypeReference<>() {});
        }
    }
    
    /**
     * Expand matrix configuration to list of parameter combinations
     * Algorithm-aware: groups parameters by algorithm to avoid redundant runs
     */
    public List<Map<String, Object>> expandMatrix(Map<String, Object> matrix) {
        if (matrix == null || matrix.isEmpty()) {
            return List.of(Map.of());
        }
        
        // Check if algoToRun is in matrix (multiple algorithms)
        Object algoToRun = matrix.get("algoToRun");
        if (algoToRun instanceof List) {
            List<?> algos = (List<?>) algoToRun;
            List<Map<String, Object>> allCombinations = new ArrayList<>();
            
            for (Object algo : algos) {
                String algoStr = String.valueOf(algo);
                
                // Filter parameters relevant to this algorithm
                Map<String, Object> algoMatrix = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : matrix.entrySet()) {
                    String key = entry.getKey();
                    if (key.equals("algoToRun")) {
                        algoMatrix.put(key, algoStr); // Single algorithm
                    } else if (isRelevantForAlgorithm(key, algoStr)) {
                        algoMatrix.put(key, entry.getValue());
                    }
                }
                
                // Expand this algorithm's parameters
                List<Map<String, Object>> algoCombinations = expandSingleAlgorithmMatrix(algoMatrix);
                allCombinations.addAll(algoCombinations);
            }
            
            return allCombinations;
        } else {
            // No algoToRun in matrix, do normal expansion
            return expandSingleAlgorithmMatrix(matrix);
        }
    }
    
    /**
     * Expand matrix for a single algorithm (no algoToRun variation)
     */
    private List<Map<String, Object>> expandSingleAlgorithmMatrix(Map<String, Object> matrix) {
        List<String> keys = new ArrayList<>(matrix.keySet());
        List<List<Object>> values = keys.stream()
            .map(k -> {
                Object v = matrix.get(k);
                if (v instanceof List) {
                    return (List<Object>) v;
                }
                return List.of(v); // Allow singletons
            })
            .collect(Collectors.toList());
        
        List<Map<String, Object>> combinations = new ArrayList<>();
        backtrack(keys, values, 0, new LinkedHashMap<>(), combinations);
        return combinations;
    }
    
    /**
     * Check if a parameter is relevant for the given algorithm
     */
    private boolean isRelevantForAlgorithm(String paramName, String algorithm) {
        if ("CAGRA_HNSW".equalsIgnoreCase(algorithm)) {
            // CAGRA parameters + common parameters
            return !isLuceneOnlyParameter(paramName);
        } else if ("LUCENE_HNSW".equalsIgnoreCase(algorithm)) {
            // Lucene parameters + common parameters
            return !isCagraOnlyParameter(paramName);
        }
        return true; // Unknown algorithm, include all parameters
    }
    
    private boolean isCagraOnlyParameter(String paramName) {
        return Set.of("cagraGraphDegree", "cagraIntermediateGraphDegree", 
                     "cuvsWriterThreads", "cagraHnswLayers", 
                     "cagraITopK", "cagraSearchWidth").contains(paramName);
    }
    
    private boolean isLuceneOnlyParameter(String paramName) {
        return Set.of("hnswMaxConn", "hnswBeamWidth").contains(paramName);
    }
    
    /**
     * Get premade configurations for common dataset+algorithm combinations
     */
    public Map<String, Object> getPremadeConfig(String dataset, String algorithm) {
        Map<String, Object> base = new LinkedHashMap<>();
        
        switch (dataset.toLowerCase()) {
            case "wiki-88m-768":
                base.put("numDocs", 88000000);
                base.put("vectorDimension", 768);
                base.put("datasetFile", "/data/wiki/base.fbin");
                base.put("queryFile", "/data/wiki/queries.fbin");
                base.put("groundTruthFile", "/data/wiki/gt.ibin");
                break;
            case "wiki-5m-2048":
                base.put("numDocs", 5000000);
                base.put("vectorDimension", 2048);
                base.put("datasetFile", "/data/wiki/base_2048.fbin");
                base.put("queryFile", "/data/wiki/queries_2048.fbin");
                base.put("groundTruthFile", "/data/wiki/gt_2048.ibin");
                break;
            case "openai-4.6m-1536":
                base.put("numDocs", 4600000);
                base.put("vectorDimension", 1536);
                base.put("datasetFile", "/data/openai/base.fbin");
                base.put("queryFile", "/data/openai/queries.fbin");
                base.put("groundTruthFile", "/data/openai/gt.ibin");
                break;
            case "sift-100m-128":
                base.put("numDocs", 100000000);
                base.put("vectorDimension", 128);
                base.put("datasetFile", "/data/sift/base.fbin");
                base.put("queryFile", "/data/sift/queries.fbin");
                base.put("groundTruthFile", "/data/sift/gt.ibin");
                break;
            default:
                throw new IllegalArgumentException("Unknown dataset: " + dataset);
        }
        
        // Common parameters
        base.put("numWarmUpQueries", 10);
        base.put("numQueriesToRun", 1000);
        base.put("topK", 10);
        base.put("createIndexInMemory", false);
        base.put("cleanIndexDirectory", true);
        base.put("saveResultsOnDisk", true);
        base.put("queryThreads", 16);
        base.put("numIndexThreads", 32);
        base.put("loadVectorsInMemory", false);
        base.put("skipIndexing", false);
        
        // Algorithm-specific defaults
        base.put("algoToRun", algorithm);
        
        if ("CAGRA_HNSW".equalsIgnoreCase(algorithm)) {
            base.put("cagraGraphDegree", 64);
            base.put("cagraIntermediateGraphDegree", 128);
            base.put("cuvsWriterThreads", 16);
            base.put("cagraITopK", 32);
            base.put("cagraSearchWidth", 4);
            base.put("cagraHnswLayers", 3);
            base.put("cuvsIndexDirPath", "indexes/cagra/" + dataset.toLowerCase());
        } else if ("LUCENE_HNSW".equalsIgnoreCase(algorithm)) {
            base.put("hnswMaxConn", 16);
            base.put("hnswBeamWidth", 100);
            base.put("hnswIndexDirPath", "indexes/hnsw/" + dataset.toLowerCase());
        }
        
        return base;
    }
    
    private void validateSweep(SweepConfig config) {
        if (config.meta.isEmpty()) {
            throw new IllegalArgumentException("Sweep must have a 'meta' section");
        }
        
        if (!config.meta.containsKey("dataset")) {
            require(config.base, "datasetFile");
            require(config.base, "queryFile");
            require(config.base, "groundTruthFile");
        }
        require(config.base, "topK");
        require(config.base, "numQueriesToRun");
        
        if (config.matrix.isEmpty()) {
            throw new IllegalArgumentException("Sweep must have a 'matrix' section with at least one parameter");
        }
        
        if (config.matrix.containsKey("algoToRun")) {
            Object algo = config.matrix.get("algoToRun");
            if (algo instanceof List) {
                List<?> algos = (List<?>) algo;
                for (Object a : algos) {
                    String algoStr = String.valueOf(a);
                    if (!"CAGRA_HNSW".equalsIgnoreCase(algoStr) && !"LUCENE_HNSW".equalsIgnoreCase(algoStr)) {
                        throw new IllegalArgumentException("Invalid algorithm: " + algoStr);
                    }
                }
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o == null) {
            return new LinkedHashMap<>();
        }
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        throw new IllegalArgumentException("Expected a map but got: " + o.getClass());
    }
    
    private static void require(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
    }
    
    private static void backtrack(List<String> keys, List<List<Object>> values, int i,
                                 Map<String, Object> curr, List<Map<String, Object>> out) {
        if (i == keys.size()) {
            out.add(new LinkedHashMap<>(curr));
            return;
        }
        String k = keys.get(i);
        for (Object v : values.get(i)) {
            curr.put(k, v);
            backtrack(keys, values, i + 1, curr, out);
        }
        curr.remove(k);
    }
    
    /**
     * Sweep configuration structure
     */
    public static class SweepConfig {
        public Map<String, Object> meta = new LinkedHashMap<>();
        public Map<String, Object> base = new LinkedHashMap<>();
        public Map<String, Object> matrix = new LinkedHashMap<>();
    }
}
