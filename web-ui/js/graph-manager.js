/**
 * Manages performance graphs and visualizations
 */
class GraphManager {
    constructor() {
        this.charts = new Map();
        this.currentMetric = 'indexingTime';
        this.currentAlgorithm = '';
        this.init();
    }

    init() {
        this.setupEventListeners();
        this.initializeGraphs();
    }

    setupEventListeners() {
        // Only set up event listeners if elements exist
        const metricFilter = document.getElementById('metric-filter');
        if (metricFilter) {
            metricFilter.addEventListener('change', (e) => {
                this.currentMetric = e.target.value;
                this.updateGraphs();
            });
        }

        const algorithmFilter = document.getElementById('algorithm-graph-filter');
        if (algorithmFilter) {
            algorithmFilter.addEventListener('change', (e) => {
                this.currentAlgorithm = e.target.value;
                this.updateGraphs();
            });
        }
    }

    initializeGraphs() {
        const container = document.getElementById('graphs-container');
        if (container) {
            container.innerHTML = '<p>Performance graphs will appear here when sweeps are selected</p>';
        }
    }

    updateGraphs(sweep = null) {
        if (!sweep) {
            this.clearGraphs();
            return;
        }

        this.renderPerformanceGraphs(sweep);
    }

    renderPerformanceGraphs(sweep) {
        const container = document.getElementById('graphs-container');
        container.innerHTML = '';

        // Group runs by algorithm
        const algorithmGroups = this.groupRunsByAlgorithm(sweep.runs);
        
        // Create graphs for each algorithm
        Object.keys(algorithmGroups).forEach(algorithm => {
            if (this.currentAlgorithm && this.currentAlgorithm !== algorithm) {
                return;
            }
            
            const graphElement = this.createGraphElement(algorithm, algorithmGroups[algorithm]);
            container.appendChild(graphElement);
        });
    }

    groupRunsByAlgorithm(runs) {
        const groups = {};
        runs.forEach(run => {
            if (!groups[run.algorithm]) {
                groups[run.algorithm] = [];
            }
            groups[run.algorithm].push(run);
        });
        return groups;
    }

    createGraphElement(algorithm, runs) {
        const element = document.createElement('div');
        element.className = 'graph-item';
        element.innerHTML = `
            <h4>${algorithm} - ${this.getMetricDisplayName(this.currentMetric)}</h4>
            <canvas class="graph-canvas" id="chart-${algorithm}"></canvas>
        `;

        // Create the chart
        setTimeout(() => {
            this.createChart(`chart-${algorithm}`, algorithm, runs);
        }, 100);

        return element;
    }

    createChart(canvasId, algorithm, runs) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) return;

        // Destroy existing chart if it exists
        if (this.charts.has(canvasId)) {
            this.charts.get(canvasId).destroy();
        }

        const ctx = canvas.getContext('2d');
        const chart = new Chart(ctx, {
            type: 'line',
            data: this.prepareChartData(algorithm, runs),
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    title: {
                        display: true,
                        text: `${algorithm} Performance Over Time`
                    },
                    tooltip: {
                        callbacks: {
                            afterLabel: (context) => {
                                const run = runs[context.dataIndex];
                                return [
                                    `Commit: ${run.commitId}`,
                                    `Run ID: ${run.runId.substring(0, 8)}`,
                                    `Recall: ${(run.recall * 100).toFixed(1)}%`,
                                    `QPS: ${run.qps.toFixed(1)}`
                                ];
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        title: {
                            display: true,
                            text: 'Run Index'
                        }
                    },
                    y: {
                        title: {
                            display: true,
                            text: this.getMetricDisplayName(this.currentMetric)
                        }
                    }
                },
                onClick: (event, elements) => {
                    if (elements.length > 0) {
                        const element = elements[0];
                        const run = runs[element.index];
                        this.showRunDetails(run);
                    }
                }
            }
        });

        this.charts.set(canvasId, chart);
    }

    prepareChartData(algorithm, runs) {
        // Sort runs by creation time
        const sortedRuns = runs.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
        
        const labels = sortedRuns.map((run, index) => index + 1);
        const data = sortedRuns.map(run => this.getMetricValue(run, this.currentMetric));
        const backgroundColors = sortedRuns.map(run => this.getRunColor(run));

        return {
            labels: labels,
            datasets: [{
                label: algorithm,
                data: data,
                borderColor: this.getAlgorithmColor(algorithm),
                backgroundColor: backgroundColors,
                fill: false,
                tension: 0.1,
                pointRadius: 6,
                pointHoverRadius: 8
            }]
        };
    }

    getMetricValue(run, metric) {
        switch (metric) {
            case 'indexingTime':
                return run.indexingTime / 1000; // Convert to seconds
            case 'queryTime':
                return run.queryTime / 1000; // Convert to seconds
            case 'recall':
                return run.recall * 100; // Convert to percentage
            case 'qps':
                return run.qps;
            default:
                return 0;
        }
    }

    getMetricDisplayName(metric) {
        switch (metric) {
            case 'indexingTime':
                return 'Indexing Time (seconds)';
            case 'queryTime':
                return 'Query Time (seconds)';
            case 'recall':
                return 'Recall (%)';
            case 'qps':
                return 'Queries Per Second';
            default:
                return metric;
        }
    }

    getAlgorithmColor(algorithm) {
        const colors = {
            'CAGRA_HNSW': '#e74c3c',
            'LUCENE_HNSW': '#3498db',
            'MIXED': '#9b59b6'
        };
        return colors[algorithm] || '#95a5a6';
    }

    getRunColor(run) {
        // Color based on performance (green = good, red = poor)
        const recall = run.recall;
        if (recall >= 0.95) return 'rgba(39, 174, 96, 0.3)';
        if (recall >= 0.90) return 'rgba(241, 196, 15, 0.3)';
        return 'rgba(231, 76, 60, 0.3)';
    }

    showRunDetails(run) {
        const modal = document.getElementById('raw-data-modal');
        const content = document.getElementById('raw-data-content');
        
        content.textContent = JSON.stringify(run, null, 2);
        modal.style.display = 'block';
    }

    clearGraphs() {
        this.charts.forEach(chart => chart.destroy());
        this.charts.clear();
        
        const container = document.getElementById('graphs-container');
        container.innerHTML = '<p>Performance graphs will appear here when sweeps are selected</p>';
    }

    // Method to create comparison graphs across sweeps
    createComparisonGraphs(sweeps) {
        const container = document.getElementById('graphs-container');
        container.innerHTML = '';

        if (sweeps.length === 0) {
            container.innerHTML = '<p>No sweeps available for comparison</p>';
            return;
        }

        // Create a comparison chart
        const element = document.createElement('div');
        element.className = 'graph-item';
        element.innerHTML = `
            <h4>Performance Trends Across Sweeps</h4>
            <canvas class="graph-canvas" id="chart-comparison"></canvas>
        `;

        container.appendChild(element);

        setTimeout(() => {
            this.createComparisonChart('chart-comparison', sweeps);
        }, 100);
    }

    createComparisonChart(canvasId, sweeps) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) return;

        if (this.charts.has(canvasId)) {
            this.charts.get(canvasId).destroy();
        }

        const ctx = canvas.getContext('2d');
        const chart = new Chart(ctx, {
            type: 'line',
            data: this.prepareComparisonData(sweeps),
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    title: {
                        display: true,
                        text: 'Performance Trends Across Sweeps'
                    },
                    tooltip: {
                        callbacks: {
                            afterLabel: (context) => {
                                const sweep = sweeps[context.dataIndex];
                                return [
                                    `Sweep: ${sweep.dataset}`,
                                    `Date: ${new Date(sweep.createdAt).toLocaleDateString()}`,
                                    `Runs: ${sweep.runs.length}`,
                                    `Commit: ${sweep.commitId}`
                                ];
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        title: {
                            display: true,
                            text: 'Sweep Date'
                        },
                        type: 'time',
                        time: {
                            parser: 'YYYY-MM-DD',
                            displayFormats: {
                                day: 'MMM DD'
                            }
                        }
                    },
                    y: {
                        title: {
                            display: true,
                            text: this.getMetricDisplayName(this.currentMetric)
                        }
                    }
                }
            }
        });

        this.charts.set(canvasId, chart);
    }

    prepareComparisonData(sweeps) {
        const labels = sweeps.map(sweep => sweep.createdAt.substring(0, 10));
        
        // Group by algorithm
        const algorithmData = {};
        sweeps.forEach(sweep => {
            sweep.runs.forEach(run => {
                if (!algorithmData[run.algorithm]) {
                    algorithmData[run.algorithm] = [];
                }
                algorithmData[run.algorithm].push({
                    sweep: sweep,
                    run: run
                });
            });
        });

        const datasets = Object.keys(algorithmData).map(algorithm => {
            const data = sweeps.map(sweep => {
                const algorithmRuns = sweep.runs.filter(run => run.algorithm === algorithm);
                if (algorithmRuns.length === 0) return null;
                
                const avgValue = algorithmRuns.reduce((sum, run) => 
                    sum + this.getMetricValue(run, this.currentMetric), 0) / algorithmRuns.length;
                return avgValue;
            });

            return {
                label: algorithm,
                data: data,
                borderColor: this.getAlgorithmColor(algorithm),
                backgroundColor: this.getAlgorithmColor(algorithm) + '20',
                fill: false,
                tension: 0.1
            };
        });

        return {
            labels: labels,
            datasets: datasets
        };
    }
}

// Make it globally available
window.GraphManager = GraphManager;


