package com.searchscale.lucene.cuvs.benchmarks.bench;

import org.knowm.xchart.*;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates reports and plots from benchmark results
 */
public class Reporter {

  private final Path runsDir;
  private final Path outputDir;
  private final ObjectMapper json = new ObjectMapper();

  public Reporter(Path runsDir, Path outputDir) {
    this.runsDir = runsDir;
    this.outputDir = outputDir;
  }

  /**
   * Generate reports in specified format
   */
  public void generateReports(String format) throws IOException {
    Files.createDirectories(outputDir);

    Catalog catalog = new Catalog(runsDir);
    List<Catalog.RunEntry> runs = catalog.loadRuns();

    if (runs.isEmpty()) {
      System.out.println("No runs found to generate reports");
      return;
    }

    System.out.printf("Generating reports for %d runs...%n", runs.size());

    if ("html".equals(format) || "both".equals(format)) {
      generateHtmlReport(runs);
    }

    if ("csv".equals(format) || "both".equals(format)) {
      generateCsvReport(runs);
    }

    // Generate plots
    // generatePlots(runs); // Disabled - using web UI charts instead
    
    // Generate Pareto analysis
    generateParetoAnalysis(runs);

    System.out.printf("Reports generated in: %s%n", outputDir);
  }

  private void generateHtmlReport(List<Catalog.RunEntry> runs) throws IOException {
    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html>\n");
    html.append("<html><head><title>Benchmark Results</title></head><body>\n");
    html.append("<h1>Vector Search Benchmark Results</h1>\n");

    // Summary table
    html.append("<h2>Summary</h2>\n");
    html.append("<table border='1'><tr>");
    html.append("<th>Run ID</th><th>Name</th><th>Dataset</th><th>Algorithm</th>");
    html.append("<th>Indexing Time (s)</th><th>Query Time (s)</th><th>Recall</th><th>QPS</th>");
    html.append("</tr>\n");

    for (Catalog.RunEntry run : runs) {
      html.append("<tr>");
      html.append("<td>").append(run.runId).append("</td>");
      html.append("<td>").append(run.name).append("</td>");
      html.append("<td>").append(run.dataset).append("</td>");
      html.append("<td>").append(run.algo).append("</td>");
      html.append("<td>").append(formatDouble(run.indexingTime)).append("</td>");
      html.append("<td>").append(formatDouble(run.queryTime)).append("</td>");
      html.append("<td>").append(formatDouble(run.recall)).append("</td>");
      html.append("<td>").append(formatDouble(run.qps)).append("</td>");
      html.append("</tr>\n");
    }

    html.append("</table>\n");

    // Best runs by recall
    html.append("<h2>Best Runs by Recall</h2>\n");
    generateBestRunsSection(html, runs);

    // Algorithm comparison
    html.append("<h2>Algorithm Comparison</h2>\n");
    generateAlgorithmComparison(html, runs);

    html.append("</body></html>\n");

    Path htmlFile = outputDir.resolve("benchmark_report.html");
    Files.writeString(htmlFile, html.toString());
    System.out.println("HTML report generated: " + htmlFile);
  }

  private void generateCsvReport(List<Catalog.RunEntry> runs) throws IOException {
    if (runs.isEmpty()) {
      System.out.println("No runs available for CSV generation");
      return;
    }

    // Generate sweep-specific CSV with new naming convention
    generateSweepCsv(runs);
    
    // Also generate the consolidated CSV for backward compatibility
    generateConsolidatedCsv(runs);
  }

  private void generateSweepCsv(List<Catalog.RunEntry> runs) throws IOException {
    // Group runs by sweep (assuming runs with same dataset and similar timestamp are from same sweep)
    Map<String, List<Catalog.RunEntry>> sweepGroups = groupRunsBySweep(runs);
    
    for (Map.Entry<String, List<Catalog.RunEntry>> entry : sweepGroups.entrySet()) {
      String sweepId = entry.getKey();
      List<Catalog.RunEntry> sweepRuns = entry.getValue();
      
      // Generate CSV for this sweep
      String csvContent = generateCsvContent(sweepRuns);
      
      // Create filename with new convention: dd-mm-yyyy-hash.csv
      String fileName = generateSweepFileName(sweepId, sweepRuns);
      Path csvFile = outputDir.resolve(fileName);
      
      Files.writeString(csvFile, csvContent);
      System.out.println("Sweep CSV generated: " + csvFile);
    }
  }

  private void generateConsolidatedCsv(List<Catalog.RunEntry> runs) throws IOException {
    // Collect all possible column names from all runs
    Set<String> allColumns = new LinkedHashSet<>();

    // Core columns (always include these)
    allColumns.add("runId");
    allColumns.add("name");
    allColumns.add("dataset");
    allColumns.add("algorithm");
    allColumns.add("createdAt");
    allColumns.add("sweepId");
    allColumns.add("commitId");

    // Performance metrics
    allColumns.add("indexingTime");
    allColumns.add("queryTime");
    allColumns.add("recall");
    allColumns.add("qps");
    allColumns.add("meanLatency");
    allColumns.add("indexSize");
    allColumns.add("peakHeapMemory");
    allColumns.add("avgHeapMemory");

    // New columns you requested
    allColumns.add("segmentCount");
    allColumns.add("cuvsWriterThreads");
    allColumns.add("efSearch");

    // Collect all parameter names from all runs
    for (Catalog.RunEntry run : runs) {
      if (run.params != null) {
        allColumns.addAll(run.params.keySet());
      }
    }

    // Remove duplicates and sort for consistent ordering
    List<String> columnOrder = new ArrayList<>(allColumns);
    Collections.sort(columnOrder);

    // Analyze which columns have uniform values (all same value)
    Map<String, Set<Object>> columnValues = new HashMap<>();
    for (String column : columnOrder) {
      columnValues.put(column, new HashSet<>());
    }

    for (Catalog.RunEntry run : runs) {
      for (String column : columnOrder) {
        Object value = getColumnValue(run, column);
        columnValues.get(column).add(value);
      }
    }

    // Filter out columns where all values are the same (except core columns)
    List<String> finalColumns = new ArrayList<>();
    for (String column : columnOrder) {
      Set<Object> values = columnValues.get(column);
      // Keep core columns and columns with varying values
      if (isCoreColumn(column) || values.size() > 1) {
        finalColumns.add(column);
      }
    }

    // Generate CSV content
    String csvContent = generateCsvContent(runs, finalColumns);

    Path csvFile = outputDir.resolve("benchmark_results.csv");
    Files.writeString(csvFile, csvContent);
    System.out.println("Enhanced CSV report generated: " + csvFile);
    System.out.println("Columns included: " + finalColumns.size() + " (skipped " + (columnOrder.size() - finalColumns.size()) + " uniform columns)");
    
    // Auto-update web UI data
    updateWebUIData(csvFile, csvContent);
  }

  /**
   * Automatically updates web UI data after generating benchmark results
   */
  private void updateWebUIData(Path csvFile, String csvContent) {
    try {
      // Copy the main results file to web-ui directory for direct access
      Path webUIDataDir = Paths.get("web-ui", "data");
      if (!Files.exists(webUIDataDir)) {
        Files.createDirectories(webUIDataDir);
      }
      
      Path webUIResultsFile = webUIDataDir.resolve("consolidated_results.csv");
      Files.writeString(webUIResultsFile, csvContent);
      
      // Run the sweep CSV generation script
      ProcessBuilder pb = new ProcessBuilder("python3", "generate_sweep_csvs.py");
      pb.directory(new File("."));
      pb.inheritIO(); // Show output in console
      
      Process process = pb.start();
      int exitCode = process.waitFor();
      
      if (exitCode == 0) {
        System.out.println("✅ Web UI data updated successfully!");
        System.out.println("📊 Dashboard will show latest results automatically");
      } else {
        System.out.println("⚠️  Warning: Failed to update web UI data (exit code: " + exitCode + ")");
        System.out.println("💡 You can manually run: ./update_web_ui_data.sh");
      }
      
    } catch (Exception e) {
      System.out.println("⚠️  Warning: Could not auto-update web UI data: " + e.getMessage());
      System.out.println("💡 You can manually run: ./update_web_ui_data.sh");
    }
  }

  private Map<String, List<Catalog.RunEntry>> groupRunsBySweep(List<Catalog.RunEntry> runs) {
    Map<String, List<Catalog.RunEntry>> groups = new LinkedHashMap<>();
    
    for (Catalog.RunEntry run : runs) {
      // Use the sweep ID from the catalog entry (which includes unique sweep IDs)
      String sweepId = run.sweepId;
      groups.computeIfAbsent(sweepId, k -> new ArrayList<>()).add(run);
    }
    
    return groups;
  }

  private String generateSweepFileName(String sweepId, List<Catalog.RunEntry> runs) {
    if (runs.isEmpty()) return sweepId + ".csv";
    
    // Get the date from the first run
    String dateStr = runs.get(0).createdAt.substring(0, 10); // YYYY-MM-DD
    String[] dateParts = dateStr.split("-");
    String formattedDate = dateParts[2] + "-" + dateParts[1] + "-" + dateParts[0]; // DD-MM-YYYY
    
    // Generate hash from sweep parameters
    String hash = generateSweepHash(runs);
    
    return formattedDate + "-" + hash + ".csv";
  }

  private String generateSweepHash(List<Catalog.RunEntry> runs) {
    // Create a hash based on the sweep configuration
    StringBuilder configStr = new StringBuilder();
    if (!runs.isEmpty()) {
      Catalog.RunEntry firstRun = runs.get(0);
      configStr.append(firstRun.dataset).append("_");
      configStr.append(firstRun.algo).append("_");
      if (firstRun.params != null) {
        firstRun.params.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> configStr.append(entry.getKey()).append("=").append(entry.getValue()).append("_"));
      }
    }
    
    // Generate a short hash
    String hashStr = String.valueOf(Math.abs(configStr.toString().hashCode()));
    return hashStr.length() >= 8 ? hashStr.substring(0, 8) : hashStr;
  }

  private String generateCsvContent(List<Catalog.RunEntry> runs) {
    return generateCsvContent(runs, null);
  }

  private String generateCsvContent(List<Catalog.RunEntry> runs, List<String> columns) {
    if (columns == null) {
      // Generate columns dynamically
      Set<String> allColumns = new LinkedHashSet<>();
      allColumns.add("runId");
      allColumns.add("name");
      allColumns.add("dataset");
      allColumns.add("algorithm");
      allColumns.add("createdAt");
      allColumns.add("indexingTime");
      allColumns.add("queryTime");
      allColumns.add("recall");
      allColumns.add("qps");
      allColumns.add("meanLatency");
      allColumns.add("indexSize");
      allColumns.add("peakHeapMemory");
      allColumns.add("avgHeapMemory");
      allColumns.add("segmentCount");
      allColumns.add("cuvsWriterThreads");
      allColumns.add("efSearch");
      
      for (Catalog.RunEntry run : runs) {
        if (run.params != null) {
          allColumns.addAll(run.params.keySet());
        }
      }
      
      columns = new ArrayList<>(allColumns);
      Collections.sort(columns);
    }

    // Generate CSV header
    StringBuilder csv = new StringBuilder();
    csv.append(String.join(",", columns)).append("\n");

    // Generate CSV rows
    for (Catalog.RunEntry run : runs) {
      List<String> row = new ArrayList<>();
      for (String column : columns) {
        Object value = getColumnValue(run, column);
        String csvValue = formatCsvValue(value);
        row.add(csvValue);
      }
      csv.append(String.join(",", row)).append("\n");
    }

    return csv.toString();
  }   

  private void generateParetoAnalysis(List<Catalog.RunEntry> runs) throws IOException {
    System.out.println("Generating Pareto analysis...");
    
    try {
      ParetoAnalyzer analyzer = new ParetoAnalyzer();
      double[] recallThresholds = {0.90, 0.95}; // 90% and 95% recall thresholds
      
      ParetoAnalyzer.ParetoAnalysisResult result = analyzer.analyzeParetoOptimal(runs, recallThresholds);
      
      // Generate analysis report
      analyzer.generateAnalysisReport(result, outputDir);
      
      // Generate speedup comparison JSON for web UI
      generateSpeedupComparisonJson(result);
      
      System.out.println("Pareto analysis completed successfully");
    } catch (Exception e) {
      System.err.println("Failed to generate Pareto analysis: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private void generateSpeedupComparisonJson(ParetoAnalyzer.ParetoAnalysisResult result) throws IOException {
    Map<String, Object> speedupData = new HashMap<>();
    
    // Group optimal configs by recall threshold
    Map<Double, List<ParetoAnalyzer.ParetoOptimalConfig>> byThreshold = result.optimalConfigs.stream()
        .collect(Collectors.groupingBy(config -> config.recallThreshold));
    
    for (Map.Entry<Double, List<ParetoAnalyzer.ParetoOptimalConfig>> entry : byThreshold.entrySet()) {
      double threshold = entry.getKey();
      List<ParetoAnalyzer.ParetoOptimalConfig> configs = entry.getValue();
      
      // Find CAGRA and Lucene configs for this threshold
      ParetoAnalyzer.ParetoOptimalConfig cagraConfig = configs.stream()
          .filter(c -> "CAGRA_HNSW".equals(c.algorithm))
          .findFirst().orElse(null);
      
      ParetoAnalyzer.ParetoOptimalConfig luceneConfig = configs.stream()
          .filter(c -> "LUCENE_HNSW".equals(c.algorithm))
          .findFirst().orElse(null);
      
      if (cagraConfig != null && luceneConfig != null) {
        String thresholdLabel = threshold == 0.90 ? "~90%" : "~95%";
        
        Map<String, Object> thresholdData = new HashMap<>();
        thresholdData.put("cagra", Map.of(
            "indexingTime", cagraConfig.indexingTime / 1000.0, // Convert to seconds
            "recall", cagraConfig.actualRecall * 100.0 // Convert to percentage
        ));
        thresholdData.put("lucene", Map.of(
            "indexingTime", luceneConfig.indexingTime / 1000.0, // Convert to seconds
            "recall", luceneConfig.actualRecall * 100.0 // Convert to percentage
        ));
        thresholdData.put("speedup", luceneConfig.indexingTime / cagraConfig.indexingTime);
        
        speedupData.put(thresholdLabel, thresholdData);
      }
    }
    
    // Save speedup comparison JSON
    Path speedupFile = outputDir.resolve("speedup_comparison.json");
    json.writerWithDefaultPrettyPrinter().writeValue(speedupFile.toFile(), speedupData);
    System.out.println("Speedup comparison JSON generated: " + speedupFile);
  }

  private void generatePlots(List<Catalog.RunEntry> runs) throws IOException {
    if (runs.isEmpty()) {
      System.out.println("No runs available for plot generation");
      return;
    }

    System.out.println("Generating plots...");

    // Generate different types of plots
    generateIndexingTimePlot(runs);
    generateQueryTimePlot(runs);
    generateRecallPlot(runs);
    generateMemoryPlots(runs);

    System.out.println("Plots generated successfully");
  }

  private void generateIndexingTimePlot(List<Catalog.RunEntry> runs) throws IOException {
    // Group runs by algorithm for comparison
    Map<String, List<Catalog.RunEntry>> groupedRuns = runs.stream()
      .filter(r -> r.indexingTime > 0) // Only include runs with valid indexing time
      .collect(Collectors.groupingBy(r -> r.algo));

    if (groupedRuns.isEmpty()) {
      System.out.println("No valid indexing time data for plotting");
      return;
    }

    // Create chart
    XYChart chart = new XYChartBuilder()
      .width(800)
      .height(600)
      .title("Indexing Time Comparison")
      .xAxisTitle("Run Index")
      .yAxisTitle("Indexing Time (seconds)")
      .build();

    // Customize chart
    chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
    chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
    chart.getStyler().setMarkerSize(8);

    // Add series for each algorithm
    int runIndex = 0;
    for (Map.Entry<String, List<Catalog.RunEntry>> entry : groupedRuns.entrySet()) {
      String algorithm = entry.getKey();
      List<Catalog.RunEntry> algorithmRuns = entry.getValue();

      // Sort by creation time for consistent ordering
      algorithmRuns.sort(Comparator.comparing(r -> r.createdAt));

      List<Double> xData = new ArrayList<>();
      List<Double> yData = new ArrayList<>();

      for (int i = 0; i < algorithmRuns.size(); i++) {
        xData.add((double) (runIndex + i));
        yData.add(algorithmRuns.get(i).indexingTime / 1000.0); // Convert to seconds
      }

      XYSeries series = chart.addSeries(algorithm, xData, yData);
      series.setMarker(SeriesMarkers.CIRCLE);

      runIndex += algorithmRuns.size();
    }

    // Save chart
    Path plotFile = outputDir.resolve("indexing_time_plot.png");
    BitmapEncoder.saveBitmap(chart, plotFile.toString(), BitmapEncoder.BitmapFormat.PNG);
    System.out.println("Indexing time plot saved: " + plotFile);
  }

  private void generateQueryTimePlot(List<Catalog.RunEntry> runs) throws IOException {
    // Group runs by algorithm for comparison
    Map<String, List<Catalog.RunEntry>> groupedRuns = runs.stream()
      .filter(r -> r.queryTime > 0) // Only include runs with valid query time
      .collect(Collectors.groupingBy(r -> r.algo));

    if (groupedRuns.isEmpty()) {
      System.out.println("No valid query time data for plotting");
      return;
    }

    // Create chart
    XYChart chart = new XYChartBuilder()
      .width(800)
      .height(600)
      .title("Query Time Comparison")
      .xAxisTitle("Run Index")
      .yAxisTitle("Query Time (seconds)")
      .build();

    // Customize chart
    chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
    chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
    chart.getStyler().setMarkerSize(8);

    // Add series for each algorithm
    int runIndex = 0;
    for (Map.Entry<String, List<Catalog.RunEntry>> entry : groupedRuns.entrySet()) {
      String algorithm = entry.getKey();
      List<Catalog.RunEntry> algorithmRuns = entry.getValue();

      // Sort by creation time for consistent ordering
      algorithmRuns.sort(Comparator.comparing(r -> r.createdAt));

      List<Double> xData = new ArrayList<>();
      List<Double> yData = new ArrayList<>();

      for (int i = 0; i < algorithmRuns.size(); i++) {
        xData.add((double) (runIndex + i));
        yData.add(algorithmRuns.get(i).queryTime / 1000.0); // Convert to seconds
      }

      XYSeries series = chart.addSeries(algorithm, xData, yData);
      series.setMarker(SeriesMarkers.CIRCLE);

      runIndex += algorithmRuns.size();
    }

    // Save chart
    Path plotFile = outputDir.resolve("query_time_plot.png");
    BitmapEncoder.saveBitmap(chart, plotFile.toString(), BitmapEncoder.BitmapFormat.PNG);
    System.out.println("Query time plot saved: " + plotFile);
  }

  private void generateRecallPlot(List<Catalog.RunEntry> runs) throws IOException {
    // Group runs by algorithm for comparison
    Map<String, List<Catalog.RunEntry>> groupedRuns = runs.stream()
      .filter(r -> r.recall > 0) // Only include runs with valid recall
      .collect(Collectors.groupingBy(r -> r.algo));

    if (groupedRuns.isEmpty()) {
      System.out.println("No valid recall data for plotting");
      return;
    }

    // Create chart
    XYChart chart = new XYChartBuilder()
      .width(800)
      .height(600)
      .title("Recall Comparison")
      .xAxisTitle("Run Index")
      .yAxisTitle("Recall (%)")
      .build();

    // Customize chart
    chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
    chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
    chart.getStyler().setMarkerSize(8);
    chart.getStyler().setYAxisMin(0.0);
    chart.getStyler().setYAxisMax(100.0);

    // Add series for each algorithm
    int runIndex = 0;
    for (Map.Entry<String, List<Catalog.RunEntry>> entry : groupedRuns.entrySet()) {
      String algorithm = entry.getKey();
      List<Catalog.RunEntry> algorithmRuns = entry.getValue();

      // Sort by creation time for consistent ordering
      algorithmRuns.sort(Comparator.comparing(r -> r.createdAt));

      List<Double> xData = new ArrayList<>();
      List<Double> yData = new ArrayList<>();

      for (int i = 0; i < algorithmRuns.size(); i++) {
        xData.add((double) (runIndex + i));
        yData.add(algorithmRuns.get(i).recall * 100.0); // Convert to percentage
      }

      XYSeries series = chart.addSeries(algorithm, xData, yData);
      series.setMarker(SeriesMarkers.CIRCLE);

      runIndex += algorithmRuns.size();
    }

    // Save chart
    Path plotFile = outputDir.resolve("recall_plot.png");
    BitmapEncoder.saveBitmap(chart, plotFile.toString(), BitmapEncoder.BitmapFormat.PNG);
    System.out.println("Recall plot saved: " + plotFile);
  }

  private void generateMemoryPlots(List<Catalog.RunEntry> runs) throws IOException {
    // Create a scatter plot showing the relationship between indexing time and query time
    List<Catalog.RunEntry> validRuns = runs.stream()
      .filter(r -> r.indexingTime > 0 && r.queryTime > 0)
      .collect(Collectors.toList());

    if (validRuns.isEmpty()) {
      System.out.println("No valid data for performance scatter plot");
      return;
    }

    // Create chart
    XYChart chart = new XYChartBuilder()
      .width(800)
      .height(600)
      .title("Performance Scatter Plot: Indexing vs Query Time")
      .xAxisTitle("Indexing Time (seconds)")
      .yAxisTitle("Query Time (seconds)")
      .build();

    // Customize chart
    chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
    chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
    chart.getStyler().setMarkerSize(10);

    // Group by algorithm and add series
    Map<String, List<Catalog.RunEntry>> groupedRuns = validRuns.stream()
      .collect(Collectors.groupingBy(r -> r.algo));

    for (Map.Entry<String, List<Catalog.RunEntry>> entry : groupedRuns.entrySet()) {
      String algorithm = entry.getKey();
      List<Catalog.RunEntry> algorithmRuns = entry.getValue();

      List<Double> xData = algorithmRuns.stream()
        .map(r -> r.indexingTime / 1000.0) // Convert to seconds
        .collect(Collectors.toList());

      List<Double> yData = algorithmRuns.stream()
        .map(r -> r.queryTime / 1000.0) // Convert to seconds
        .collect(Collectors.toList());

      XYSeries series = chart.addSeries(algorithm, xData, yData);
      series.setMarker(SeriesMarkers.CIRCLE);
    }

    // Save chart
    Path plotFile = outputDir.resolve("performance_scatter_plot.png");
    BitmapEncoder.saveBitmap(chart, plotFile.toString(), BitmapEncoder.BitmapFormat.PNG);
    System.out.println("Performance scatter plot saved: " + plotFile);

    // Also create a QPS vs Recall plot
    generateQpsRecallPlot(validRuns);
  }

  private void generateQpsRecallPlot(List<Catalog.RunEntry> runs) throws IOException {
    // Create a scatter plot showing QPS vs Recall
    List<Catalog.RunEntry> validRuns = runs.stream()
      .filter(r -> r.qps > 0 && r.recall > 0)
      .collect(Collectors.toList());

    if (validRuns.isEmpty()) {
      System.out.println("No valid QPS/Recall data for plotting");
      return;
    }

    // Create chart
    XYChart chart = new XYChartBuilder()
      .width(800)
      .height(600)
      .title("QPS vs Recall Scatter Plot")
      .xAxisTitle("Recall (%)")
      .yAxisTitle("Queries Per Second (QPS)")
      .build();

    // Customize chart
    chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
    chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
    chart.getStyler().setMarkerSize(10);

    // Group by algorithm and add series
    Map<String, List<Catalog.RunEntry>> groupedRuns = validRuns.stream()
      .collect(Collectors.groupingBy(r -> r.algo));

    for (Map.Entry<String, List<Catalog.RunEntry>> entry : groupedRuns.entrySet()) {
      String algorithm = entry.getKey();
      List<Catalog.RunEntry> algorithmRuns = entry.getValue();

      List<Double> xData = algorithmRuns.stream()
        .map(r -> r.recall * 100.0) // Convert to percentage
        .collect(Collectors.toList());

      List<Double> yData = algorithmRuns.stream()
        .map(r -> r.qps)
        .collect(Collectors.toList());

      XYSeries series = chart.addSeries(algorithm, xData, yData);
      series.setMarker(SeriesMarkers.CIRCLE);
    }

    // Save chart
    Path plotFile = outputDir.resolve("qps_recall_plot.png");
    BitmapEncoder.saveBitmap(chart, plotFile.toString(), BitmapEncoder.BitmapFormat.PNG);
    System.out.println("QPS vs Recall plot saved: " + plotFile);
  }

  private void generateBestRunsSection(StringBuilder html, List<Catalog.RunEntry> runs) {
    html.append("<h3>Best Indexing Time (90% recall)</h3>\n");
    html.append("<table border='1'><tr><th>Dataset</th><th>Algorithm</th><th>Run</th><th>Time (s)</th></tr>\n");

    // Group by dataset and algorithm, find best runs
    Map<String, List<Catalog.RunEntry>> grouped = runs.stream()
      .filter(r -> r.recall >= 0.90)
      .collect(Collectors.groupingBy(r -> r.dataset + "_" + r.algo));

    for (Map.Entry<String, List<Catalog.RunEntry>> entry : grouped.entrySet()) {
      List<Catalog.RunEntry> groupRuns = entry.getValue();
      Catalog.RunEntry best = groupRuns.stream()
        .min(Comparator.comparingDouble(r -> r.indexingTime))
        .orElse(null);

      if (best != null) {
        html.append("<tr>");
        html.append("<td>").append(best.dataset).append("</td>");
        html.append("<td>").append(best.algo).append("</td>");
        html.append("<td>").append(best.name).append("</td>");
        html.append("<td>").append(formatDouble(best.indexingTime)).append("</td>");
        html.append("</tr>\n");
      }
    }

    html.append("</table>\n");
  }

  private void generateAlgorithmComparison(StringBuilder html, List<Catalog.RunEntry> runs) {
    html.append("<h3>CPU vs GPU Comparison</h3>\n");
    html.append("<table border='1'><tr>");
    html.append("<th>Dataset</th><th>TopK</th><th>Lucene Indexing (s)</th><th>CAGRA Indexing (s)</th><th>Speedup</th>");
    html.append("<th>Lucene Query (s)</th><th>CAGRA Query (s)</th><th>Speedup</th>");
    html.append("</tr>\n");

    // Group by dataset and topK
    Map<String, List<Catalog.RunEntry>> grouped = runs.stream()
      .collect(Collectors.groupingBy(r -> r.dataset + "_" + r.topK));

    for (Map.Entry<String, List<Catalog.RunEntry>> entry : grouped.entrySet()) {
      List<Catalog.RunEntry> groupRuns = entry.getValue();

      Catalog.RunEntry lucene = groupRuns.stream()
        .filter(r -> "LUCENE_HNSW".equals(r.algo))
        .findFirst().orElse(null);

      Catalog.RunEntry cagra = groupRuns.stream()
        .filter(r -> "CAGRA_HNSW".equals(r.algo))
        .findFirst().orElse(null);

      if (lucene != null && cagra != null) {
        html.append("<tr>");
        html.append("<td>").append(lucene.dataset).append("</td>");
        html.append("<td>").append(lucene.topK).append("</td>");
        html.append("<td>").append(formatDouble(lucene.indexingTime)).append("</td>");
        html.append("<td>").append(formatDouble(cagra.indexingTime)).append("</td>");
        html.append("<td>").append(formatDouble(lucene.indexingTime / cagra.indexingTime)).append("</td>");
        html.append("<td>").append(formatDouble(lucene.queryTime)).append("</td>");
        html.append("<td>").append(formatDouble(cagra.queryTime)).append("</td>");
        html.append("<td>").append(formatDouble(lucene.queryTime / cagra.queryTime)).append("</td>");
        html.append("</tr>\n");
      }
    }

    html.append("</table>\n");
  }

  private String formatDouble(double value) {
    if (value < 0) return "N/A";
    return String.format("%.2f", value);
  }

  private Object getColumnValue(Catalog.RunEntry run, String column) {
    switch (column) {
      case "runId":
        return run.runId;
      case "name":
        return run.name;
      case "dataset":
        return run.dataset;
      case "algorithm":
        return run.algo;
      case "createdAt":
        return run.createdAt;
      case "sweepId":
        return run.sweepId;
      case "commitId":
        return run.commitId;
      case "indexingTime":
        return run.indexingTime;
      case "queryTime":
        return run.queryTime;
      case "recall":
        return run.recall;
      case "qps":
        return run.qps;
      case "meanLatency":
        return run.meanLatency;
      case "indexSize":
        return run.indexSize;
      case "peakHeapMemory":
        return run.peakHeapMemory;
      case "avgHeapMemory":
        return run.avgHeapMemory;
      case "segmentCount":
        return getSegmentCount(run);
      case "cuvsWriterThreads":
        return getCuvsWriterThreads(run);
      case "efSearch":
        return getEfSearch(run);
      default:
        // Try to get from params
        if (run.params != null && run.params.containsKey(column)) {
          return run.params.get(column);
        }
        return null;
    }
  }

  private Object getSegmentCount(Catalog.RunEntry run) {
    if (run.params != null) {
      // Look for segment count in metrics
      for (Map.Entry<String, Object> entry : run.params.entrySet()) {
        if (entry.getKey().contains("segment") && entry.getKey().contains("count")) {
          return entry.getValue();
        }
      }
    }
    return null;
  }

  private Object getCuvsWriterThreads(Catalog.RunEntry run) {
    if (run.params != null) {
      return run.params.get("cuvsWriterThreads");
    }
    return null;
  }

  private Object getEfSearch(Catalog.RunEntry run) {
    if (run.params != null) {
      return run.params.get("efSearch");
    }
    return null;
  }

  private boolean isCoreColumn(String column) {
    return Arrays.asList("runId", "name", "dataset", "algorithm", "createdAt", 
        "sweepId", "commitId", "indexingTime", "queryTime", "recall", "qps", "meanLatency",
        "indexSize", "peakHeapMemory", "avgHeapMemory", "segmentCount", 
        "cuvsWriterThreads", "efSearch").contains(column);
  }

  private String formatCsvValue(Object value) {
    if (value == null) {
      return "";
    }

    String str = value.toString();

    // Escape commas and quotes in string values
    if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
      str = "\"" + str.replace("\"", "\"\"") + "\"";
    }

    return str;
  }  
}
