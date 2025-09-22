class BenchmarkDashboard {
    constructor() {
        this.sweeps = [];
        this.currentSweep = null;
        this.charts = {};
        this.sortState = {
            column: null,
            direction: 'asc' // 'asc' or 'desc'
        };
        
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
            console.error('Init error:', error);
            this.showError('Failed to load benchmark data: ' + error.message);
        }
    }

    async loadSweeps() {
        try {
            // Load sweeps list from sweeps-list.json
            console.log('Loading sweeps list...');
            const response = await fetch('results/sweeps-list.json');
            if (!response.ok) {
                throw new Error(`Failed to load sweeps list: ${response.status}`);
            }
            
            const sweepsData = await response.json();
            console.log('Sweeps data:', sweepsData);
            const sweepIds = sweepsData.sweeps || [];
            
            this.sweeps = [];
            for (const sweepId of sweepIds) {
                try {
                    console.log(`Loading sweep ${sweepId}...`);
                    const sweepData = await this.loadSweepData(sweepId);
                    this.sweeps.push(sweepData);
                } catch (error) {
                    console.warn(`Failed to load sweep ${sweepId}:`, error);
                }
            }

            // Sort by date (newest first) if dates are available
            this.sweeps.sort((a, b) => {
                const dateA = a.date ? new Date(a.date) : new Date(0);
                const dateB = b.date ? new Date(b.date) : new Date(0);
                return dateB - dateA;
            });
            
            // Extract available datasets from sweep runs
            this.extractAvailableDatasets();
        } catch (error) {
            throw new Error(`Failed to load sweeps: ${error.message}`);
        }
    }
    
    extractAvailableDatasets() {
        // Extract unique datasets from all sweep runs
        const datasets = new Set();
        this.sweeps.forEach(sweep => {
            sweep.runs.forEach(run => {
                if (run.dataset) {
                    datasets.add(run.dataset);
                }
            });
        });
        this.availableDatasets = Array.from(datasets).sort();
        this.populateDatasetFilter();
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
        // Load summary.txt to get the list of configs
        console.log(`Fetching summary for ${sweepId}...`);
        const summaryResponse = await fetch(`/results/${sweepId}/summary.txt`);
        if (!summaryResponse.ok) {
            throw new Error(`Failed to load summary for sweep ${sweepId}`);
        }
        
        const summaryText = await summaryResponse.text();
        console.log(`Summary text for ${sweepId}:`, summaryText);
        const configs = this.parseConfigsFromSummary(summaryText);
        console.log(`Configs found for ${sweepId}:`, configs);
        
        // Load each config's results.json
        const runs = [];
        for (const config of configs) {
            try {
                const resultsResponse = await fetch(`/results/${sweepId}/${config}/results.json`);
                if (!resultsResponse.ok) {
                    console.warn(`Failed to load results for ${config}`);
                    continue;
                }
                
                const resultsData = await resultsResponse.json();
                const run = this.extractRunFromResults(resultsData, config);
                runs.push(run);
            } catch (error) {
                console.warn(`Error loading results for ${config}:`, error);
            }
        }

        // Extract date from summary or use current date
        const dateMatch = summaryText.match(/Started at: .* (\d{2}) (\w+) (\d{4})/);
        let date = 'Unknown';
        if (dateMatch) {
            const months = {Jan:1,Feb:2,Mar:3,Apr:4,May:5,Jun:6,Jul:7,Aug:8,Sep:9,September:9,Oct:10,Nov:11,Dec:12};
            const month = months[dateMatch[2]] || dateMatch[2];
            date = `${dateMatch[3]}-${String(month).padStart(2, '0')}-${dateMatch[1]}`;
        }
        
        // Try to get commit_id from the sweep directory if available
        let commit_id = 'Unknown';
        try {
            const sweepJsonResponse = await fetch(`/results/${sweepId}/sweeps.json`);
            if (sweepJsonResponse.ok) {
                const sweepJson = await sweepJsonResponse.json();
                commit_id = sweepJson.commit_id || commit_id;
            }
        } catch (error) {
            // Ignore error, use default
        }

        return {
            id: sweepId,
            date: date,
            commit_id: commit_id,
            runs: runs,
            totalRuns: runs.length,
            algorithms: [...new Set(runs.map(run => run.algorithm))],
            datasets: [...new Set(runs.map(run => run.dataset))]
        };
    }

    parseConfigsFromSummary(summaryText) {
        const configs = [];
        const lines = summaryText.split('\n');
        
        for (const line of lines) {
            // Match lines like "sift-1m/CAGRA_HNSW-3a337168: SUCCESS" or just "sift-1m/CAGRA_HNSW-3a337168:"
            const match = line.match(/^([\w-]+\/[\w-]+):/);
            if (match) {
                const configPath = match[1].trim();
                configs.push(configPath);
            }
        }
        
        return configs;
    }
    
    extractRunFromResults(resultsData, configPath) {
        const config = resultsData.configuration;
        const metrics = resultsData.metrics;
        
        // Extract dataset and run ID from config path
        const pathParts = configPath.split('/');
        const dataset = pathParts[0];
        const algorithmAndId = pathParts[pathParts.length - 1];
        const [algorithm, runId] = algorithmAndId.split('-');
        
        // Determine metric prefixes based on algorithm
        const algoType = config.algoToRun || algorithm;
        let recallKey, indexingTimeKey, indexSizeKey;
        
        if (algoType === 'CAGRA_HNSW') {
            recallKey = 'cuvs-recall-accuracy';
            indexingTimeKey = 'cuvs-indexing-time';
            indexSizeKey = 'cuvs-index-size';
        } else if (algoType === 'LUCENE_HNSW') {
            recallKey = 'hnsw-recall-accuracy';
            indexingTimeKey = 'hnsw-indexing-time';
            indexSizeKey = 'hnsw-index-size';
        } else {
            // Fallback: try both prefixes
            recallKey = metrics['cuvs-recall-accuracy'] !== undefined ? 'cuvs-recall-accuracy' : 'hnsw-recall-accuracy';
            indexingTimeKey = metrics['cuvs-indexing-time'] !== undefined ? 'cuvs-indexing-time' : 'hnsw-indexing-time';
            indexSizeKey = metrics['cuvs-index-size'] !== undefined ? 'cuvs-index-size' : 'hnsw-index-size';
        }
        
        const extractedRun = {
            run_id: algorithmAndId,
            dataset: dataset,
            algorithm: algoType,
            recall: metrics[recallKey] || 0,
            indexingTime: (metrics[indexingTimeKey] || 0) / 1000, // Convert ms to seconds
            indexSize: metrics[indexSizeKey] || 0,
            meanLatency: metrics['hnsw-mean-latency'] || 0,
            queryThroughput: metrics['hnsw-query-throughput'] || 0,
            // Include algorithm-specific parameters
            cagraGraphDegree: config.cagraGraphDegree,
            cagraIntermediateGraphDegree: config.cagraIntermediateGraphDegree,
            hnswMaxConn: config.hnswMaxConn,
            hnswBeamWidth: config.hnswBeamWidth,
            efSearch: config.efSearch || config.effectiveEfSearch,
            numDocs: config.numDocs,
            topK: config.topK
        };
        
        console.log(`Extracted run for ${algoType}:`, {
            recallKey, indexingTimeKey, indexSizeKey,
            recall: extractedRun.recall,
            indexingTime: extractedRun.indexingTime,
            metrics: Object.keys(metrics)
        });
        
        return extractedRun;
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
        const chartContainer = document.querySelector('.speedup-chart');
        const messageContainer = document.getElementById('pareto-message');
        
        // Destroy existing chart
        if (this.charts.speedup) {
            this.charts.speedup.destroy();
        }

        // Check if we should show the pareto analysis
        if (!this.shouldShowParetoAnalysis(sweep)) {
            // Hide the chart and show a message
            chartContainer.style.display = 'none';
            messageContainer.style.display = 'block';
            return;
        }
        
        // Show the chart container and hide the message
        chartContainer.style.display = 'block';
        messageContainer.style.display = 'none';

        // Pareto analysis: Find best runs at specific recall levels
        const recallLevels = [90, 95];
        const algorithms = ['CAGRA_HNSW', 'LUCENE_HNSW'];
        
        // For each recall level and algorithm, find the run with best indexing time
        // that meets the recall threshold
        const paretoData = this.calculateParetoData(sweep.runs, recallLevels, algorithms);
        console.log('Pareto data calculated:', paretoData);
        
        // Only show recall levels where at least one algorithm has data
        const validRecallLevels = [];
        const validLabels = [];
        const validData = algorithms.map(algo => []);
        const multipliers = [];
        
        recallLevels.forEach(level => {
            const levelData = paretoData[level];
            const hasData = algorithms.some(algo => levelData[algo] !== null);
            
            if (hasData) {
                validRecallLevels.push(level);
                validLabels.push(`${level}% Recall`);
                
                algorithms.forEach((algo, algoIndex) => {
                    const runData = levelData[algo];
                    validData[algoIndex].push(runData ? runData.indexingTime : null);
                });
                
                // Calculate multiplier improvement (LUCENE time / CAGRA time)
                const cagraTime = levelData['CAGRA_HNSW']?.indexingTime;
                const luceneTime = levelData['LUCENE_HNSW']?.indexingTime;
                if (cagraTime && luceneTime) {
                    multipliers.push((luceneTime / cagraTime).toFixed(1));
                } else {
                    multipliers.push(null);
                }
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
                        text: 'Best Indexing Times by Recall Level (Pareto Analysis)'
                    },
                    legend: {
                        display: true,
                        position: 'top'
                    },
                    tooltip: {
                        callbacks: {
                            afterLabel: function(context) {
                                const multiplier = multipliers[context.dataIndex];
                                if (multiplier && context.datasetIndex === 0) { // Show on CAGRA bars
                                    return `Speedup: ${multiplier}x`;
                                }
                                return '';
                            }
                        }
                    },
                    datalabels: {
                        display: function(context) {
                            // Only show speedup labels on CAGRA bars when both algorithms have data
                            if (context.datasetIndex === 0) { // CAGRA dataset
                                const multiplier = multipliers[context.dataIndex];
                                return multiplier !== null;
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
                            const multiplier = multipliers[context.dataIndex];
                            if (multiplier) {
                                return `${multiplier}x faster`;
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
    
    calculateParetoData(runs, recallLevels, algorithms) {
        const paretoData = {};
        
        recallLevels.forEach(level => {
            paretoData[level] = {};
            
            algorithms.forEach(algo => {
                // Find all runs for this algorithm that meet or exceed the recall threshold
                const eligibleRuns = runs.filter(run => 
                    run.algorithm === algo && 
                    parseFloat(run.recall) >= level
                );
                
                if (eligibleRuns.length > 0) {
                    // Find the run with the best (lowest) indexing time
                    const bestRun = eligibleRuns.reduce((best, current) => {
                        const currentTime = parseFloat(current.indexingTime);
                        const bestTime = parseFloat(best.indexingTime);
                        return currentTime < bestTime ? current : best;
                    });
                    
                    paretoData[level][algo] = {
                        run: bestRun,
                        indexingTime: parseFloat(bestRun.indexingTime),
                        recall: parseFloat(bestRun.recall),
                        runId: bestRun.run_id
                    };
                } else {
                    paretoData[level][algo] = null;
                }
            });
        });
        
        return paretoData;
    }
    
    shouldShowParetoAnalysis(sweep) {
        // Check filter selections
        const selectedDataset = document.getElementById('sweep-dataset-filter')?.value || 'all';
        const selectedAlgorithm = document.getElementById('sweep-algorithm-filter')?.value || 'all';
        
        // Only show when "all algorithms" is selected
        if (selectedAlgorithm !== 'all') {
            console.log('Hiding pareto analysis: algorithm filter is not "all"');
            return false;
        }
        
        // Check if we have a single dataset (either naturally or by filter)
        const uniqueDatasets = [...new Set(sweep.runs.map(run => run.dataset))];
        
        if (selectedDataset !== 'all') {
            // A specific dataset is selected, so we effectively have a single dataset
            console.log('Showing pareto analysis: specific dataset selected');
            return true;
        } else if (uniqueDatasets.length === 1) {
            // Only one dataset present in the sweep
            console.log('Showing pareto analysis: only one dataset present');
            return true;
        } else {
            // Multiple datasets present and "all" selected
            console.log('Hiding pareto analysis: multiple datasets present and "all" selected');
            return false;
        }
    }

    renderRunsTable(sweep) {
        // Setup table headers with sorting if not already done
        this.setupTableSorting();
        
        // Sort runs if a sort column is selected
        let sortedRuns = [...sweep.runs];
        if (this.sortState.column) {
            sortedRuns = this.sortRuns(sortedRuns, this.sortState.column, this.sortState.direction);
        }
        
        const tbody = document.querySelector('#runs-table tbody');
        tbody.innerHTML = '';

        sortedRuns.forEach(run => {
            const row = document.createElement('tr');
            
            row.innerHTML = `
                <td>${this.formatRunId(run.run_id)}</td>
                <td>${run.algorithm.replace('_HNSW', '')}</td>
                <td>${parseFloat(run.recall || 0).toFixed(2)}</td>
                <td>${parseFloat(run.indexingTime || 0).toFixed(2)}</td>
                <td>${this.formatParameters(run)}</td>
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
    
    setupTableSorting() {
        const headers = document.querySelectorAll('#runs-table th');
        const sortableColumns = ['run_id', 'algorithm', 'recall', 'indexTime', 'parameters', 'meanLatency'];
        
        headers.forEach((header, index) => {
            if (index < sortableColumns.length) { // Skip the Actions column
                const columnKey = sortableColumns[index];
                header.classList.add('sortable');
                header.dataset.column = columnKey;
                
                // Remove existing click listeners to avoid duplicates
                header.replaceWith(header.cloneNode(true));
                const newHeader = document.querySelectorAll('#runs-table th')[index];
                
                newHeader.addEventListener('click', () => {
                    this.handleColumnSort(columnKey);
                });
                
                // Update sort indicator
                this.updateSortIndicator(newHeader, columnKey);
            }
        });
    }
    
    handleColumnSort(columnKey) {
        if (this.sortState.column === columnKey) {
            // Toggle direction if same column
            this.sortState.direction = this.sortState.direction === 'asc' ? 'desc' : 'asc';
        } else {
            // New column, default to ascending
            this.sortState.column = columnKey;
            this.sortState.direction = 'asc';
        }
        
        // Re-render the table with current sweep data
        if (this.currentSweep) {
            // Apply current filters
            const selectedDataset = document.getElementById('sweep-dataset-filter')?.value || 'all';
            const selectedAlgorithm = document.getElementById('sweep-algorithm-filter')?.value || 'all';
            
            const filteredRuns = this.currentSweep.runs.filter(run => {
                const datasetMatch = selectedDataset === 'all' || run.dataset === selectedDataset;
                const algorithmMatch = selectedAlgorithm === 'all' || run.algorithm === selectedAlgorithm;
                return datasetMatch && algorithmMatch;
            });
            
            this.renderRunsTable({...this.currentSweep, runs: filteredRuns});
        }
    }
    
    updateSortIndicator(header, columnKey) {
        // Remove all sort classes
        header.classList.remove('sorted-asc', 'sorted-desc');
        
        // Add appropriate class if this is the current sort column
        if (this.sortState.column === columnKey) {
            header.classList.add(this.sortState.direction === 'asc' ? 'sorted-asc' : 'sorted-desc');
        }
    }
    
    sortRuns(runs, columnKey, direction) {
        return runs.sort((a, b) => {
            let valueA, valueB;
            
            switch (columnKey) {
                case 'run_id':
                    valueA = this.formatRunId(a.run_id || '');
                    valueB = this.formatRunId(b.run_id || '');
                    break;
                case 'algorithm':
                    valueA = a.algorithm || '';
                    valueB = b.algorithm || '';
                    break;
                case 'recall':
                    valueA = parseFloat(a.recall || 0);
                    valueB = parseFloat(b.recall || 0);
                    break;
                case 'indexTime':
                    valueA = parseFloat(a.indexingTime || 0);
                    valueB = parseFloat(b.indexingTime || 0);
                    break;
                case 'parameters':
                    valueA = this.formatParameters(a);
                    valueB = this.formatParameters(b);
                    break;
                case 'meanLatency':
                    valueA = parseFloat(a.meanLatency || 0);
                    valueB = parseFloat(b.meanLatency || 0);
                    break;
                default:
                    return 0;
            }
            
            // Handle string vs number comparison
            let comparison = 0;
            if (typeof valueA === 'string' && typeof valueB === 'string') {
                comparison = valueA.localeCompare(valueB);
            } else {
                comparison = valueA - valueB;
            }
            
            return direction === 'asc' ? comparison : -comparison;
        });
    }
    
    formatRunId(runId) {
        // Extract only the hash part after the last dash
        // e.g., "CAGRA_HNSW-edee9e87" -> "edee9e87"
        if (!runId) return 'N/A';
        
        const parts = runId.split('-');
        return parts.length > 1 ? parts[parts.length - 1] : runId;
    }
    
    formatParameters(run) {
        const algorithm = run.algorithm;
        
        if (algorithm === 'CAGRA_HNSW') {
            const graphDegree = run.cagraGraphDegree || 'N/A';
            const intermediateDegree = run.cagraIntermediateGraphDegree || 'N/A';
            return `graphDegree: ${graphDegree}, intermediateDegree: ${intermediateDegree}`;
        } else if (algorithm === 'LUCENE_HNSW') {
            const maxConn = run.hnswMaxConn || 'N/A';
            const beamWidth = run.hnswBeamWidth || 'N/A';
            return `m: ${maxConn}, efConstruction: ${beamWidth}`;
        } else {
            return 'N/A';
        }
    }

    async showMetrics(runId) {
        try {
            // Find the dataset for this run
            const run = this.currentSweep.runs.find(r => r.run_id === runId);
            const dataset = run ? run.dataset : '';
            
            const memoryResponse = await fetch(`/results/${this.currentSweep.id}/${dataset}/${runId}/memory_metrics.json`);
            const memoryData = memoryResponse.ok ? await memoryResponse.json() : null;

            const cpuResponse = await fetch(`/results/${this.currentSweep.id}/${dataset}/${runId}/cpu_metrics.json`);
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
            // Find the dataset for this run
            const run = this.currentSweep.runs.find(r => r.run_id === runId);
            const dataset = run ? run.dataset : '';
            
            const response = await fetch(`/results/${this.currentSweep.id}/${dataset}/${runId}/results.json`);
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
        // Find the dataset for this run
        const run = this.currentSweep.runs.find(r => r.run_id === runId);
        const dataset = run ? run.dataset : '';
        
        // Download benchmark log
        const logLink = document.createElement('a');
        logLink.href = `/results/${sweepId}/${dataset}/${runId}/benchmark.log`;
        logLink.download = `${runId}_benchmark.log`;
        logLink.click();
    }

    async showConfiguration() {
        try {
            if (!this.currentSweep) {
                alert('No sweep selected');
                return;
            }

            const response = await fetch(`/results/${this.currentSweep.id}/sweeps.json`);
            if (!response.ok) {
                throw new Error('Failed to load sweep configuration');
            }
            
            const configData = await response.json();
            
            document.getElementById('config-title').textContent = `Configuration for Sweep ${this.currentSweep.id}`;
            document.getElementById('config-viewer').textContent = JSON.stringify(configData, null, 2);
            document.getElementById('config-modal').style.display = 'block';
        } catch (error) {
            alert('Failed to load configuration: ' + error.message);
        }
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