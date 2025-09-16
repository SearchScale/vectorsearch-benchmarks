class BenchmarkDashboard {
    constructor() {
        this.dataLoader = new DataLoader();
        this.charts = new Map();
        this.data = [];
        this.currentMetric = 'indexingTime';
        this.currentAlgo = 'all';
        this.metricsManager = null;
        this.paretoManager = null;
    }

    async init() {
        try {
            console.log('Starting dashboard initialization...');
            await this.loadData();
            console.log('Data loaded successfully, generating components...');
            this.setupEventListeners();
            this.initializeMetricsManager();
            this.initializeParetoManager();
            this.generateSweepList();
            this.generateCharts();
            console.log('Dashboard initialization complete');
        } catch (error) {
            console.error('Failed to initialize dashboard:', error);
            this.showError('Failed to load benchmark data. Please check the console for details.');
        }
    }

    async loadData() {
        console.log('Loading data with DataLoader...');
        this.data = await this.dataLoader.loadData();
        console.log(`Loaded ${this.data.length} benchmark runs`);
        console.log('First run:', this.data[0]);
    }

    setupEventListeners() {
        document.getElementById('metric-filter').addEventListener('change', (e) => {
            this.currentMetric = e.target.value;
            this.updateChartsTitle();
            this.generateCharts();
        });

        document.getElementById('algo-filter').addEventListener('change', (e) => {
            this.currentAlgo = e.target.value;
            this.generateCharts();
        });

        // Setup modal close functionality
        const configModal = document.getElementById('config-modal');
        const configCloseBtn = document.getElementById('config-modal-close');
        
        configCloseBtn.onclick = () => {
            configModal.style.display = 'none';
        };

        const metricsModal = document.getElementById('metrics-modal');
        const metricsCloseBtn = document.getElementById('metrics-modal-close');
        
        metricsCloseBtn.onclick = () => {
            metricsModal.style.display = 'none';
        };

        window.onclick = (event) => {
            if (event.target == configModal) {
                configModal.style.display = 'none';
            }
            if (event.target == metricsModal) {
                metricsModal.style.display = 'none';
            }
        };

        // Setup back to dashboard button
        const backBtn = document.getElementById('back-to-dashboard');
        if (backBtn) {
            backBtn.addEventListener('click', () => {
                this.showOverview();
            });
        }
    }

    updateChartsTitle() {
        const metricName = this.currentMetric === 'indexingTime' ? 'Indexing Time' : 'Query Time';
        document.getElementById('charts-title').textContent = `${metricName} by Parameter Configuration`;
    }

    generateSweepList() {
        console.log('Generating sweep list...');
        const sweepList = document.getElementById('sweep-list');
        const sweepGroups = this.groupBySweep(this.data);
        console.log('Sweep groups:', sweepGroups);
        
        sweepList.innerHTML = '';
        
                Object.entries(sweepGroups).forEach(([sweepId, runs]) => {
                    const sweepItem = document.createElement('div');
                    sweepItem.className = 'sweep-item';
                    
                    // Create a better display name for the sweep
                    const sweepName = this.createSweepDisplayName(sweepId, runs[0]);
                    
                    sweepItem.innerHTML = `
                        <div class="sweep-date">${sweepName}</div>
                        <div class="sweep-runs">${runs.length} runs</div>
                    `;
                    
                    sweepItem.addEventListener('click', () => {
                        this.selectSweep(sweepId, runs);
                    });
                    
                    sweepList.appendChild(sweepItem);
                });
        
        // Update total sweeps count
        document.getElementById('total-sweeps').textContent = `${Object.keys(sweepGroups).length} sweeps`;
        
        console.log('Sweep list generated');
    }

    selectSweep(sweepId, runs) {
        // Update active state
        document.querySelectorAll('.sweep-item').forEach(item => {
            item.classList.remove('active');
        });
        
        // Find and activate the clicked item
        const sweepItems = document.querySelectorAll('.sweep-item');
        sweepItems.forEach(item => {
            if (item.textContent.includes(this.createSweepDisplayName(sweepId, runs[0]))) {
                item.classList.add('active');
            }
        });
        
        // Show sweep details inline instead of navigating
        this.showSweepDetailsInline(sweepId, runs);
    }

    showSweepDetailsInline(sweepId, runs) {
        // Hide overview section and show sweep details
        document.getElementById('overview-section').style.display = 'none';
        document.getElementById('sweep-details-section').style.display = 'block';

        // Update sweep details
        this.updateSweepDetails(sweepId, runs);
    }

    showOverview() {
        // Show overview section and hide sweep details
        document.getElementById('overview-section').style.display = 'block';
        document.getElementById('sweep-details-section').style.display = 'none';

        // Clear any active sweep selection
        document.querySelectorAll('.sweep-item').forEach(item => {
            item.classList.remove('active');
        });
    }

    updateSweepDetails(sweepId, runs) {
        // Update page title
        const sweepTitle = this.createSweepDisplayName(sweepId, runs[0]);
        document.getElementById('page-title').textContent = `Sweep: ${sweepTitle}`;

        // Update sweep info cards
        this.updateSweepInfoCards(runs);

        // Update results table
        this.updateSweepResultsTable(runs);

        // Generate Pareto analysis if applicable
        this.generateParetoAnalysis(runs);
    }

    updateSweepInfoCards(runs) {
        const container = document.getElementById('sweep-info');
        
        const avgIndexingTime = this.calculateAverage(runs, 'indexingTime');
        const avgQueryTime = this.calculateAverage(runs, 'queryTime');
        const avgRecall = this.calculateAverage(runs, 'recall');
        const avgQPS = this.calculateAverage(runs, 'qps');

        container.innerHTML = `
            <div class="info-card">
                <h4>Dataset</h4>
                <p>${runs[0].dataset}</p>
            </div>
            <div class="info-card">
                <h4>Total Runs</h4>
                <p>${runs.length}</p>
            </div>
            <div class="info-card">
                <h4>Avg Indexing Time</h4>
                <p>${(avgIndexingTime / 1000).toFixed(2)}s</p>
            </div>
            <div class="info-card">
                <h4>Avg Query Time</h4>
                <p>${(avgQueryTime / 1000).toFixed(2)}s</p>
            </div>
            <div class="info-card">
                <h4>Avg Recall</h4>
                <p>${(avgRecall * 100).toFixed(1)}%</p>
            </div>
            <div class="info-card">
                <h4>Avg QPS</h4>
                <p>${avgQPS.toFixed(1)}</p>
            </div>
        `;
    }

    updateSweepResultsTable(runs) {
        const tbody = document.getElementById('results-table-body');
        tbody.innerHTML = '';
        
        runs.forEach(run => {
            const row = document.createElement('tr');
            
            const indexTime = run.indexingTime > 0 ? (run.indexingTime / 1000).toFixed(2) : 'Failed';
            const queryTime = run.queryTime > 0 ? (run.queryTime / 1000).toFixed(2) : 'Failed';
            const recall = run.recall > 0 ? (run.recall * 100).toFixed(1) : 'Failed';
            const qps = run.qps > 0 ? run.qps.toFixed(1) : 'Failed';
            const meanLatency = run.meanLatency > 0 ? run.meanLatency.toFixed(1) : 'Failed';
            const indexSize = run.indexSize > 0 ? (run.indexSize / 1024 / 1024).toFixed(1) : 'Failed';
            
            const algoClass = run.algo === 'CAGRA_HNSW' ? 'algorithm-cagra_hnsw' : 'algorithm-lucene_hnsw';
            const algoDisplay = run.algo.replace('_HNSW', '');
            
            row.innerHTML = `
                <td>${run.runId.substring(0, 8)}</td>
                <td><span class="algorithm-badge ${algoClass}">${algoDisplay}</span></td>
                <td>${indexTime}</td>
                <td>${queryTime}</td>
                <td>${recall}</td>
                <td>${qps}</td>
                <td>${meanLatency}</td>
                <td>${indexSize}</td>
                <td><code>${run.commitId ? run.commitId.substring(0, 8) : 'N/A'}</code></td>
                <td>
                    <button class="btn btn-small btn-primary" onclick="dashboard.viewRunConfig('${run.runId}')">
                        Config
                    </button>
                    <button class="btn btn-small btn-secondary" onclick="dashboard.viewRunMetrics('${run.runId}')">
                        Metrics
                    </button>
                    <button class="btn btn-small btn-secondary" onclick="dashboard.downloadRunLogs('${run.runId}')">
                        Logs
                    </button>
                </td>
            `;
            
            tbody.appendChild(row);
        });
    }

    calculateAverage(runs, field) {
        const validRuns = runs.filter(run => run[field] > 0);
        if (validRuns.length === 0) return 0;
        return validRuns.reduce((sum, run) => sum + run[field], 0) / validRuns.length;
    }

    async generateParetoAnalysis(runs) {
        const paretoSection = document.getElementById('pareto-section');
        
        if (!this.paretoManager || runs.length === 0) {
            paretoSection.style.display = 'none';
            return;
        }

        try {
            // Show the Pareto section
            paretoSection.style.display = 'block';
            
            // Generate Pareto analysis
            const sweepId = runs[0].sweepId || new Date(runs[0].createdAt).toDateString();
            await this.paretoManager.generateParetoAnalysis(sweepId, runs);
            
        } catch (error) {
            console.error('Failed to generate Pareto analysis:', error);
            paretoSection.style.display = 'none';
        }
    }

    groupBySweep(runs) {
        const groups = {};
        runs.forEach(run => {
            // Only include runs with valid recall data
            if (run.recall > 0) {
                const sweepId = run.sweepId || new Date(run.createdAt).toDateString();
                if (!groups[sweepId]) {
                    groups[sweepId] = [];
                }
                groups[sweepId].push(run);
            }
        });
        return groups;
    }

    generateCharts() {
        const chartsGrid = document.getElementById('charts-grid');
        
        if (this.data.length === 0) {
            chartsGrid.innerHTML = '<div class="no-data">No benchmark data available</div>';
            return;
        }

        // Filter data for valid runs
        const allValidRuns = this.data.filter(run => {
            // Apply algorithm filter
            if (this.currentAlgo !== 'all' && run.algo !== this.currentAlgo) {
                return false;
            }
            // Only include runs with valid data for the selected metric AND valid recall
            return run[this.currentMetric] > 0 && run.recall > 0;
        });

        // Group runs by unique parameter configuration to avoid duplicates
        const uniqueConfigs = {};
        allValidRuns.forEach(run => {
            const configKey = this.createConfigKey(run);
            if (!uniqueConfigs[configKey]) {
                uniqueConfigs[configKey] = [];
            }
            uniqueConfigs[configKey].push(run);
        });

        // Convert to array of representative runs (one per unique config)
        const validRuns = Object.values(uniqueConfigs).map(configRuns => configRuns[0]);

        if (validRuns.length === 0) {
            chartsGrid.innerHTML = '<div class="no-data">No valid runs found for current filters</div>';
            return;
        }

        chartsGrid.innerHTML = '';

        console.log(`Generating charts for ${validRuns.length} unique configurations from ${allValidRuns.length} total valid runs`);

        // Clear any existing charts first
        this.charts.forEach(chart => chart.destroy());
        this.charts.clear();

        // For scalability: if we have too many runs, show a warning and limit display
        const maxCharts = 50; // Reasonable limit for performance
        
        if (validRuns.length > maxCharts) {
            chartsGrid.innerHTML = `
                <div class="warning">
                    <h3>⚠️ Too Many Runs</h3>
                    <p>Found ${validRuns.length} runs, but displaying only the first ${maxCharts} for performance reasons.</p>
                    <p>Consider using filters to narrow down the results.</p>
                </div>
            `;
            validRuns.slice(0, maxCharts).forEach((run, index) => {
                const chartCard = this.createIndividualRunChart(run, index);
                chartsGrid.appendChild(chartCard);
            });
        } else {
            // Create a chart for each individual run showing its performance across sweeps
            validRuns.forEach((run, index) => {
                // console.log(`Creating chart ${index + 1}/${validRuns.length} for run ${run.runId}`);
                const chartCard = this.createIndividualRunChart(run, index);
                chartsGrid.appendChild(chartCard);
            });
        }
    }

    groupRunsByUniqueConfig(runs) {
        const groups = {};
        
        runs.forEach(run => {
            const configKey = this.createConfigKey(run);
            if (!groups[configKey]) {
                groups[configKey] = [];
            }
            groups[configKey].push(run);
        });

        return groups;
    }

    createConfigKey(run) {
        // Create a unique key based on algorithm and all parameters
        const params = [];
        
        if (run.algo === 'CAGRA_HNSW') {
            params.push(`degree:${run.cagraGraphDegree || 0}`);
            params.push(`intermediate:${run.cagraIntermediateGraphDegree || 0}`);
            params.push(`threads:${run.cuvsWriterThreads || 0}`);
        } else if (run.algo === 'LUCENE_HNSW') {
            params.push(`maxConn:${run.hnswMaxConn || 0}`);
            params.push(`beamWidth:${run.hnswBeamWidth || 0}`);
        }
        
        return `${run.algo}_${params.join('_')}`;
    }

    createConfigChart(configKey, runs, index) {
        const chartCard = document.createElement('div');
        chartCard.className = 'chart-card';
        
        const chartId = `chart-config-${this.generateId(configKey)}`;
        const metricName = this.currentMetric === 'indexingTime' ? 'Indexing Time' : 'Query Time';
        
        // Create a descriptive title for this configuration
        const configTitle = this.createConfigTitle(runs[0]);
        
        chartCard.innerHTML = `
            <div class="chart-title">${configTitle}</div>
            <div class="chart-container">
                <canvas id="${chartId}"></canvas>
            </div>
        `;
        
        // Create the chart
        setTimeout(() => {
            const canvas = document.getElementById(chartId);
            if (canvas) {
                this.createConfigLineChart(chartId, runs, metricName);
            } else {
                console.error(`Canvas element not found: ${chartId}`);
            }
        }, 200);
        
        return chartCard;
    }

    createConfigTitle(run) {
        const algoName = run.algo.replace('_HNSW', '');
        const params = [];
        
        // Add specific parameters based on algorithm
        if (run.algo === 'CAGRA_HNSW') {
            if (run.cagraGraphDegree > 0) {
                params.push(`Degree: ${run.cagraGraphDegree}`);
            }
            if (run.cagraIntermediateGraphDegree > 0) {
                params.push(`Intermediate: ${run.cagraIntermediateGraphDegree}`);
            }
            if (run.cuvsWriterThreads > 0) {
                params.push(`Threads: ${run.cuvsWriterThreads}`);
            }
        } else if (run.algo === 'LUCENE_HNSW') {
            if (run.hnswMaxConn > 0) {
                params.push(`MaxConn: ${run.hnswMaxConn}`);
            }
            if (run.hnswBeamWidth > 0) {
                params.push(`BeamWidth: ${run.hnswBeamWidth}`);
            }
        }
        
        const paramStr = params.length > 0 ? ` (${params.join(', ')})` : '';
        return `${algoName}${paramStr}`;
    }

    createConfigLineChart(canvasId, runs, metricName) {
        const ctx = document.getElementById(canvasId);
        if (!ctx) {
            console.error(`Canvas element not found: ${canvasId}`);
            return;
        }

        // console.log(`Creating config chart for ${canvasId} with ${runs.length} runs`);

        // Group runs by sweep
        const sweepGroups = {};
        runs.forEach(run => {
            const sweepId = run.sweepId;
            if (!sweepGroups[sweepId]) {
                sweepGroups[sweepId] = [];
            }
            sweepGroups[sweepId].push(run);
        });

        // Calculate data points for each sweep
        const sweepData = Object.entries(sweepGroups).map(([sweepId, sweepRuns]) => {
            const avgValue = sweepRuns.reduce((sum, run) => sum + run[this.currentMetric], 0) / sweepRuns.length;
            const sweepName = this.createSweepDisplayName(sweepId, sweepRuns[0]);
            
            return {
                label: sweepName,
                value: avgValue / 1000, // Convert to seconds
                sweepId: sweepId,
                runCount: sweepRuns.length
            };
        }).sort((a, b) => a.sweepId.localeCompare(b.sweepId));

        const labels = sweepData.map(item => item.label);
        const data = sweepData.map(item => item.value);

        console.log(`Chart ${canvasId} data:`, { labels, data, sweepData });

        if (data.length === 0) {
            console.warn(`No data for chart ${canvasId} - hiding chart`);
            const chartContainer = document.querySelector(`#${canvasId}`).closest('.chart-card');
            if (chartContainer) {
                chartContainer.style.display = 'none';
            }
            return;
        }

        const chart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: labels,
                datasets: [{
                    label: runs[0].algo.replace('_HNSW', ''),
                    data: data,
                    borderColor: runs[0].algo === 'CAGRA_HNSW' ? '#2196f3' : '#ff9800',
                    backgroundColor: runs[0].algo === 'CAGRA_HNSW' ? 'rgba(33, 150, 243, 0.1)' : 'rgba(255, 152, 0, 0.1)',
                    borderWidth: 2,
                    fill: false,
                    tension: 0.1,
                    pointRadius: 6,
                    pointHoverRadius: 8
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: {
                        beginAtZero: true,
                        title: {
                            display: true,
                            text: `${metricName} (seconds)`
                        }
                    },
                    x: {
                        title: {
                            display: true,
                            text: 'Sweep'
                        }
                    }
                },
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        callbacks: {
                            label: function(context) {
                                const dataPoint = sweepData[context.dataIndex];
                                return `${context.dataset.label}: ${context.parsed.y.toFixed(2)}s (${dataPoint.runCount} runs)`;
                            }
                        }
                    }
                }
            }
        });
        
        this.charts.set(canvasId, chart);
    }

    groupByParameterConfig(runs) {
        const groups = {};
        
        runs.forEach(run => {
            // Apply filters
            if (this.currentAlgo !== 'all' && run.algo !== this.currentAlgo) {
                return;
            }
            
            // Only include runs with valid data for the selected metric AND valid recall
            if (run[this.currentMetric] > 0 && run.recall > 0) {
                const paramKey = this.createParameterKey(run);
                if (!groups[paramKey]) {
                    groups[paramKey] = [];
                }
                groups[paramKey].push(run);
            }
        });

        return groups;
    }

    createConfigKey(run) {
        // Create a unique key based on algorithm and all parameters
        const params = [];
        
        if (run.algo === 'CAGRA_HNSW') {
            params.push(`degree:${run.cagraGraphDegree || 0}`);
            params.push(`intermediate:${run.cagraIntermediateGraphDegree || 0}`);
            params.push(`threads:${run.cuvsWriterThreads || 0}`);
        } else if (run.algo === 'LUCENE_HNSW') {
            params.push(`maxConn:${run.hnswMaxConn || 0}`);
            params.push(`beamWidth:${run.hnswBeamWidth || 0}`);
        }
        
        return `${run.algo}_${params.join('_')}`;
    }

    createParameterKey(run) {
        const params = [];
        
        // For CAGRA algorithms, include CAGRA-specific parameters
        if (run.algo === 'CAGRA_HNSW') {
            if (run.cagraGraphDegree > 0) {
                params.push(`Degree: ${run.cagraGraphDegree}`);
            }
            if (run.cagraIntermediateGraphDegree > 0) {
                params.push(`Intermediate: ${run.cagraIntermediateGraphDegree}`);
            }
            if (run.cuvsWriterThreads > 0) {
                params.push(`Threads: ${run.cuvsWriterThreads}`);
            }
        }
        
        // For Lucene algorithms, include Lucene-specific parameters
        if (run.algo === 'LUCENE_HNSW') {
            if (run.hnswMaxConn > 0) {
                params.push(`MaxConn: ${run.hnswMaxConn}`);
            }
            if (run.hnswBeamWidth > 0) {
                params.push(`BeamWidth: ${run.hnswBeamWidth}`);
            }
        }
        
        const algoName = run.algo.replace('_HNSW', '');
        const paramStr = params.length > 0 ? ` (${params.join(', ')})` : '';
        const key = `${algoName}${paramStr}`;
        return key;
    }

    createIndividualRunChart(run, index) {
        const chartCard = document.createElement('div');
        chartCard.className = 'chart-card';
        
        const chartId = `chart-run-${this.generateId(run.runId)}`;
        const metricName = this.currentMetric === 'indexingTime' ? 'Indexing Time' : 'Query Time';
        
        // Create a descriptive title for this run
        const runTitle = this.createRunTitle(run);
        
        console.log(`Creating chart card for run ${run.runId} with ID: ${chartId}`);
        
        chartCard.innerHTML = `
            <div class="chart-title">${runTitle}</div>
            <div class="chart-container">
                <canvas id="${chartId}"></canvas>
            </div>
        `;
        
        // Create the chart
        setTimeout(() => {
            const canvas = document.getElementById(chartId);
            if (canvas) {
                console.log(`Creating chart for canvas ${chartId}`);
                this.createIndividualRunLineChart(chartId, run, metricName);
            } else {
                console.error(`Canvas element not found: ${chartId}`);
            }
        }, 200);
        
        return chartCard;
    }

    createRunTitle(run) {
        const algoName = run.algo.replace('_HNSW', '');
        const params = [];
        
        // Add specific parameters based on algorithm
        if (run.algo === 'CAGRA_HNSW') {
            if (run.cagraGraphDegree > 0) {
                params.push(`Degree: ${run.cagraGraphDegree}`);
            }
            if (run.cagraIntermediateGraphDegree > 0) {
                params.push(`Intermediate: ${run.cagraIntermediateGraphDegree}`);
            }
            if (run.cuvsWriterThreads > 0) {
                params.push(`Threads: ${run.cuvsWriterThreads}`);
            }
        } else if (run.algo === 'LUCENE_HNSW') {
            if (run.hnswMaxConn > 0) {
                params.push(`MaxConn: ${run.hnswMaxConn}`);
            }
            if (run.hnswBeamWidth > 0) {
                params.push(`BeamWidth: ${run.hnswBeamWidth}`);
            }
        }
        
        const paramStr = params.length > 0 ? ` (${params.join(', ')})` : '';
        return `${algoName}${paramStr}`;
    }

    createIndividualRunLineChart(canvasId, targetRun, metricName) {
        const ctx = document.getElementById(canvasId);
        if (!ctx) {
            console.error(`Canvas element not found: ${canvasId}`);
            return;
        }

        // console.log(`Creating line chart for ${canvasId}, target run: ${targetRun.runId}`);

        // Find all runs with the same parameter configuration across different sweeps
        const sameConfigRuns = this.data.filter(run => {
            return run.algo === targetRun.algo &&
                   run.cagraGraphDegree === targetRun.cagraGraphDegree &&
                   run.cagraIntermediateGraphDegree === targetRun.cagraIntermediateGraphDegree &&
                   run.cuvsWriterThreads === targetRun.cuvsWriterThreads &&
                   run.hnswMaxConn === targetRun.hnswMaxConn &&
                   run.hnswBeamWidth === targetRun.hnswBeamWidth &&
                   run[this.currentMetric] > 0 &&
                   run.recall > 0;
        });

        // If no other runs with same config found, just use this run
        const runsToUse = sameConfigRuns.length > 0 ? sameConfigRuns : [targetRun];

        // console.log(`Found ${sameConfigRuns.length} runs with same config as ${targetRun.runId}`);

        // Group by sweep and calculate average for each sweep
        const sweepGroups = {};
        runsToUse.forEach(run => {
            const sweepId = run.sweepId;
            if (!sweepGroups[sweepId]) {
                sweepGroups[sweepId] = [];
            }
            sweepGroups[sweepId].push(run);
        });

        // Calculate data points for each sweep
        const sweepData = Object.entries(sweepGroups).map(([sweepId, sweepRuns]) => {
            const avgValue = sweepRuns.reduce((sum, run) => sum + run[this.currentMetric], 0) / sweepRuns.length;
            const sweepName = this.createSweepDisplayName(sweepId, sweepRuns[0]);
            
            return {
                label: sweepName,
                value: avgValue / 1000, // Convert to seconds
                sweepId: sweepId,
                runCount: sweepRuns.length
            };
        }).sort((a, b) => a.sweepId.localeCompare(b.sweepId));

        const labels = sweepData.map(item => item.label);
        const data = sweepData.map(item => item.value);

        console.log(`Chart ${canvasId} data:`, { labels, data, sweepData });

        if (data.length === 0) {
            console.warn(`No data for chart ${canvasId} - skipping chart creation`);
            // Hide the chart container instead of creating an empty chart
            const chartContainer = document.querySelector(`#${canvasId}`).closest('.chart-card');
            if (chartContainer) {
                chartContainer.style.display = 'none';
            }
            return;
        }

        // If we only have one data point, duplicate it to show a line
        if (data.length === 1) {
            labels.push(labels[0] + ' (duplicate)');
            data.push(data[0]);
        }

        const chart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: labels,
                datasets: [{
                    label: targetRun.algo.replace('_HNSW', ''),
                    data: data,
                    borderColor: targetRun.algo === 'CAGRA_HNSW' ? '#2196f3' : '#ff9800',
                    backgroundColor: targetRun.algo === 'CAGRA_HNSW' ? 'rgba(33, 150, 243, 0.1)' : 'rgba(255, 152, 0, 0.1)',
                    borderWidth: 2,
                    fill: false,
                    tension: 0.1,
                    pointRadius: 6,
                    pointHoverRadius: 8
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: {
                        beginAtZero: true,
                        title: {
                            display: true,
                            text: `${metricName} (seconds)`
                        }
                    },
                    x: {
                        title: {
                            display: true,
                            text: 'Sweep'
                        }
                    }
                },
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        callbacks: {
                            label: function(context) {
                                const dataPoint = sweepData[context.dataIndex];
                                return `${context.dataset.label}: ${context.parsed.y.toFixed(2)}s (${dataPoint.runCount} runs)`;
                            }
                        }
                    }
                }
            }
        });
        
        this.charts.set(canvasId, chart);
    }

    createChartCard(paramKey, runs) {
        const chartCard = document.createElement('div');
        chartCard.className = 'chart-card';
        
        const chartId = `chart-${this.generateId(paramKey)}`;
        const metricName = this.currentMetric === 'indexingTime' ? 'Indexing Time' : 'Query Time';
        
        chartCard.innerHTML = `
            <div class="chart-title">${paramKey}</div>
            <div class="chart-container">
                <canvas id="${chartId}"></canvas>
            </div>
        `;
        
        // Create the chart
        setTimeout(() => {
            console.log(`Creating chart for ${paramKey} with canvas ID: ${chartId}`);
            const canvas = document.getElementById(chartId);
            if (canvas) {
                this.createLineChart(chartId, runs, metricName);
            } else {
                console.error(`Canvas element not found: ${chartId}`);
            }
        }, 200);
        
        return chartCard;
    }

    createLineChart(canvasId, runs, metricName) {
        const ctx = document.getElementById(canvasId);
        if (!ctx) {
            console.error(`Canvas element not found: ${canvasId}`);
            return;
        }
        console.log(`Creating chart for ${canvasId} with ${runs.length} runs`);
        console.log('Runs data:', runs.map(r => ({ runId: r.runId, sweepId: r.sweepId, indexingTime: r.indexingTime, recall: r.recall })));
        
        // Group runs by sweep and calculate average for each sweep
        const sweepGroups = {};
        runs.forEach(run => {
            const sweepId = run.sweepId;
            if (!sweepGroups[sweepId]) {
                sweepGroups[sweepId] = [];
            }
            sweepGroups[sweepId].push(run);
        });
        
        // Calculate average for each sweep
        const sweepData = Object.entries(sweepGroups).map(([sweepId, sweepRuns]) => {
            const validRuns = sweepRuns.filter(run => run[this.currentMetric] > 0);
            if (validRuns.length === 0) return null;
            
            const avgValue = validRuns.reduce((sum, run) => sum + run[this.currentMetric], 0) / validRuns.length;
            const sweepName = this.createSweepDisplayName(sweepId, validRuns[0]);
            
            return {
                label: sweepName,
                value: avgValue / 1000, // Convert to seconds
                sweepId: sweepId
            };
        }).filter(item => item !== null);
        
        // Sort by sweep order (by date)
        sweepData.sort((a, b) => a.sweepId.localeCompare(b.sweepId));
        
        const labels = sweepData.map(item => item.label);
        const data = sweepData.map(item => item.value);
        
        console.log(`Chart data for ${canvasId}:`, { labels, data, sweepData });
        
        if (data.length === 0) {
            console.warn(`No data for chart ${canvasId}`);
            return;
        }
        
        const chart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: labels,
                datasets: [{
                    label: runs[0].algo.replace('_HNSW', ''),
                    data: data,
                    borderColor: runs[0].algo === 'CAGRA_HNSW' ? '#2196f3' : '#ff9800',
                    backgroundColor: runs[0].algo === 'CAGRA_HNSW' ? 'rgba(33, 150, 243, 0.1)' : 'rgba(255, 152, 0, 0.1)',
                    borderWidth: 2,
                    fill: false,
                    tension: 0.1,
                    pointRadius: 6,
                    pointHoverRadius: 8
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: {
                        beginAtZero: true,
                        title: {
                            display: true,
                            text: `${metricName} (seconds)`
                        }
                    },
                    x: {
                        title: {
                            display: true,
                            text: 'Sweep'
                        }
                    }
                },
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        callbacks: {
                            label: function(context) {
                                return `${context.dataset.label}: ${context.parsed.y.toFixed(2)}s`;
                            }
                        }
                    }
                }
            }
        });
        
        this.charts.set(canvasId, chart);
    }

    createSweepDisplayName(sweepId, sampleRun) {
        // Extract date and time from sweepId for unique identification
        if (sweepId.includes('_')) {
            // Extract date and time from sweepId like "sift-1m_20250916_0830_CAGRA_vs_Lucene_Algorithm_Comparison_algorithm_comparison_yaml"
            const parts = sweepId.split('_');
            if (parts.length >= 3) {
                const datePart = parts[1]; // "20250916"
                const timePart = parts[2]; // "0830"
                
                if (datePart.length === 8 && timePart.length === 4) {
                    const year = datePart.substring(0, 4);
                    const month = datePart.substring(4, 6);
                    const day = datePart.substring(6, 8);
                    const hour = timePart.substring(0, 2);
                    const minute = timePart.substring(2, 4);
                    
                    // Include time to make each sweep unique, even on the same day
                    return `${day}/${month}/${year} ${hour}:${minute}`;
                }
            }
        }
        
        // Fallback: use createdAt timestamp if available
        if (sampleRun && sampleRun.createdAt) {
            const date = new Date(sampleRun.createdAt);
            return `${date.toLocaleDateString()} ${date.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}`;
        }
        
        // Final fallback: show first part of sweepId with unique suffix
        const shortId = sweepId.substring(0, 20);
        const hashSuffix = sweepId.split('_').pop().substring(0, 8);
        return `${shortId}...${hashSuffix}`;
    }

    generateId(str) {
        return str.replace(/[^a-zA-Z0-9]/g, '').toLowerCase().substring(0, 20);
    }

    async viewRunConfig(runId) {
        try {
            console.log(`Loading configuration for run ${runId}`);
            
            // Try to fetch the results.json file for this run
            const response = await fetch(`../runs/${runId}/results.json`);
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            const configData = await response.json();
            
            // Display the configuration in the modal
            this.showConfigModal(runId, configData);
            
        } catch (error) {
            console.error('Error loading run configuration:', error);
            alert(`Failed to load configuration for run ${runId}: ${error.message}`);
        }
    }

    async viewRunMetrics(runId) {
        try {
            console.log(`Loading metrics for run ${runId}`);
            
            // Show metrics modal
            this.showMetricsModal(runId);
            
        } catch (error) {
            console.error('Error loading run metrics:', error);
            alert(`Failed to load metrics for run ${runId}: ${error.message}`);
        }
    }

    initializeMetricsManager() {
        if (window.MetricsManager) {
            this.metricsManager = new MetricsManager();
        }
    }

    initializeParetoManager() {
        if (window.ParetoManager) {
            this.paretoManager = new ParetoManager(this.dataLoader);
        }
    }

    showConfigModal(runId, configData) {
        const modal = document.getElementById('config-modal');
        const title = document.getElementById('config-modal-title');
        const content = document.getElementById('config-json-display');
        
        title.textContent = `Configuration for Run ${runId.substring(0, 8)}`;
        content.textContent = JSON.stringify(configData, null, 2);
        
        modal.style.display = 'block';
    }

    showMetricsModal(runId) {
        const modal = document.getElementById('metrics-modal');
        const title = document.getElementById('metrics-modal-title');
        
        title.textContent = `Metrics for Run ${runId.substring(0, 8)}`;
        
        // Load and display metrics
        if (this.metricsManager) {
            this.metricsManager.selectRun(runId);
        } else {
            document.getElementById('metrics-container').innerHTML = 
                '<p>Metrics manager not available</p>';
        }
        
        modal.style.display = 'block';
    }

    async downloadRunLogs(runId) {
        try {
            console.log(`Downloading logs for run ${runId}`);
            
            // Try to fetch the log files for this run
            const logTypes = ['stdout', 'stderr'];
            
            for (const logType of logTypes) {
                try {
                    const response = await fetch(`../runs/${runId}/${runId}_${logType}.log`);
                    
                    if (response.ok) {
                        const logContent = await response.text();
                        this.downloadTextFile(`${runId}_${logType}.log`, logContent);
                    } else {
                        console.warn(`Log file ${logType} not found for run ${runId}`);
                    }
                } catch (error) {
                    console.warn(`Failed to download ${logType} log for run ${runId}:`, error);
                }
            }
            
        } catch (error) {
            console.error('Error downloading run logs:', error);
            alert(`Failed to download logs for run ${runId}: ${error.message}`);
        }
    }

    downloadTextFile(filename, content) {
        const blob = new Blob([content], { type: 'text/plain' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
    }

    showError(message) {
        const chartsGrid = document.getElementById('charts-grid');
        chartsGrid.innerHTML = `<div class="error">${message}</div>`;
    }
}

// Initialize the dashboard when the page loads
let dashboard;
document.addEventListener('DOMContentLoaded', () => {
    dashboard = new BenchmarkDashboard();
    dashboard.init();
    // Make dashboard globally available for button clicks
    window.dashboard = dashboard;
});
