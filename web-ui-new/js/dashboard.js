class BenchmarkDashboard {
    constructor() {
        this.sweeps = [];
        this.currentSweep = null;
        this.charts = {};
        
        // Register Chart.js plugins
        Chart.register(ChartDataLabels);
        
        this.init();
    }

    async init() {
        try {
            await this.loadSweeps();
            this.renderSweepList();
            this.renderOverviewCharts();
        } catch (error) {
            this.showError('Failed to load benchmark data: ' + error.message);
        }
    }

    async loadSweeps() {
        try {
            const response = await fetch('/list-sweep-dirs');
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            const sweepsMetadata = await response.json();
            this.sweeps = [];
            for (const sweepMeta of sweepsMetadata) {
                try {
                    const sweepData = await this.loadSweepData(sweepMeta.id);
                    // Add metadata to sweep data
                    sweepData.datasets = sweepMeta.datasets;
                    sweepData.commit_id = sweepMeta.commit_id;
                    sweepData.date = sweepMeta.date;
                    this.sweeps.push(sweepData);
                } catch (error) {
                    console.warn(`Failed to load sweep ${sweepMeta.id}:`, error);
                }
            }

            // Sort by date (newest first)
            this.sweeps.sort((a, b) => new Date(b.date) - new Date(a.date));
            
            // Load available datasets
            await this.loadDatasets();
        } catch (error) {
            throw new Error(`Failed to load sweeps: ${error.message}`);
        }
    }
    
    async loadDatasets() {
        try {
            const response = await fetch('/list-datasets');
            if (response.ok) {
                this.availableDatasets = await response.json();
                this.populateDatasetFilter();
            } else {
                this.availableDatasets = [];
            }
        } catch (error) {
            console.warn('Failed to load datasets:', error);
            this.availableDatasets = [];
        }
    }
    
    populateDatasetFilter() {
        const datasetFilter = document.getElementById('dataset-filter');
        datasetFilter.innerHTML = '<option value="all">All Datasets</option>';
        
        this.availableDatasets.forEach(dataset => {
            const option = document.createElement('option');
            option.value = dataset;
            option.textContent = dataset;
            datasetFilter.appendChild(option);
        });
        
        // Add event listener for filtering
        datasetFilter.addEventListener('change', () => this.applyFilters());
        document.getElementById('algorithm-filter').addEventListener('change', () => this.applyFilters());
    }
    
    applyFilters() {
        const selectedDataset = document.getElementById('dataset-filter').value;
        const selectedAlgorithm = document.getElementById('algorithm-filter').value;
        
        // Create filtered data by filtering runs within sweeps
        const filteredSweeps = this.sweeps.map(sweep => {
            const filteredRuns = sweep.runs.filter(run => {
                const datasetMatch = selectedDataset === 'all' || run.dataset === selectedDataset;
                const algorithmMatch = selectedAlgorithm === 'all' || run.algorithm === selectedAlgorithm;
                return datasetMatch && algorithmMatch;
            });
            
            if (filteredRuns.length > 0) {
                return {
                    ...sweep,
                    runs: filteredRuns,
                    algorithms: [...new Set(filteredRuns.map(run => run.algorithm))],
                    datasets: [...new Set(filteredRuns.map(run => run.dataset))]
                };
            }
            return null;
        }).filter(sweep => sweep !== null);
        
        this.renderFilteredOverviewCharts(filteredSweeps);
    }

    async loadSweepData(sweepId) {
        // Load consolidated CSV data
        const csvResponse = await fetch(`/results/raw/${sweepId}/${sweepId}.csv`);
        if (!csvResponse.ok) {
            throw new Error(`Failed to load CSV for sweep ${sweepId}`);
        }
        
        const csvText = await csvResponse.text();
        const runs = this.parseCSV(csvText);

        // Extract date from sweep ID
        const dateMatch = sweepId.match(/^(\d{2})-(\d{2})-(\d{4})/);
        const date = dateMatch ? `${dateMatch[3]}-${dateMatch[2]}-${dateMatch[1]}` : 'Unknown';

        return {
            id: sweepId,
            date: date,
            runs: runs,
            totalRuns: runs.length,
            algorithms: [...new Set(runs.map(run => run.algorithm))],
            datasets: [...new Set(runs.map(run => run.dataset))]
        };
    }

    parseCSV(csvText) {
        const lines = csvText.trim().split('\n');
        const headers = lines[0].split(',').map(h => h.trim());
        
        return lines.slice(1).map(line => {
            const values = line.split(',').map(v => v.trim());
            const run = {};
            headers.forEach((header, index) => {
                run[header] = values[index] || '';
            });
            return run;
        });
    }

    renderSweepList() {
        const container = document.getElementById('sweep-list');
        container.innerHTML = '';

        this.sweeps.forEach(sweep => {
            const item = document.createElement('div');
            item.className = 'sweep-item';
            item.onclick = () => this.showSweepAnalysis(sweep);
            
            item.innerHTML = `
                <div class="sweep-title">${sweep.id}</div>
                <div class="sweep-info">
                    ${sweep.totalRuns} runs • ${sweep.algorithms.join(', ')}<br>
                    <strong>Datasets:</strong> ${sweep.datasets.join(', ')}<br>
                    <strong>Commit:</strong> ${sweep.commit_id}<br>
                    ${sweep.date}
                </div>
            `;
            
            container.appendChild(item);
        });
    }

    renderOverviewCharts() {
        this.renderFilteredOverviewCharts(this.sweeps);
    }
    
    renderFilteredOverviewCharts(filteredSweeps) {
        const container = document.getElementById('overview-charts');
        container.innerHTML = '<div class="loading">Generating charts...</div>';

        if (filteredSweeps.length === 0) {
            container.innerHTML = `
                <div class="no-data-state">
                    <div style="font-size: 48px; margin-bottom: 20px;">No Data</div>
                    <h3>No data matches the selected filters</h3>
                    <p>Try adjusting your filter settings or run more benchmarks.</p>
                </div>
            `;
            return;
        }

        const paramCombos = this.getParameterCombinations(filteredSweeps);
        
        if (paramCombos.length === 0) {
            container.innerHTML = `
                <div class="no-data-state">
                    <div style="font-size: 48px; margin-bottom: 20px;">No Data</div>
                    <h3>No benchmark runs found</h3>
                    <p>The selected sweeps don't contain any valid benchmark data.</p>
                </div>
            `;
            return;
        }
        
        // Clear the loading message
        container.innerHTML = '';
        
        paramCombos.forEach((combo, index) => {
            try {
                const chartContainer = document.createElement('div');
                chartContainer.className = 'chart-container';
                
                const canvas = document.createElement('canvas');
                canvas.id = `overview-chart-${index}`;
                canvas.width = 300;
                canvas.height = 200;
                
                chartContainer.innerHTML = `
                    <div class="chart-title">${combo.title}</div>
                `;
                chartContainer.appendChild(canvas);
                container.appendChild(chartContainer);
                
                this.createOverviewChart(canvas, combo);
            } catch (error) {
                console.error('Error creating chart for', combo.paramKey, ':', error);
            }
        });
    }

    getParameterCombinations(sweeps = this.sweeps) {
        const runGroups = new Map();
        
        sweeps.forEach(sweep => {
            sweep.runs.forEach(run => {
                // Create a unique key based on algorithm and parameters
                const algorithm = run.algorithm.replace('_HNSW', '');
                let paramKey;
                
                const dataset = run.dataset || 'unknown';
                
                if (algorithm === 'CAGRA') {
                    const graphDegree = run.cagraGraphDegree || 'N/A';
                    const intermediateDegree = run.cagraIntermediateGraphDegree || 'N/A';
                    const efSearch = run.efSearch || 'N/A';
                    paramKey = `CAGRA_${dataset}_${graphDegree}_${intermediateDegree}_${efSearch}`;
                } else if (algorithm === 'LUCENE') {
                    const maxConn = run.hnswMaxConn || 'N/A';
                    const beamWidth = run.hnswBeamWidth || 'N/A';
                    const efSearch = run.efSearch || 'N/A';
                    paramKey = `LUCENE_${dataset}_${maxConn}_${beamWidth}_${efSearch}`;
                } else {
                    const efSearch = run.efSearch || 'N/A';
                    paramKey = `${algorithm}_${dataset}_${efSearch}`;
                }
                
                if (!runGroups.has(paramKey)) {
                    const title = this.createMeaningfulTitle(run);
                    runGroups.set(paramKey, {
                        title: title,
                        paramKey: paramKey,
                        algorithm: run.algorithm,
                        data: []
                    });
                }
                
                runGroups.get(paramKey).data.push({
                    sweep: sweep.id,
                    recall: parseFloat(run.recall || 0),
                    indexingTime: parseFloat(run.indexingTime || 0),
                    dataset: run.dataset,
                    runId: run.run_id
                });
            });
        });
        
        const combinations = Array.from(runGroups.values());
        console.log(`DEBUG: Found ${combinations.length} parameter combinations:`, combinations.map(c => c.title));
        return combinations;
    }
    
    createMeaningfulTitle(run) {
        const algorithm = run.algorithm.replace('_HNSW', '');
        const dataset = run.dataset || 'unknown';
        
        if (algorithm === 'CAGRA') {
            const graphDegree = run.cagraGraphDegree || 'N/A';
            const intermediateDegree = run.cagraIntermediateGraphDegree || 'N/A';
            const efSearch = run.efSearch || 'N/A';
            return `CAGRA ${dataset} (Graph: ${graphDegree}, Intermediate: ${intermediateDegree}, efSearch: ${efSearch})`;
        } else if (algorithm === 'LUCENE') {
            const maxConn = run.hnswMaxConn || 'N/A';
            const beamWidth = run.hnswBeamWidth || 'N/A'; 
            const efSearch = run.efSearch || 'N/A';
            return `Lucene HNSW ${dataset} (MaxConn: ${maxConn}, BeamWidth: ${beamWidth}, efSearch: ${efSearch})`;
        } else {
            return `${algorithm} ${dataset} (efSearch: ${run.efSearch || 'N/A'})`;
        }
    }

    createOverviewChart(canvas, combo) {
        const ctx = canvas.getContext('2d');
        
        const sweepLabels = [...new Set(combo.data.map(d => d.sweep))];
        console.log(`Creating chart for ${combo.runId}:`, combo.data, 'sweepLabels:', sweepLabels);
        
        // Only show indexing time, not recall
        const datasets = [
            {
                label: 'Index Time (s)',
                data: sweepLabels.map(sweep => {
                    const sweepData = combo.data.filter(d => d.sweep === sweep);
                    return sweepData.length > 0 ? sweepData[0].indexingTime : 0;
                }),
                borderColor: '#2196f3',
                backgroundColor: 'rgba(33, 150, 243, 0.1)',
                tension: 0.4
            }
        ];

        new Chart(ctx, {
            type: 'line',
            data: {
                labels: sweepLabels,
                datasets: datasets
            },
            options: {
                responsive: true,
                plugins: {
                    datalabels: {
                        display: false
                    }
                },
                interaction: {
                    mode: 'index',
                    intersect: false,
                },
                scales: {
                    x: {
                        display: true,
                        title: {
                            display: true,
                            text: 'Sweep'
                        }
                    },
                    y: {
                        beginAtZero: true,
                        title: {
                            display: true,
                            text: 'Index Time (seconds)'
                        }
                    }
                }
            }
        });
    }

    showSweepAnalysis(sweep) {
        this.currentSweep = sweep;
        
        // Update active sweep in list
        document.querySelectorAll('.sweep-item').forEach(item => {
            item.classList.remove('active');
        });
        event.target.closest('.sweep-item').classList.add('active');
        
        // Show analysis view
        document.getElementById('home-view').style.display = 'none';
        document.getElementById('sweep-analysis').style.display = 'block';
        
        // Populate sweep analysis filters
        this.populateSweepAnalysisFilters(sweep);
        
        // Render speedup analysis
        this.renderSpeedupAnalysis(sweep);
        
        // Render runs table
        this.renderRunsTable(sweep);
    }

    populateSweepAnalysisFilters(sweep) {
        // Populate dataset filter
        const datasetFilter = document.getElementById('sweep-dataset-filter');
        const algorithmFilter = document.getElementById('sweep-algorithm-filter');
        
        datasetFilter.innerHTML = '<option value="all">All Datasets</option>';
        
        const uniqueDatasets = [...new Set(sweep.runs.map(run => run.dataset))];
        uniqueDatasets.forEach(dataset => {
            const option = document.createElement('option');
            option.value = dataset;
            option.textContent = dataset;
            datasetFilter.appendChild(option);
        });
        
        // Remove existing event listeners and add new ones
        datasetFilter.removeEventListener('change', this.sweepFilterHandler);
        algorithmFilter.removeEventListener('change', this.sweepFilterHandler);
        
        this.sweepFilterHandler = () => this.applySweepAnalysisFilters();
        datasetFilter.addEventListener('change', this.sweepFilterHandler);
        algorithmFilter.addEventListener('change', this.sweepFilterHandler);
    }
    
    applySweepAnalysisFilters() {
        const selectedDataset = document.getElementById('sweep-dataset-filter').value;
        const selectedAlgorithm = document.getElementById('sweep-algorithm-filter').value;
        
        // Filter runs based on selected criteria
        const filteredRuns = this.currentSweep.runs.filter(run => {
            const datasetMatch = selectedDataset === 'all' || run.dataset === selectedDataset;
            const algorithmMatch = selectedAlgorithm === 'all' || run.algorithm === selectedAlgorithm;
            return datasetMatch && algorithmMatch;
        });
        
        // Re-render with filtered data
        this.renderSpeedupAnalysis({...this.currentSweep, runs: filteredRuns});
        this.renderRunsTable({...this.currentSweep, runs: filteredRuns});
    }

    renderSpeedupAnalysis(sweep) {
        const ctx = document.getElementById('speedup-chart').getContext('2d');
        
        // Destroy existing chart
        if (this.charts.speedup) {
            this.charts.speedup.destroy();
        }

        // Group runs by algorithm and find best times at each recall level
        const recallLevels = [90, 95, 99];
        const algorithms = ['CAGRA_HNSW', 'LUCENE_HNSW'];
        
        // First, collect best times for each algorithm at each recall level
        const algorithmData = {};
        algorithms.forEach(algo => {
            const runs = sweep.runs.filter(run => run.algorithm === algo);
            algorithmData[algo] = recallLevels.map(level => {
                const runsAtLevel = runs.filter(run => parseFloat(run.recall) >= level);
                if (runsAtLevel.length === 0) return null;
                return Math.min(...runsAtLevel.map(run => parseFloat(run.indexingTime)));
            });
        });
        
        // Only show recall levels where both algorithms have data
        const validRecallLevels = [];
        const validLabels = [];
        const validData = algorithms.map(algo => []);
        
        recallLevels.forEach((level, index) => {
            const cagraTime = algorithmData['CAGRA_HNSW'][index];
            const luceneTime = algorithmData['LUCENE_HNSW'][index];
            
            // Only include this recall level if both algorithms have data
            if (cagraTime !== null && luceneTime !== null) {
                validRecallLevels.push(level);
                validLabels.push(`${level}% Recall`);
                validData[0].push(cagraTime); // CAGRA
                validData[1].push(luceneTime); // LUCENE
            }
        });
        
        // If no valid recall levels, show empty chart
        if (validRecallLevels.length === 0) {
            ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height);
            ctx.font = '16px Arial';
            ctx.fillStyle = '#666';
            ctx.textAlign = 'center';
            ctx.fillText('No comparable data available', ctx.canvas.width / 2, ctx.canvas.height / 2);
            return;
        }
        
        const data = {
            labels: validLabels,
            datasets: algorithms.map((algo, index) => ({
                label: algo.replace('_HNSW', ''),
                data: validData[index],
                backgroundColor: algo === 'CAGRA_HNSW' ? '#4caf50' : '#ff9800',
                borderColor: algo === 'CAGRA_HNSW' ? '#388e3c' : '#f57c00',
                borderWidth: 2
            }))
        };

        this.charts.speedup = new Chart(ctx, {
            type: 'bar',
            data: data,
            options: {
                responsive: true,
                plugins: {
                    title: {
                        display: true,
                        text: 'Best Indexing Times by Recall Level'
                    },
                    legend: {
                        display: true,
                        position: 'top'
                    },
                    tooltip: {
                        callbacks: {
                            afterLabel: function(context) {
                                const luceneTime = data.datasets[1].data[context.dataIndex];
                                const cagraTime = data.datasets[0].data[context.dataIndex];
                                if (luceneTime && cagraTime && context.datasetIndex === 0) {
                                    const speedup = (luceneTime / cagraTime).toFixed(1);
                                    return `Speedup: ${speedup}x`;
                                }
                                return '';
                            }
                        }
                    },
                    datalabels: {
                        display: function(context) {
                            // Only show speedup labels on CAGRA bars when both algorithms have data
                            if (context.datasetIndex === 0) { // CAGRA dataset
                                const luceneTime = data.datasets[1].data[context.dataIndex];
                                const cagraTime = data.datasets[0].data[context.dataIndex];
                                return luceneTime && cagraTime;
                            }
                            return false;
                        },
                        anchor: 'end',
                        align: 'top',
                        color: '#333',
                        backgroundColor: 'rgba(255, 255, 255, 0.8)',
                        borderColor: '#333',
                        borderWidth: 1,
                        borderRadius: 4,
                        padding: 4,
                        font: {
                            weight: 'bold',
                            size: 11
                        },
                        formatter: function(value, context) {
                            const luceneTime = data.datasets[1].data[context.dataIndex];
                            const cagraTime = data.datasets[0].data[context.dataIndex];
                            if (luceneTime && cagraTime) {
                                const speedup = (luceneTime / cagraTime).toFixed(1);
                                return `${speedup}x faster`;
                            }
                            return '';
                        }
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        title: {
                            display: true,
                            text: 'Indexing Time (seconds)'
                        }
                    }
                }
            }
        });
    }

    renderRunsTable(sweep) {
        const tbody = document.querySelector('#runs-table tbody');
        tbody.innerHTML = '';

        sweep.runs.forEach(run => {
            const row = document.createElement('tr');
            
            row.innerHTML = `
                <td>${run.run_id}</td>
                <td>${run.algorithm.replace('_HNSW', '')}</td>
                <td>${parseFloat(run.recall || 0).toFixed(2)}</td>
                <td>${parseFloat(run.indexingTime || 0).toFixed(2)}</td>
                <td>${this.formatIndexSize(run.indexSize)}</td>
                <td>${parseFloat(run.meanLatency || 0).toFixed(2)}</td>
                <td>
                    <button class="btn btn-primary" onclick="dashboard.showMetrics('${run.run_id}')">Metrics</button>
                    <button class="btn btn-secondary" onclick="dashboard.downloadLogs('${run.run_id}')">Logs</button>
                    <button class="btn btn-success" onclick="dashboard.showResults('${run.run_id}')">Results</button>
                </td>
            `;
            
            tbody.appendChild(row);
        });
    }

    async showMetrics(runId) {
        try {
            const memoryResponse = await fetch(`/results/raw/${this.currentSweep.id}/${runId}/memory_metrics.json`);
            const memoryData = memoryResponse.ok ? await memoryResponse.json() : null;

            const cpuResponse = await fetch(`/results/raw/${this.currentSweep.id}/${runId}/cpu_metrics.json`);
            const cpuData = cpuResponse.ok ? await cpuResponse.json() : null;

            document.getElementById('metrics-title').textContent = `Metrics for Run ${runId}`;
            document.getElementById('metrics-modal').style.display = 'block';

            // Create charts
            if (memoryData && memoryData.memory_samples) {
                this.createMemoryChart(memoryData.memory_samples);
            }

            if (cpuData && cpuData.cpu_samples) {
                this.createCpuChart(cpuData.cpu_samples);
            }
        } catch (error) {
            alert('Failed to load metrics: ' + error.message);
        }
    }

    createMemoryChart(samples) {
        const ctx = document.getElementById('memory-chart').getContext('2d');
        
        if (this.charts.memory) {
            this.charts.memory.destroy();
        }
        
        const labels = samples.map((_, index) => `${index * 2}s`);
        const heapData = samples.map(sample => sample.heapUsed / (1024 * 1024));
        const offHeapData = samples.map(sample => sample.offHeapUsed / (1024 * 1024));

        this.charts.memory = new Chart(ctx, {
            type: 'line',
            data: {
                labels: labels,
                datasets: [
                    {
                        label: 'Heap Memory (MB)',
                        data: heapData,
                        borderColor: '#2196f3',
                        backgroundColor: 'rgba(33, 150, 243, 0.1)',
                        tension: 0.4
                    },
                    {
                        label: 'Off-Heap Memory (MB)',
                        data: offHeapData,
                        borderColor: '#4caf50',
                        backgroundColor: 'rgba(76, 175, 80, 0.1)',
                        tension: 0.4
                    }
                ]
            },
            options: {
                responsive: true,
                plugins: {
                    datalabels: {
                        display: false
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
                            text: 'Time'
                        }
                    }
                }
            }
        });
    }

    createCpuChart(samples) {
        const ctx = document.getElementById('cpu-chart').getContext('2d');
        
        if (this.charts.cpu) {
            this.charts.cpu.destroy();
        }
        
        const labels = samples.map((_, index) => `${index * 2}s`);
        const processCpuData = samples.map(sample => sample.cpuUsagePercent || 0);
        const systemCpuData = samples.map(sample => sample.systemCpuUsagePercent || 0);

        this.charts.cpu = new Chart(ctx, {
            type: 'line',
            data: {
                labels: labels,
                datasets: [
                    {
                        label: 'Process CPU (%)',
                        data: processCpuData,
                        borderColor: '#ff9800',
                        backgroundColor: 'rgba(255, 152, 0, 0.1)',
                        tension: 0.4
                    },
                    {
                        label: 'System CPU (%)',
                        data: systemCpuData,
                        borderColor: '#f44336',
                        backgroundColor: 'rgba(244, 67, 54, 0.1)',
                        tension: 0.4
                    }
                ]
            },
            options: {
                responsive: true,
                plugins: {
                    datalabels: {
                        display: false
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        max: 100,
                        title: {
                            display: true,
                            text: 'CPU Usage (%)'
                        }
                    },
                    x: {
                        title: {
                            display: true,
                            text: 'Time'
                        }
                    }
                }
            }
        });
    }

    async showResults(runId) {
        try {
            const response = await fetch(`/results/raw/${this.currentSweep.id}/${runId}/detailed_results.json`);
            if (!response.ok) {
                throw new Error('Failed to load detailed results');
            }
            
            const results = await response.json();
            
            document.getElementById('results-title').textContent = `Detailed Results for Run ${runId}`;
            document.getElementById('json-viewer').textContent = JSON.stringify(results, null, 2);
            document.getElementById('results-modal').style.display = 'block';
        } catch (error) {
            alert('Failed to load results: ' + error.message);
        }
    }

    downloadLogs(runId) {
        const sweepId = this.currentSweep.id;
        
        // Download stdout log
        const stdoutLink = document.createElement('a');
        stdoutLink.href = `/results/raw/${sweepId}/${runId}/${runId}_stdout.log`;
        stdoutLink.download = `${runId}_stdout.log`;
        stdoutLink.click();

        // Download stderr log
        setTimeout(() => {
            const stderrLink = document.createElement('a');
            stderrLink.href = `/results/raw/${sweepId}/${runId}/${runId}_stderr.log`;
            stderrLink.download = `${runId}_stderr.log`;
            stderrLink.click();
        }, 100);
    }

    formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    formatIndexSize(indexSize) {
        if (!indexSize || indexSize === '0' || indexSize === 0) return '0 B';
        
        // If it's already a string with units, return as is
        if (typeof indexSize === 'string' && (indexSize.includes('B') || indexSize.includes('MB') || indexSize.includes('GB'))) {
            return indexSize;
        }
        
        // If it's a number or numeric string, format it
        const value = parseFloat(indexSize);
        if (isNaN(value)) return 'N/A';
        
        // The indexSize values from the CSV are already in GB
        if (value < 1) {
            return (value * 1024).toFixed(2) + ' MB';
        } else {
            return value.toFixed(2) + ' GB';
        }
    }

    showError(message) {
        const errorDiv = document.createElement('div');
        errorDiv.className = 'error';
        errorDiv.innerHTML = `<strong>Error:</strong> ${message}`;
        document.querySelector('.right-panel').insertBefore(errorDiv, document.querySelector('.right-panel').firstChild);
    }

    goBackToDashboard() {
        document.getElementById('sweep-analysis').style.display = 'none';
        document.getElementById('home-view').style.display = 'block';
        
        document.querySelectorAll('.sweep-item').forEach(item => {
            item.classList.remove('active');
        });
    }
}

// Initialize dashboard when page loads
let dashboard;
document.addEventListener('DOMContentLoaded', () => {
    dashboard = new BenchmarkDashboard();
    
    // Modal close handlers
    document.querySelectorAll('.close').forEach(closeBtn => {
        closeBtn.onclick = function() {
            this.closest('.modal').style.display = 'none';
        }
    });
    
    // Close modal when clicking outside
    window.onclick = function(event) {
        if (event.target.classList.contains('modal')) {
            event.target.style.display = 'none';
        }
    }
});