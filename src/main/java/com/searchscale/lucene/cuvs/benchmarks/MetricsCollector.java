package com.searchscale.lucene.cuvs.benchmarks;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.OperatingSystemMXBean;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);
    private static final int SAMPLE_INTERVAL_MS = 2000;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread collectorThread;
    private final List<MemorySample> memorySamples = new ArrayList<>();
    private final List<CpuSample> cpuSamples = new ArrayList<>();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final OperatingSystemMXBean osBean = 
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final long startTime = System.currentTimeMillis();
    
    public void start() {
        if (running.get()) {
            return;
        }
        
        running.set(true);
        collectorThread = new Thread(() -> {
            while (running.get()) {
                try {
                    collectSample();
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Error collecting metrics", e);
                }
            }
        });
        
        collectorThread.setDaemon(true);
        collectorThread.start();
        log.info("Started metrics collection");
    }
    
    public void stop() {
        if (!running.get()) {
            return;
        }
        
        running.set(false);
        if (collectorThread != null) {
            collectorThread.interrupt();
            try {
                collectorThread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("Collected {} memory samples, {} CPU samples", 
                 memorySamples.size(), cpuSamples.size());
    }
    
    private void collectSample() {
        long elapsed = System.currentTimeMillis() - startTime;
        
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        long nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed();
        memorySamples.add(new MemorySample(elapsed, heapUsed, heapMax, nonHeapUsed));
        
        double processCpu = osBean.getProcessCpuLoad() * 100.0;
        double systemCpu = osBean.getSystemCpuLoad() * 100.0;
        if (Double.isNaN(processCpu)) processCpu = 0.0;
        if (Double.isNaN(systemCpu)) systemCpu = 0.0;
        cpuSamples.add(new CpuSample(elapsed, processCpu, systemCpu));
    }
    
    public void writeToFiles(String resultsDir) throws IOException {
        File dir = new File(resultsDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        
        ObjectMapper mapper = Util.newObjectMapper();
        
        File memoryFile = new File(dir, "memory_metrics.json");
        String memoryJson = mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(new MemoryMetrics(memorySamples));
        FileUtils.write(memoryFile, memoryJson, "UTF-8");
        
        File cpuFile = new File(dir, "cpu_metrics.json");
        String cpuJson = mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(new CpuMetrics(cpuSamples));
        FileUtils.write(cpuFile, cpuJson, "UTF-8");
        
        log.info("Metrics written to {}", dir);
    }
    
    static class MemorySample {
        public long timestamp;
        public long heapUsed;
        public long heapMax;
        public long offHeapUsed;
        
        public MemorySample(long timestamp, long heapUsed, long heapMax, long offHeapUsed) {
            this.timestamp = timestamp;
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.offHeapUsed = offHeapUsed;
        }
    }
    
    static class CpuSample {
        public long timestamp;
        public double cpuUsagePercent;
        public double systemCpuUsagePercent;
        
        public CpuSample(long timestamp, double cpuUsagePercent, double systemCpuUsagePercent) {
            this.timestamp = timestamp;
            this.cpuUsagePercent = cpuUsagePercent;
            this.systemCpuUsagePercent = systemCpuUsagePercent;
        }
    }
    
    static class MemoryMetrics {
        public List<MemorySample> memory_samples;
        
        public MemoryMetrics(List<MemorySample> samples) {
            this.memory_samples = samples;
        }
    }
    
    static class CpuMetrics {
        public List<CpuSample> cpu_samples;
        
        public CpuMetrics(List<CpuSample> samples) {
            this.cpu_samples = samples;
        }
    }
}

