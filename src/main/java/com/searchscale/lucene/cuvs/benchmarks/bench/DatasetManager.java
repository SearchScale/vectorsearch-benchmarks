package com.searchscale.lucene.cuvs.benchmarks.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class DatasetManager {
    
    private static final String CONFIG_FILE = "datasets.yaml";
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private Map<String, DatasetConfig> datasets;
    private Map<String, String> defaultPaths;
    
    public static class DatasetConfig {
        public String name;
        public String description;
        public String base_file;
        public String query_file;
        public String ground_truth_file;
        public int num_docs;
        public int vector_dimension;
        public int top_k_ground_truth;
        public boolean available;
        
        public boolean isAvailable() {
            return available && Files.exists(Path.of(base_file)) && 
                   Files.exists(Path.of(query_file)) && Files.exists(Path.of(ground_truth_file));
        }
        
        public String getStatus() {
            if (!available) return "Not configured";
            if (!Files.exists(Path.of(base_file))) return "Base file missing";
            if (!Files.exists(Path.of(query_file))) return "Query file missing";
            if (!Files.exists(Path.of(ground_truth_file))) return "Ground truth missing";
            return "Available";
        }
    }
    
    public DatasetManager() throws IOException {
        loadDatasets();
    }
    
    private void loadDatasets() throws IOException {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            throw new IOException("Dataset config not found: " + CONFIG_FILE);
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> config = yaml.readValue(configFile, Map.class);
        
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> datasetsMap = (Map<String, Map<String, Object>>) config.get("datasets");
        datasets = new HashMap<>();
        
        for (Map.Entry<String, Map<String, Object>> entry : datasetsMap.entrySet()) {
            Map<String, Object> value = entry.getValue();
            DatasetConfig dataset = new DatasetConfig();
            dataset.name = (String) value.get("name");
            dataset.description = (String) value.get("description");
            dataset.base_file = (String) value.get("base_file");
            dataset.query_file = (String) value.get("query_file");
            dataset.ground_truth_file = (String) value.get("ground_truth_file");
            dataset.num_docs = ((Number) value.get("num_docs")).intValue();
            dataset.vector_dimension = ((Number) value.get("vector_dimension")).intValue();
            dataset.top_k_ground_truth = ((Number) value.get("top_k_ground_truth")).intValue();
            dataset.available = (Boolean) value.getOrDefault("available", false);
            datasets.put(entry.getKey(), dataset);
        }
        
        @SuppressWarnings("unchecked")
        Map<String, String> pathsMap = (Map<String, String>) config.get("default_paths");
        defaultPaths = pathsMap != null ? new HashMap<>(pathsMap) : new HashMap<>();
        
        overrideWithEnvVars();
    }
    
    private void overrideWithEnvVars() {
        String basePath = System.getenv("BENCHMARK_DATASET_PATH");
        if (basePath != null && !basePath.isEmpty()) {
            for (DatasetConfig dataset : datasets.values()) {
                dataset.base_file = updatePath(dataset.base_file, basePath);
                dataset.query_file = updatePath(dataset.query_file, basePath);
                dataset.ground_truth_file = updatePath(dataset.ground_truth_file, basePath);
            }
        }
    }
    
    private String updatePath(String originalPath, String newBasePath) {
        if (originalPath == null) return null;
        
        String relativePath = originalPath;
        for (String defaultPath : defaultPaths.values()) {
            if (originalPath.startsWith(defaultPath)) {
                relativePath = originalPath.substring(defaultPath.length());
                if (relativePath.startsWith("/")) {
                    relativePath = relativePath.substring(1);
                }
                break;
            }
        }
        
        return newBasePath.endsWith("/") ? newBasePath + relativePath : newBasePath + "/" + relativePath;
    }
    
    public List<String> getAvailableDatasets() {
        return datasets.entrySet().stream()
            .filter(entry -> entry.getValue().isAvailable())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    public List<String> getAllDatasets() {
        return new ArrayList<>(datasets.keySet());
    }
    
    public DatasetConfig getDataset(String datasetId) {
        return datasets.get(datasetId);
    }
    
    public void listDatasets() {
        System.out.println("\n=== Available Datasets ===");
        System.out.printf("%-15s %-20s %-10s %-15s %s%n", 
            "ID", "Name", "Docs", "Dimensions", "Status");
        System.out.println("-".repeat(80));
        
        for (Map.Entry<String, DatasetConfig> entry : datasets.entrySet()) {
            DatasetConfig dataset = entry.getValue();
            System.out.printf("%-15s %-20s %-10s %-15s %s%n",
                entry.getKey(),
                dataset.name,
                String.format("%.1fM", dataset.num_docs / 1_000_000.0),
                dataset.vector_dimension + "D",
                dataset.getStatus());
        }
        System.out.println();
    }
    
    public Map<String, Object> createBaseConfig(String datasetId) {
        DatasetConfig dataset = datasets.get(datasetId);
        if (dataset == null) {
            throw new IllegalArgumentException("Dataset not found: " + datasetId);
        }
        
        Map<String, Object> config = new HashMap<>();
        config.put("datasetFile", dataset.base_file);
        config.put("queryFile", dataset.query_file);
        config.put("groundTruthFile", dataset.ground_truth_file);
        config.put("numDocs", dataset.num_docs);
        config.put("vectorDimension", dataset.vector_dimension);
        config.put("topK", 100);
        
        return config;
    }
}
