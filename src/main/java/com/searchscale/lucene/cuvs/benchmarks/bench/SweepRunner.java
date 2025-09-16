package com.searchscale.lucene.cuvs.benchmarks.bench;

import java.nio.file.Path;
import java.util.List;
import java.io.IOException;

/**
 * Executes parameter sweeps using the new architecture
 */
public class SweepRunner {
    
    private final Path runsDir;
    
    public SweepRunner(Path runsDir) {
        this.runsDir = runsDir;
    }
    
    public Path getRunsDir() {
        return runsDir;
    }
    
    /**
     * Run a parameter sweep
     */
    public int runSweep(Path sweepYaml, boolean dryRun) throws Exception {
        // Load sweep configuration
        SweepLoader loader = new SweepLoader();
        SweepLoader.SweepConfig sweepConfig = loader.loadSweep(sweepYaml);
        
        // Generate unique sweep ID for this execution
        String uniqueSweepId = generateUniqueSweepId(sweepConfig, sweepYaml);
        sweepConfig.meta.put("unique_sweep_id", uniqueSweepId);
        
        // Materialize configurations
        Materializer materializer = new Materializer();
        List<Materializer.MaterializedRun> runs = materializer.materializeSweep(sweepConfig);
        
        System.out.printf("Sweep '%s': %d combinations%n",
            sweepConfig.meta.getOrDefault("sweep_id", sweepYaml.getFileName().toString()),
            runs.size());
        
        if (dryRun) {
            System.out.println("Dry run - would execute the following runs:");
            for (int i = 0; i < runs.size(); i++) {
                Materializer.MaterializedRun run = runs.get(i);
                System.out.printf("[%d/%d] %s - %s%n", 
                    i + 1, runs.size(), run.runId, run.friendlyName);
            }
            return 0;
        }
        
        // Execute runs
        Runner runner = new Runner(runsDir);
        int successCount = 0;
        int failureCount = 0;
        
        for (int i = 0; i < runs.size(); i++) {
            Materializer.MaterializedRun run = runs.get(i);
            System.out.printf("[%d/%d] Executing: %s - %s%n", 
                i + 1, runs.size(), run.runId, run.friendlyName);
            
            try {
                int result = runner.executeRun(run);
                if (result == 0) {
                    successCount++;
                } else {
                    failureCount++;
                }
            } catch (Exception e) {
                System.err.printf("Run %s failed: %s%n", run.runId, e.getMessage());
                failureCount++;
            }
        }
        
        System.out.printf("%nSweep completed: %d successful, %d failed%n", 
            successCount, failureCount);
        
        // Automatically generate CSV report for this sweep
        if (successCount > 0) {
            try {
                generateSweepCsvReport(sweepConfig);
            } catch (Exception e) {
                System.err.printf("Warning: Failed to generate CSV report: %s%n", e.getMessage());
            }
        }
        
        return failureCount > 0 ? 1 : 0;
    }
    
    /**
     * Run a parameter sweep with an already loaded configuration
     */
    public int runSweepWithConfig(SweepLoader.SweepConfig sweepConfig, boolean dryRun) throws Exception {
        // Generate unique sweep ID for this execution if not already set
        if (!sweepConfig.meta.containsKey("unique_sweep_id")) {
            String uniqueSweepId = generateUniqueSweepId(sweepConfig, null);
            sweepConfig.meta.put("unique_sweep_id", uniqueSweepId);
        }
        
        // Materialize configurations
        Materializer materializer = new Materializer();
        List<Materializer.MaterializedRun> runs = materializer.materializeSweep(sweepConfig);
        
        System.out.printf("Sweep '%s': %d combinations%n",
            sweepConfig.meta.getOrDefault("name", "Unknown"),
            runs.size());
        
        if (dryRun) {
            System.out.println("Dry run - would execute the following runs:");
            for (int i = 0; i < runs.size(); i++) {
                Materializer.MaterializedRun run = runs.get(i);
                System.out.printf("[%d/%d] %s - %s%n", 
                    i + 1, runs.size(), run.runId, run.friendlyName);
            }
            return 0;
        }
        
        // Execute runs
        Runner runner = new Runner(runsDir);
        int successCount = 0;
        int failureCount = 0;
        
        for (int i = 0; i < runs.size(); i++) {
            Materializer.MaterializedRun run = runs.get(i);
            System.out.printf("[%d/%d] Executing: %s - %s%n", 
                i + 1, runs.size(), run.runId, run.friendlyName);
            
            try {
                int result = runner.executeRun(run);
                if (result == 0) {
                    successCount++;
                } else {
                    failureCount++;
                }
            } catch (Exception e) {
                System.err.printf("Run %s failed: %s%n", run.runId, e.getMessage());
                failureCount++;
            }
        }
        
        System.out.printf("%nSweep completed: %d successful, %d failed%n", 
            successCount, failureCount);
        
        // Automatically generate CSV report for this sweep
        if (successCount > 0) {
            try {
                generateSweepCsvReport(sweepConfig);
            } catch (Exception e) {
                System.err.printf("Warning: Failed to generate CSV report: %s%n", e.getMessage());
            }
        }
        
        return failureCount > 0 ? 1 : 0;
    }
    
    /**
     * Execute a single run from the sweep
     */
    private int executeRun(Materializer.MaterializedRun run) throws Exception {
        Runner runner = new Runner(runsDir);
        return runner.executeRun(run);
    }
    
    /**
     * Generate CSV report for the completed sweep
     */
    private void generateSweepCsvReport(SweepLoader.SweepConfig sweepConfig) throws IOException {
        System.out.println("Generating CSV report for sweep...");
        
        // Create reports directory if it doesn't exist
        Path reportsDir = Path.of("reports");
        if (!reportsDir.toFile().exists()) {
            reportsDir.toFile().mkdirs();
        }
        
        // Use the Reporter to generate CSV reports
        Reporter reporter = new Reporter(runsDir, reportsDir);
        reporter.generateReports("csv");
        
        // Also generate web UI data for immediate viewing
        generateWebUiData();
        
        System.out.println("CSV report generated successfully!");
    }
    
    /**
     * Generate web UI data by running the Python script
     */
    private void generateWebUiData() {
        try {
            System.out.println("Updating web UI data...");
            ProcessBuilder pb = new ProcessBuilder("python3", "generate_sweep_csvs.py");
            pb.directory(Path.of(".").toFile());
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                System.out.println("Web UI data updated successfully!");
            } else {
                System.err.println("Warning: Failed to update web UI data (exit code: " + exitCode + ")");
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to run web UI data update script: " + e.getMessage());
        }
    }
    
    /**
     * Generate a unique sweep ID for this sweep execution
     */
    private String generateUniqueSweepId(SweepLoader.SweepConfig sweepConfig, Path sweepYaml) {
        StringBuilder idBuilder = new StringBuilder();
        
        // Add dataset
        String dataset = (String) sweepConfig.meta.getOrDefault("dataset", "unknown");
        idBuilder.append(dataset).append("_");
        
        // Add timestamp (to the minute for uniqueness)
        String timestamp = java.time.Instant.now().toString().substring(0, 16); // YYYY-MM-DDTHH:MM
        idBuilder.append(timestamp.replace(":", "").replace("-", "").replace("T", "_"));
        
        // Add sweep name if available
        String sweepName = (String) sweepConfig.meta.getOrDefault("name", "");
        if (!sweepName.isEmpty()) {
            idBuilder.append("_").append(sweepName.replaceAll("[^a-zA-Z0-9]", "_"));
        }
        
        // Add filename if available
        if (sweepYaml != null) {
            String fileName = sweepYaml.getFileName().toString().replaceAll("[^a-zA-Z0-9]", "_");
            idBuilder.append("_").append(fileName);
        }
        
        return idBuilder.toString();
    }
}
