/**
 * Manages system metrics visualization
 */
class MetricsManager {
    constructor() {
        this.currentRun = null;
        this.metricsCharts = new Map();
        this.init();
    }

    init() {
        this.setupEventListeners();
        this.initializeMetrics();
    }

    setupEventListeners() {
        // Only set up event listeners if elements exist
        const metricsRunFilter = document.getElementById('metrics-run-filter');
        if (metricsRunFilter) {
            metricsRunFilter.addEventListener('change', (e) => {
                const runId = e.target.value;
                if (runId) {
                    this.selectRun(runId);
                } else {
                    this.clearMetrics();
                }
            });
        }
    }

    initializeMetrics() {
        const container = document.getElementById('metrics-container');
        if (container) {
            container.innerHTML = '<p>System metrics will appear here when a run is selected</p>';
        }
    }

    updateMetrics(sweep = null) {
        if (!sweep) {
            this.clearMetrics();
            return;
        }

        this.updateRunFilter(sweep.runs);
    }

    updateRunFilter(runs) {
        const select = document.getElementById('metrics-run-filter');
        select.innerHTML = '<option value="">Select Run</option>';
        
        runs.forEach(run => {
            const option = document.createElement('option');
            option.value = run.runId;
            option.textContent = `${run.runId.substring(0, 8)} - ${run.algorithm}`;
            select.appendChild(option);
        });
    }

    async selectRun(runId) {
        this.currentRun = { runId: runId };
        try {
            await this.loadMetricsData(runId);
            this.renderMetrics();
        } catch (error) {
            console.error('Failed to load metrics:', error);
            this.renderMetrics(); // Still render with simulated data
        }
    }

    async loadMetricsData(runId) {
        try {
            // Try to load memory metrics
            const memoryResponse = await fetch(`../runs/${runId}/memory_metrics.json`);
            if (memoryResponse.ok) {
                this.memoryData = await memoryResponse.json();
            }
            
            // Try to load CPU metrics
            const cpuResponse = await fetch(`../runs/${runId}/cpu_metrics.json`);
            if (cpuResponse.ok) {
                this.cpuData = await cpuResponse.json();
            }
            
            // Try to load general metrics
            const metricsResponse = await fetch(`../runs/${runId}/metrics.json`);
            if (metricsResponse.ok) {
                this.generalMetrics = await metricsResponse.json();
            }
        } catch (error) {
            console.warn('Could not load metrics data:', error);
        }
    }

    renderMetrics() {
        const container = document.getElementById('metrics-container');
        container.innerHTML = '';

        // Create metric cards
        const memoryCard = this.createMemoryCard();
        const cpuCard = this.createCpuCard();
        const performanceCard = this.createPerformanceCard();

        container.appendChild(memoryCard);
        container.appendChild(cpuCard);
        container.appendChild(performanceCard);

        // Create charts after elements are added
        setTimeout(() => {
            this.createMemoryChart();
            this.createCpuChart();
            this.createPerformanceChart();
        }, 100);
    }

    createMemoryCard() {
        const element = document.createElement('div');
        element.className = 'metric-card';
        element.innerHTML = `
            <h4>Memory Usage <span id="memory-indicator" style="font-size: 0.8em; color: #666;"></span></h4>
            <div class="metric-value" id="memory-value">Loading...</div>
            <canvas class="metric-chart" id="memory-chart"></canvas>
        `;
        return element;
    }

    createCpuCard() {
        const element = document.createElement('div');
        element.className = 'metric-card';
        element.innerHTML = `
            <h4>CPU Utilization <span id="cpu-indicator" style="font-size: 0.8em; color: #666;"></span></h4>
            <div class="metric-value" id="cpu-value">Loading...</div>
            <canvas class="metric-chart" id="cpu-chart"></canvas>
        `;
        return element;
    }

    createPerformanceCard() {
        const element = document.createElement('div');
        element.className = 'metric-card';
        element.innerHTML = `
            <h4>Performance Metrics</h4>
            <div class="metric-value" id="performance-value">Loading...</div>
            <canvas class="metric-chart" id="performance-chart"></canvas>
        `;
        return element;
    }

    createMemoryChart() {
        const canvas = document.getElementById('memory-chart');
        if (!canvas) return;

        // Use real data if available, otherwise simulate
        const data = this.memoryData ? this.processMemoryData() : this.generateSimulatedMemoryData();
        
        const chart = new Chart(canvas.getContext('2d'), {
            type: 'line',
            data: {
                labels: data.labels,
                datasets: [{
                    label: 'Heap Used (MB)',
                    data: data.heapMemory,
                    borderColor: '#e74c3c',
                    backgroundColor: 'rgba(231, 76, 60, 0.1)',
                    fill: true,
                    tension: 0.1
                }, {
                    label: 'Heap Max (MB)',
                    data: data.heapMax,
                    borderColor: '#c0392b',
                    backgroundColor: 'rgba(192, 57, 43, 0.1)',
                    fill: false,
                    tension: 0.1,
                    borderDash: [5, 5]
                }, {
                    label: 'Non-Heap Memory (MB)',
                    data: data.nonHeapMemory,
                    borderColor: '#3498db',
                    backgroundColor: 'rgba(52, 152, 219, 0.1)',
                    fill: true,
                    tension: 0.1
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                aspectRatio: 2,
                plugins: {
                    legend: {
                        position: 'top'
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        title: {
                            display: true,
                            text: 'Memory Usage (MB)'
                        }
                    },
                    x: {
                        title: {
                            display: true,
                            text: 'Time (seconds)'
                        }
                    }
                }
            }
        });

        this.metricsCharts.set('memory', chart);
        
        // Update the metric value and indicator
        const maxHeap = Math.max(...data.heapMemory);
        const maxNonHeap = Math.max(...data.nonHeapMemory);
        const isRealData = this.memoryData ? 'Peak' : 'Simulated Peak';
        document.getElementById('memory-value').textContent = 
            `${isRealData}: ${maxHeap.toFixed(1)}MB heap, ${maxNonHeap.toFixed(1)}MB non-heap`;
        
        // Set indicator
        const indicator = document.getElementById('memory-indicator');
        if (indicator) {
            indicator.textContent = this.memoryData ? '(Real Data)' : '(Simulated Data)';
        }
    }

    createCpuChart() {
        const canvas = document.getElementById('cpu-chart');
        if (!canvas) return;

        // Use real data if available, otherwise simulate
        const data = this.cpuData ? this.processCpuData() : this.generateSimulatedCpuData();
        
        const chart = new Chart(canvas.getContext('2d'), {
            type: 'line',
            data: {
                labels: data.labels,
                datasets: [{
                    label: 'Process CPU (%)',
                    data: data.processCpuUsage,
                    borderColor: '#27ae60',
                    backgroundColor: 'rgba(39, 174, 96, 0.1)',
                    fill: true,
                    tension: 0.1
                }, {
                    label: 'System CPU (%)',
                    data: data.systemCpuUsage,
                    borderColor: '#e67e22',
                    backgroundColor: 'rgba(230, 126, 34, 0.1)',
                    fill: true,
                    tension: 0.1
                }, {
                    label: 'Thread Count',
                    data: data.threadCounts,
                    borderColor: '#9b59b6',
                    backgroundColor: 'rgba(155, 89, 182, 0.1)',
                    fill: false,
                    tension: 0.1,
                    yAxisID: 'y1'
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                aspectRatio: 2,
                plugins: {
                    legend: {
                        position: 'top'
                    }
                },
                scales: {
                    y: {
                        type: 'linear',
                        display: true,
                        position: 'left',
                        beginAtZero: true,
                        max: 100,
                        title: {
                            display: true,
                            text: 'CPU Usage (%)'
                        }
                    },
                    y1: {
                        type: 'linear',
                        display: true,
                        position: 'right',
                        beginAtZero: true,
                        title: {
                            display: true,
                            text: 'Thread Count'
                        },
                        grid: {
                            drawOnChartArea: false,
                        },
                    },
                    x: {
                        title: {
                            display: true,
                            text: 'Time (seconds)'
                        }
                    }
                }
            }
        });

        this.metricsCharts.set('cpu', chart);
        
        // Update the metric value and indicator
        const avgProcessCpu = data.processCpuUsage.length > 0 ? 
            data.processCpuUsage.reduce((sum, val) => sum + val, 0) / data.processCpuUsage.length : 0;
        const avgSystemCpu = data.systemCpuUsage.length > 0 ? 
            data.systemCpuUsage.reduce((sum, val) => sum + val, 0) / data.systemCpuUsage.length : 0;
        const maxThreads = data.threadCounts.length > 0 ? Math.max(...data.threadCounts) : 0;
        const isRealData = this.cpuData ? '' : 'Simulated - ';
        
        document.getElementById('cpu-value').textContent = 
            `${isRealData}Process: ${avgProcessCpu.toFixed(1)}%, System: ${avgSystemCpu.toFixed(1)}%, Max Threads: ${maxThreads}`;
        
        // Set indicator
        const indicator = document.getElementById('cpu-indicator');
        if (indicator) {
            indicator.textContent = this.cpuData ? '(Real Data)' : '(Simulated Data)';
        }
    }

    createPerformanceChart() {
        const canvas = document.getElementById('performance-chart');
        if (!canvas) return;

        // Simulate performance data
        const data = this.generateSimulatedPerformanceData();
        
        const chart = new Chart(canvas.getContext('2d'), {
            type: 'line',
            data: {
                labels: data.labels,
                datasets: [{
                    label: 'Indexing Time (s)',
                    data: data.indexingTime,
                    borderColor: '#9b59b6',
                    backgroundColor: 'rgba(155, 89, 182, 0.1)',
                    fill: true,
                    tension: 0.1
                }, {
                    label: 'Query Time (s)',
                    data: data.queryTime,
                    borderColor: '#f39c12',
                    backgroundColor: 'rgba(243, 156, 18, 0.1)',
                    fill: true,
                    tension: 0.1
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                aspectRatio: 2,
                plugins: {
                    legend: {
                        position: 'top'
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        title: {
                            display: true,
                            text: 'Time (seconds)'
                        }
                    },
                    x: {
                        title: {
                            display: true,
                            text: 'Time (seconds)'
                        }
                    }
                }
            }
        });

        this.metricsCharts.set('performance', chart);
        
        // Update the metric value
        const totalIndexing = data.indexingTime.reduce((sum, val) => sum + val, 0);
        const totalQuery = data.queryTime.reduce((sum, val) => sum + val, 0);
        document.getElementById('performance-value').textContent = 
            `Total: ${totalIndexing.toFixed(1)}s indexing, ${totalQuery.toFixed(1)}s querying`;
    }

    processMemoryData() {
        if (!this.memoryData) return this.generateSimulatedMemoryData();
        
        const labels = this.memoryData.timestamps.map((ts, index) => {
            // Convert to relative time in seconds (every 2 seconds)
            return (index * 2).toString();
        });
        
        return {
            labels: labels,
            heapMemory: this.memoryData.heapUsedMB,
            nonHeapMemory: this.memoryData.nonHeapUsedMB,
            heapMax: this.memoryData.heapMaxMB
        };
    }

    processCpuData() {
        if (!this.cpuData) return this.generateSimulatedCpuData();
        
        const labels = this.cpuData.timestamps.map((ts, index) => {
            // Convert to relative time in seconds (every 2 seconds)
            return (index * 2).toString();
        });
        
        return {
            labels: labels,
            processCpuUsage: this.cpuData.processCpuLoad || [],
            systemCpuUsage: this.cpuData.systemCpuLoad || [],
            threadCounts: this.cpuData.threadCounts || []
        };
    }

    generateSimulatedMemoryData() {
        const labels = [];
        const heapMemory = [];
        const nonHeapMemory = [];
        
        for (let i = 0; i < 60; i++) {
            labels.push(i);
            // Simulate memory usage patterns
            heapMemory.push(100 + Math.sin(i * 0.1) * 50 + Math.random() * 20);
            nonHeapMemory.push(50 + Math.cos(i * 0.15) * 30 + Math.random() * 15);
        }
        
        return { labels, heapMemory, nonHeapMemory };
    }

    generateSimulatedCpuData() {
        const labels = [];
        const cpuUsage = [];
        
        for (let i = 0; i < 60; i++) {
            labels.push(i);
            // Simulate CPU usage patterns
            cpuUsage.push(30 + Math.sin(i * 0.2) * 40 + Math.random() * 20);
        }
        
        return { labels, cpuUsage };
    }

    generateSimulatedPerformanceData() {
        const labels = [];
        const indexingTime = [];
        const queryTime = [];
        
        for (let i = 0; i < 60; i++) {
            labels.push(i);
            // Simulate performance patterns
            indexingTime.push(200 + Math.sin(i * 0.1) * 50 + Math.random() * 30);
            queryTime.push(15 + Math.cos(i * 0.15) * 5 + Math.random() * 3);
        }
        
        return { labels, indexingTime, queryTime };
    }

    clearMetrics() {
        this.metricsCharts.forEach(chart => chart.destroy());
        this.metricsCharts.clear();
        
        const container = document.getElementById('metrics-container');
        container.innerHTML = '<p>System metrics will appear here when a run is selected</p>';
        
        const select = document.getElementById('metrics-run-filter');
        select.innerHTML = '<option value="">Select Run</option>';
    }
}

// Make it globally available
window.MetricsManager = MetricsManager;


