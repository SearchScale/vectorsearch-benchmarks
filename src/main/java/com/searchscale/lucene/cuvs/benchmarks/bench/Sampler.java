package com.searchscale.lucene.cuvs.benchmarks.bench;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Collects system metrics during benchmark runs
 */
public class Sampler {
    
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final List<MetricSample> samples = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean sampling = false;
    
    /**
     * Start sampling metrics with default 5-second interval
     */
    public void startSampling() {
        startSampling(5);
    }
    
    /**
     * Start sampling metrics with configurable interval
     */
    public void startSampling(int intervalSeconds) {
        if (sampling) {
            return;
        }
        
        sampling = true;
        samples.clear();
        
        // Sample at specified interval
        scheduler.scheduleAtFixedRate(this::sampleMetrics, 0, intervalSeconds, TimeUnit.SECONDS);
        
        System.out.println("Started metrics sampling with " + intervalSeconds + " second interval");
    }
    
    /**
     * Stop sampling and save results
     */
    public void stopSampling(Path outputDir) throws IOException {
        if (!sampling) {
            return;
        }
        
        sampling = false;
        scheduler.shutdown();
        
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Save samples to file
        Path metricsFile = outputDir.resolve("metrics.json");
        json.writerWithDefaultPrettyPrinter().writeValue(metricsFile.toFile(), samples);
        
        System.out.printf("Stopped metrics sampling. Collected %d samples%n", samples.size());
    }
    
    /**
     * Get collected samples
     */
    public List<MetricSample> getSamples() {
        return new ArrayList<>(samples);
    }
    
    /**
     * Sample current metrics
     */
    private void sampleMetrics() {
        try {
            MetricSample sample = new MetricSample();
            sample.timestamp = Instant.now().toString();
            
            // Memory metrics
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            sample.heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
            sample.heapMax = memoryBean.getHeapMemoryUsage().getMax();
            sample.nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed();
            
            // CPU metrics
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
                sample.cpuLoad = sunOsBean.getProcessCpuLoad();
                sample.systemCpuLoad = sunOsBean.getSystemCpuLoad();
            } else {
                sample.cpuLoad = -1.0;
                sample.systemCpuLoad = -1.0;
            }
            sample.availableProcessors = osBean.getAvailableProcessors();
            
            // Thread metrics
            sample.threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
            
            // GC metrics
            sample.gcCount = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> gc.getCollectionCount())
                .sum();
            
            samples.add(sample);
            
        } catch (Exception e) {
            System.err.println("Failed to sample metrics: " + e.getMessage());
        }
    }
    
    /**
     * Get current memory usage
     */
    public MemoryUsage getCurrentMemoryUsage() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage usage = new MemoryUsage();
        usage.heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        usage.heapMax = memoryBean.getHeapMemoryUsage().getMax();
        usage.nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed();
        return usage;
    }
    
    /**
     * Get current CPU usage
     */
    public CpuUsage getCurrentCpuUsage() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        CpuUsage usage = new CpuUsage();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            usage.processCpuLoad = sunOsBean.getProcessCpuLoad();
            usage.systemCpuLoad = sunOsBean.getSystemCpuLoad();
        } else {
            usage.processCpuLoad = -1.0;
            usage.systemCpuLoad = -1.0;
        }
        usage.availableProcessors = osBean.getAvailableProcessors();
        return usage;
    }
    
    /**
     * Generate memory usage plot
     */
    public void generateMemoryPlot(Path outputDir) throws IOException {
        if (samples.isEmpty()) {
            return;
        }
        
        // Generate JSON data for web UI charts
        Path jsonFile = outputDir.resolve("memory_metrics.json");
        Map<String, Object> chartData = new HashMap<>();
        
        List<String> timestamps = new ArrayList<>();
        List<Double> heapUsedMB = new ArrayList<>();
        List<Double> heapMaxMB = new ArrayList<>();
        List<Double> nonHeapUsedMB = new ArrayList<>();
        
        for (MetricSample sample : samples) {
            timestamps.add(sample.timestamp);
            heapUsedMB.add(sample.heapUsed / (1024.0 * 1024.0));
            heapMaxMB.add(sample.heapMax / (1024.0 * 1024.0));
            nonHeapUsedMB.add(sample.nonHeapUsed / (1024.0 * 1024.0));
        }
        
        chartData.put("timestamps", timestamps);
        chartData.put("heapUsedMB", heapUsedMB);
        chartData.put("heapMaxMB", heapMaxMB);
        chartData.put("nonHeapUsedMB", nonHeapUsedMB);
        
        json.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), chartData);
        
        System.out.println("Memory metrics JSON generated: " + jsonFile);
    }
    
    /**
     * Generate CPU usage plot
     */
    public void generateCpuPlot(Path outputDir) throws IOException {
        if (samples.isEmpty()) {
            return;
        }
        
        // Generate JSON data for web UI charts
        Path jsonFile = outputDir.resolve("cpu_metrics.json");
        Map<String, Object> chartData = new HashMap<>();
        
        List<String> timestamps = new ArrayList<>();
        List<Double> processCpuLoad = new ArrayList<>();
        List<Double> systemCpuLoad = new ArrayList<>();
        List<Integer> threadCounts = new ArrayList<>();
        List<Long> gcCounts = new ArrayList<>();
        List<Integer> availableProcessors = new ArrayList<>();
        
        for (MetricSample sample : samples) {
            timestamps.add(sample.timestamp);
            processCpuLoad.add(sample.cpuLoad >= 0 ? sample.cpuLoad * 100.0 : 0.0); // Convert to percentage
            systemCpuLoad.add(sample.systemCpuLoad >= 0 ? sample.systemCpuLoad * 100.0 : 0.0); // Convert to percentage
            threadCounts.add(sample.threadCount);
            gcCounts.add(sample.gcCount);
            availableProcessors.add(sample.availableProcessors);
        }
        
        chartData.put("timestamps", timestamps);
        chartData.put("processCpuLoad", processCpuLoad);
        chartData.put("systemCpuLoad", systemCpuLoad);
        chartData.put("threadCounts", threadCounts);
        chartData.put("gcCounts", gcCounts);
        chartData.put("availableProcessors", availableProcessors);
        
        json.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), chartData);
        
        System.out.println("CPU metrics JSON generated: " + jsonFile);
    }
    
    /**
     * Metric sample data structure
     */
    public static class MetricSample {
        public String timestamp;
        public long heapUsed;
        public long heapMax;
        public long nonHeapUsed;
        public double cpuLoad;
        public double systemCpuLoad;
        public int availableProcessors;
        public int threadCount;
        public long gcCount;
    }
    
    /**
     * Current memory usage
     */
    public static class MemoryUsage {
        public long heapUsed;
        public long heapMax;
        public long nonHeapUsed;
        
        public double getHeapUsedMB() {
            return heapUsed / (1024.0 * 1024.0);
        }
        
        public double getHeapMaxMB() {
            return heapMax / (1024.0 * 1024.0);
        }
        
        public double getNonHeapUsedMB() {
            return nonHeapUsed / (1024.0 * 1024.0);
        }
        
        public double getHeapUsagePercent() {
            return heapMax > 0 ? (double) heapUsed / heapMax * 100.0 : 0.0;
        }
    }
    
    /**
     * Current CPU usage
     */
    public static class CpuUsage {
        public double processCpuLoad;
        public double systemCpuLoad;
        public int availableProcessors;
        
        public double getProcessCpuPercent() {
            return processCpuLoad * 100.0;
        }
        
        public double getSystemCpuPercent() {
            return systemCpuLoad * 100.0;
        }
    }
}
