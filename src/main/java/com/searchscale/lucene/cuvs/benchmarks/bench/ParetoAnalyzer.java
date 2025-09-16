package com.searchscale.lucene.cuvs.benchmarks.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Performs Pareto analysis to find optimal configurations at specific recall thresholds
 */
public class ParetoAnalyzer {
    
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectWriter prettyWriter = json.writerWithDefaultPrettyPrinter();
    
    /**
     * Analyze Pareto optimal configurations for given recall thresholds
     */
    public ParetoAnalysisResult analyzeParetoOptimal(List<Catalog.RunEntry> runs, 
                                                   double[] recallThresholds) throws IOException {
        
        ParetoAnalysisResult result = new ParetoAnalysisResult();
        result.recallThresholds = recallThresholds;
        result.analysisTimestamp = java.time.Instant.now().toString();
        
        // Group runs by algorithm
        Map<String, List<Catalog.RunEntry>> runsByAlgo = runs.stream()
            .filter(r -> r.recall > 0 && r.indexingTime > 0 && r.queryTime > 0)
            .collect(Collectors.groupingBy(r -> r.algo));
        
        for (String algorithm : runsByAlgo.keySet()) {
            List<Catalog.RunEntry> algoRuns = runsByAlgo.get(algorithm);
            
            for (double threshold : recallThresholds) {
                ParetoOptimalConfig config = findParetoOptimal(algoRuns, threshold);
                if (config != null) {
                    result.optimalConfigs.add(config);
                }
            }
        }
        
        return result;
    }
    
    /**
     * Find Pareto optimal configuration for a specific recall threshold
     */
    private ParetoOptimalConfig findParetoOptimal(List<Catalog.RunEntry> runs, double recallThreshold) {
        // Filter runs that meet the recall threshold
        List<Catalog.RunEntry> validRuns = runs.stream()
            .filter(r -> r.recall >= recallThreshold)
            .collect(Collectors.toList());
        
        if (validRuns.isEmpty()) {
            return null;
        }
        
        // Find Pareto optimal points (best indexing time AND query time)
        List<Catalog.RunEntry> paretoOptimal = findParetoFrontier(validRuns);
        
        if (paretoOptimal.isEmpty()) {
            return null;
        }
        
        // Select the best overall configuration (lowest combined time)
        Catalog.RunEntry bestRun = paretoOptimal.stream()
            .min(Comparator.comparingDouble(r -> r.indexingTime + r.queryTime))
            .orElse(paretoOptimal.get(0));
        
        ParetoOptimalConfig config = new ParetoOptimalConfig();
        config.algorithm = bestRun.algo;
        config.recallThreshold = recallThreshold;
        config.actualRecall = bestRun.recall;
        config.indexingTime = bestRun.indexingTime;
        config.queryTime = bestRun.queryTime;
        config.totalTime = bestRun.indexingTime + bestRun.queryTime;
        config.topK = bestRun.topK;
        config.runId = bestRun.runId;
        config.parameters = extractParameters(bestRun);
        
        return config;
    }
    
    /**
     * Find Pareto frontier - configurations that are not dominated by others
     */
    private List<Catalog.RunEntry> findParetoFrontier(List<Catalog.RunEntry> runs) {
        List<Catalog.RunEntry> frontier = new ArrayList<>();
        
        for (Catalog.RunEntry candidate : runs) {
            boolean isDominated = false;
            
            for (Catalog.RunEntry other : runs) {
                if (candidate == other) continue;
                
                // Check if 'other' dominates 'candidate'
                if (other.indexingTime <= candidate.indexingTime && 
                    other.queryTime <= candidate.queryTime &&
                    (other.indexingTime < candidate.indexingTime || other.queryTime < candidate.queryTime)) {
                    isDominated = true;
                    break;
                }
            }
            
            if (!isDominated) {
                frontier.add(candidate);
            }
        }
        
        return frontier;
    }
    
    /**
     * Extract key parameters from a run entry
     */
    private Map<String, Object> extractParameters(Catalog.RunEntry run) {
        Map<String, Object> params = new HashMap<>();
        
        // Extract parameters from the params map
        if (run.params != null) {
            // Add common parameters based on algorithm
            if (run.algo.toLowerCase().contains("cagra")) {
                params.put("cagraGraphDegree", run.params.get("cagraGraphDegree"));
                params.put("cagraIntermediateGraphDegree", run.params.get("cagraIntermediateGraphDegree"));
                params.put("cuvsWriterThreads", run.params.get("cuvsWriterThreads"));
            } else if (run.algo.toLowerCase().contains("lucene")) {
                params.put("hnswMaxConn", run.params.get("hnswMaxConn"));
                params.put("hnswBeamWidth", run.params.get("hnswBeamWidth"));
            }
            
            params.put("numIndexThreads", run.params.get("numIndexThreads"));
            params.put("topK", run.topK);
            params.put("flushFreq", run.params.get("flushFreq"));
        }
        
        return params;
    }
    
    /**
     * Generate detailed analysis report
     */
    public void generateAnalysisReport(ParetoAnalysisResult result, Path outputDir) throws IOException {
        // Create output directory if it doesn't exist
        Files.createDirectories(outputDir);
        
        // Save detailed JSON report
        Path jsonFile = outputDir.resolve("pareto_analysis.json");
        prettyWriter.writeValue(jsonFile.toFile(), result);
        
        // Generate summary report
        generateSummaryReport(result, outputDir);
        
        // Generate CSV for easy analysis
        generateCSVReport(result, outputDir);
        
        System.out.println("Pareto analysis report generated in: " + outputDir);
    }
    
    /**
     * Generate human-readable summary report
     */
    private void generateSummaryReport(ParetoAnalysisResult result, Path outputDir) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("# Pareto Analysis Report\n\n");
        report.append("**Analysis Date:** ").append(result.analysisTimestamp).append("\n\n");
        report.append("**Recall Thresholds:** ").append(Arrays.toString(result.recallThresholds)).append("\n\n");
        
        // Group by algorithm
        Map<String, List<ParetoOptimalConfig>> byAlgo = result.optimalConfigs.stream()
            .collect(Collectors.groupingBy(c -> c.algorithm));
        
        for (Map.Entry<String, List<ParetoOptimalConfig>> entry : byAlgo.entrySet()) {
            String algorithm = entry.getKey();
            List<ParetoOptimalConfig> configs = entry.getValue();
            
            report.append("## ").append(algorithm).append("\n\n");
            
            for (ParetoOptimalConfig config : configs) {
                report.append("### Recall ≥ ").append(String.format("%.1f", config.recallThreshold * 100)).append("%\n");
                report.append("- **Actual Recall:** ").append(String.format("%.2f", config.actualRecall * 100)).append("%\n");
                report.append("- **Indexing Time:** ").append(String.format("%.2f", config.indexingTime)).append("s\n");
                report.append("- **Query Time:** ").append(String.format("%.2f", config.queryTime)).append("s\n");
                report.append("- **Total Time:** ").append(String.format("%.2f", config.totalTime)).append("s\n");
                report.append("- **Top-K:** ").append(config.topK).append("\n");
                report.append("- **Run ID:** ").append(config.runId).append("\n");
                
                if (!config.parameters.isEmpty()) {
                    report.append("- **Key Parameters:**\n");
                    config.parameters.forEach((key, value) -> 
                        report.append("  - ").append(key).append(": ").append(value).append("\n"));
                }
                report.append("\n");
            }
        }
        
        // Save report
        Path reportFile = outputDir.resolve("pareto_analysis_report.md");
        Files.write(reportFile, report.toString().getBytes());
    }
    
    /**
     * Generate CSV report for spreadsheet analysis
     */
    private void generateCSVReport(ParetoAnalysisResult result, Path outputDir) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("Algorithm,RecallThreshold,ActualRecall,IndexingTime,QueryTime,TotalTime,TopK,RunID,");
        csv.append("CagraGraphDegree,CagraIntermediateGraphDegree,CuvsWriterThreads,HnswMaxConn,HnswBeamWidth,NumIndexThreads,FlushFreq\n");
        
        for (ParetoOptimalConfig config : result.optimalConfigs) {
            csv.append(config.algorithm).append(",");
            csv.append(String.format("%.1f", config.recallThreshold * 100)).append(",");
            csv.append(String.format("%.2f", config.actualRecall * 100)).append(",");
            csv.append(String.format("%.2f", config.indexingTime)).append(",");
            csv.append(String.format("%.2f", config.queryTime)).append(",");
            csv.append(String.format("%.2f", config.totalTime)).append(",");
            csv.append(config.topK).append(",");
            csv.append(config.runId).append(",");
            
            // Add parameters with defaults for missing values
            csv.append(config.parameters.getOrDefault("cagraGraphDegree", "")).append(",");
            csv.append(config.parameters.getOrDefault("cagraIntermediateGraphDegree", "")).append(",");
            csv.append(config.parameters.getOrDefault("cuvsWriterThreads", "")).append(",");
            csv.append(config.parameters.getOrDefault("hnswMaxConn", "")).append(",");
            csv.append(config.parameters.getOrDefault("hnswBeamWidth", "")).append(",");
            csv.append(config.parameters.getOrDefault("numIndexThreads", "")).append(",");
            csv.append(config.parameters.getOrDefault("flushFreq", "")).append("\n");
        }
        
        Path csvFile = outputDir.resolve("pareto_analysis.csv");
        Files.write(csvFile, csv.toString().getBytes());
    }
    
    /**
     * Data structures for Pareto analysis
     */
    public static class ParetoAnalysisResult {
        public String analysisTimestamp;
        public double[] recallThresholds;
        public List<ParetoOptimalConfig> optimalConfigs = new ArrayList<>();
    }
    
    public static class ParetoOptimalConfig {
        public String algorithm;
        public double recallThreshold;
        public double actualRecall;
        public double indexingTime;
        public double queryTime;
        public double totalTime;
        public int topK;
        public String runId;
        public Map<String, Object> parameters = new HashMap<>();
    }
}
