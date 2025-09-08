package com.searchscale.lucene.cuvs.benchmarks.bench;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI for vectorsearch-benchmarks
 */
@Command(
    name = "bench",
    mixinStandardHelpOptions = true,
    version = "1.0",
    description = "Vector Search Benchmarks CLI",
    subcommands = {
        BenchCLI.ListCommand.class,
        BenchCLI.RunCommand.class,
        BenchCLI.SweepCommand.class,
        BenchCLI.MultiSweepCommand.class,
        BenchCLI.ReportCommand.class,
        BenchCLI.DatasetsCommand.class
    }
)
public class BenchCLI implements Callable<Integer> {
    
    @Option(names = {"--runs-dir"}, description = "Runs directory (default: runs)")
    private Path runsDir = Path.of("runs");
    
    @Override
    public Integer call() {
        System.out.println("Use --help to see available commands");
        return 0;
    }
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new BenchCLI()).execute(args);
        System.exit(exitCode);
    }
    
    public Path getRunsDir() {
        return runsDir;
    }
    
    @Command(
        name = "list",
        description = "List benchmark runs with optional filtering"
    )
    static class ListCommand implements Callable<Integer> {
        
        @Option(names = {"--where"}, description = "Filter runs (e.g., 'dataset=wiki-88m-768 and algo=CAGRA_HNSW')")
        private String whereClause;
        
        @Option(names = {"--runs-dir"}, description = "Runs directory (default: runs)")
        private Path runsDir = Path.of("runs");
        
        @Override
        public Integer call() throws Exception {
            Catalog catalog = new Catalog(runsDir);
            catalog.listRuns(whereClause);
            return 0;
        }
    }
    
    @Command(
        name = "run",
        description = "Run a single benchmark"
    )
    static class RunCommand implements Callable<Integer> {
        
        @Parameters(index = "0", arity = "0..1", description = "Configuration file path")
        private Path configFile;
        
        @Option(names = {"--id"}, description = "Run ID to replay from lockfile")
        private String runId;
        
        @Option(names = {"--runs-dir"}, description = "Runs directory (default: runs)")
        private Path runsDir = Path.of("runs");
        
        @Option(names = {"--dry-run"}, description = "Validate config without running")
        private boolean dryRun;
        
        @Override
        public Integer call() throws Exception {
            if (runId != null) {
                Runner runner = new Runner(runsDir);
                return runner.replayRun(runId, dryRun);
            } else if (configFile != null) {
                Runner runner = new Runner(runsDir);
                return runner.runFromConfig(configFile, dryRun);
            } else {
                System.err.println("Either --id or config file must be specified");
                return 1;
            }
        }
    }
    
    @Command(
        name = "sweep",
        description = "Run a parameter sweep"
    )
    static class SweepCommand implements Callable<Integer> {
        
        @Parameters(index = "0", description = "Sweep YAML file")
        private Path sweepYaml;
        
        @Option(names = {"--runs-dir"}, description = "Runs directory (default: runs)")
        private Path runsDir = Path.of("runs");
        
        @Option(names = {"--dry-run"}, description = "Validate sweep without running")
        private boolean dryRun;
        
        @Override
        public Integer call() throws Exception {
            SweepLoader loader = new SweepLoader();
            SweepRunner sweepRunner = new SweepRunner(runsDir);
            return sweepRunner.runSweep(sweepYaml, dryRun);
        }
    }
    
    @Command(
        name = "report",
        description = "Generate reports and plots"
    )
    static class ReportCommand implements Callable<Integer> {
        
        @Option(names = {"--runs-dir"}, description = "Runs directory (default: runs)")
        private Path runsDir = Path.of("runs");
        
        @Option(names = {"--output"}, description = "Output directory (default: reports)")
        private Path outputDir = Path.of("reports");
        
        @Option(names = {"--format"}, description = "Output format: html, csv, both (default: both)")
        private String format = "both";
        
        @Override
        public Integer call() throws Exception {
            Reporter reporter = new Reporter(runsDir, outputDir);
            reporter.generateReports(format);
            return 0;
        }
    }
    
    @Command(
        name = "datasets",
        description = "Manage and list available datasets"
    )
    static class DatasetsCommand implements Callable<Integer> {
        
        @Option(names = {"--list"}, description = "List all datasets")
        private boolean list;
        
        @Option(names = {"--available"}, description = "List only available datasets")
        private boolean availableOnly;
        
        @Option(names = {"--info"}, description = "Show detailed info for a specific dataset")
        private String datasetId;
        
        @Override
        public Integer call() throws Exception {
            try {
                DatasetManager manager = new DatasetManager();
                
                if (datasetId != null) {
                    DatasetManager.DatasetConfig dataset = manager.getDataset(datasetId);
                    if (dataset == null) {
                        System.err.println("Dataset not found: " + datasetId);
                        return 1;
                    }
                    
                    System.out.println("\n=== Dataset Information ===");
                    System.out.println("ID: " + datasetId);
                    System.out.println("Name: " + dataset.name);
                    System.out.println("Description: " + dataset.description);
                    System.out.println("Documents: " + String.format("%.1fM", dataset.num_docs / 1_000_000.0));
                    System.out.println("Dimensions: " + dataset.vector_dimension);
                    System.out.println("Base file: " + dataset.base_file);
                    System.out.println("Query file: " + dataset.query_file);
                    System.out.println("Ground truth: " + dataset.ground_truth_file);
                    System.out.println("Status: " + dataset.getStatus());
                    System.out.println();
                    
                } else if (availableOnly) {
                    List<String> available = manager.getAvailableDatasets();
                    System.out.println("\n=== Available Datasets ===");
                    if (available.isEmpty()) {
                        System.out.println("No datasets are currently available.");
                        System.out.println("Check dataset paths and ensure files exist.");
                    } else {
                        for (String id : available) {
                            DatasetManager.DatasetConfig dataset = manager.getDataset(id);
                            System.out.printf("%-15s %-20s (%.1fM docs, %dD)%n",
                                id, dataset.name, dataset.num_docs / 1_000_000.0, dataset.vector_dimension);
                        }
                    }
                    System.out.println();
                    
                } else {
                    manager.listDatasets();
                }
                
                return 0;
                
            } catch (Exception e) {
                System.err.println("Error managing datasets: " + e.getMessage());
                e.printStackTrace();
                return 1;
            }
        }
    }
    
    @Command(
        name = "multi-sweep",
        description = "Run parameter sweeps on multiple datasets"
    )
    static class MultiSweepCommand implements Callable<Integer> {
        
        @Parameters(index = "0", description = "Sweep YAML file (dataset-agnostic)")
        private Path sweepYaml;
        
        @Option(names = {"--datasets"}, description = "Comma-separated list of datasets (default: all available)")
        private String datasets;
        
        @Option(names = {"--runs-dir"}, description = "Runs directory (default: runs)")
        private Path runsDir = Path.of("runs");
        
        @Option(names = {"--dry-run"}, description = "Validate sweeps without running")
        private boolean dryRun;
        
        @Override
        public Integer call() throws Exception {
            try {
                DatasetManager datasetManager = new DatasetManager();
                
                List<String> datasetList;
                if (datasets != null) {
                    datasetList = Arrays.asList(datasets.split(","));
                } else {
                    datasetList = datasetManager.getAvailableDatasets();
                }
                
                if (datasetList.isEmpty()) {
                    System.err.println("No datasets available for benchmarking");
                    return 1;
                }
                
                System.out.println("Running sweep on " + datasetList.size() + " datasets: " + String.join(", ", datasetList));
                
                SweepLoader loader = new SweepLoader();
                SweepRunner sweepRunner = new SweepRunner(runsDir);
                
                int totalFailures = 0;
                
                for (String datasetId : datasetList) {
                    System.out.println("\n=== Running sweep for dataset: " + datasetId + " ===");
                    
                    SweepLoader.SweepConfig sweepConfig = loader.loadSweep(sweepYaml);
                    sweepConfig.meta.put("dataset", datasetId);
                    
                    String originalBenchmarkId = (String) sweepConfig.base.get("benchmarkID");
                    sweepConfig.base.put("benchmarkID", originalBenchmarkId + "_" + datasetId.toUpperCase().replace("-", "_"));
                    
                    try {
                        int result = sweepRunner.runSweepWithConfig(sweepConfig, dryRun);
                        if (result != 0) {
                            System.err.println("Sweep failed for dataset: " + datasetId);
                            totalFailures++;
                        }
                    } catch (Exception e) {
                        System.err.println("Error running sweep for dataset " + datasetId + ": " + e.getMessage());
                        totalFailures++;
                    }
                }
                
                if (totalFailures > 0) {
                    System.err.println("\nCompleted with " + totalFailures + " failures out of " + datasetList.size() + " datasets");
                    return 1;
                } else {
                    System.out.println("\nAll sweeps completed successfully!");
                    return 0;
                }
                
            } catch (Exception e) {
                System.err.println("Error in multi-sweep: " + e.getMessage());
                e.printStackTrace();
                return 1;
            }
        }
    }
}
