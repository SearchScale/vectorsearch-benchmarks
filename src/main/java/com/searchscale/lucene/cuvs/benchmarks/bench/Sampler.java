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
     * Start sampling metrics
     */
    public void startSampling() {
        if (sampling) {
            return;
        }
        
        sampling = true;
        samples.clear();
        
        // Sample every 5 seconds
        scheduler.scheduleAtFixedRate(this::sampleMetrics, 0, 5, TimeUnit.SECONDS);
        
        System.out.println("Started metrics sampling");
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
            sample.cpuLoad = -1.0; // CPU load not available
            sample.systemCpuLoad = -1.0; // System CPU load not available
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
        usage.processCpuLoad = -1.0; // CPU load not available
        usage.systemCpuLoad = -1.0; // System CPU load not available
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
        
        // TODO: Implement memory plot generation when XChart is properly configured
        System.out.println("Memory plot generation not yet implemented - requires XChart configuration");
    }
    
    /**
     * Generate CPU usage plot
     */
    public void generateCpuPlot(Path outputDir) throws IOException {
        if (samples.isEmpty()) {
            return;
        }
        
        // TODO: Implement CPU plot generation when XChart is properly configured
        System.out.println("CPU plot generation not yet implemented - requires XChart configuration");
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
