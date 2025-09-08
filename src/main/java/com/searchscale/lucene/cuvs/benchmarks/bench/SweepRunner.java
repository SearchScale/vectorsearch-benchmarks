package com.searchscale.lucene.cuvs.benchmarks.bench;

import java.nio.file.Path;
import java.util.List;

/**
 * Executes parameter sweeps using the new architecture
 */
public class SweepRunner {
    
    private final Path runsDir;
    
    public SweepRunner(Path runsDir) {
        this.runsDir = runsDir;
    }
    
    /**
     * Run a parameter sweep
     */
    public int runSweep(Path sweepYaml, boolean dryRun) throws Exception {
        // Load sweep configuration
        SweepLoader loader = new SweepLoader();
        SweepLoader.SweepConfig sweepConfig = loader.loadSweep(sweepYaml);
        
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
        
        return failureCount > 0 ? 1 : 0;
    }
    
    /**
     * Run a parameter sweep with an already loaded configuration
     */
    public int runSweepWithConfig(SweepLoader.SweepConfig sweepConfig, boolean dryRun) throws Exception {
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
        
        return failureCount > 0 ? 1 : 0;
    }
    
    /**
     * Execute a single run from the sweep
     */
    private int executeRun(Materializer.MaterializedRun run) throws Exception {
        Runner runner = new Runner(runsDir);
        return runner.executeRun(run);
    }
}
