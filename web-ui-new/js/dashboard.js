class BenchmarkDashboard {
    constructor() {
        this.sweeps = [];
        this.currentSweep = null;
        this.charts = {};
        this.sortState = {
            column: null,
            direction: 'asc' // 'asc' or 'desc'
        };

        this.RECALL_TOLERANCE = 0.01;
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
            console.log('Loading sweeps list...');
            const response = await fetch(`results/sweeps-list.json?t=${Date.now()}`);
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

            this.sweeps.sort((a, b) => {
                const dateA = a.date ? new Date(a.date) : new Date(0);
                const dateB = b.date ? new Date(b.date) : new Date(0);
                return dateB - dateA;
            });

            this.extractAvailableDatasets();
        } catch (error) {
            throw new Error(`Failed to load sweeps: ${error.message}`);
        }
    }

    extractAvailableDatasets() {
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

        datasetFilter.addEventListener('change', () => this.applyFilters());
        document.getElementById('algorithm-filter').addEventListener('change', () => this.applyFilters());
    }

    applyFilters() {
        const selectedDataset = document.getElementById('dataset-filter').value;
        const selectedAlgorithm = document.getElementById('algorithm-filter').value;

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
        console.log(`Fetching summary for ${sweepId}...`);
        const summaryResponse = await fetch(`/results/${sweepId}/summary.txt?t=${Date.now()}`);
        if (!summaryResponse.ok) {
            throw new Error(`Failed to load summary for sweep ${sweepId}`);
        }

        const summaryText = await summaryResponse.text();
        console.log(`Summary text for ${sweepId}:`, summaryText);
        const configs = this.parseConfigsFromSummary(summaryText);
        console.log(`Configs found for ${sweepId}:`, configs);

        const runs = [];
        for (const config of configs) {
            try {
                let resultsResponse = await fetch(`/results/${sweepId}/${config}/results.json`);

                if (!resultsResponse.ok) {
                    resultsResponse = await fetch(`/results/${sweepId}/${config}/results.json`);
                }

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

        const dateMatch = summaryText.match(/Started at: .* (\d{2}) (\w+) (\d{4})/);
        let date = 'Unknown';
        if (dateMatch) {
            const months = {Jan:1,Feb:2,Mar:3,Apr:4,May:5,Jun:6,Jul:7,Aug:8,Sep:9,September:9,Oct:10,Nov:11,Dec:12};
            const month = months[dateMatch[2]] || dateMatch[2];
            date = `${dateMatch[3]}-${String(month).padStart(2, '0')}-${dateMatch[1]}`;
        }

        let commit_id = 'Unknown';
        try {
            const sweepJsonResponse = await fetch(`/results/${sweepId}/sweeps.json`);
            if (sweepJsonResponse.ok) {
                const sweepJson = await sweepJsonResponse.json();
                commit_id = sweepJson.commit_id || commit_id;
            }
        } catch (error) {
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

        const pathParts = configPath.split('/');
        const dataset = pathParts[0];
        const algorithmAndId = pathParts[pathParts.length - 1];
        const [algorithm, runId] = algorithmAndId.split('-');

        const algoType = config.algoToRun || algorithm;
        let recallKey, indexingTimeKey, indexSizeKey, meanLatencyKey;

        if (metrics['recall-accuracy'] !== undefined) {
            recallKey = 'recall-accuracy';
            indexingTimeKey = 'cuvs-indexing-time';
            indexSizeKey = 'cuvs-index-size';
            meanLatencyKey = 'mean-latency';
        } else if (algoType === 'CAGRA_HNSW' || algoType === 'cagra_hnsw') {
            recallKey = 'cuvs-recall-accuracy';
            indexingTimeKey = 'cuvs-indexing-time';
            indexSizeKey = 'cuvs-index-size';
            meanLatencyKey = 'hnsw-mean-latency';
        } else if (algoType === 'LUCENE_HNSW' || algoType === 'hnsw') {
            recallKey = 'hnsw-recall-accuracy';
            indexingTimeKey = 'hnsw-indexing-time';
            indexSizeKey = 'hnsw-index-size';
            meanLatencyKey = 'hnsw-mean-latency';
        } else {
            recallKey = metrics['recall-accuracy'] !== undefined ? 'recall-accuracy' :
                       metrics['cuvs-recall-accuracy'] !== undefined ? 'cuvs-recall-accuracy' : 'hnsw-recall-accuracy';
            indexingTimeKey = metrics['cuvs-indexing-time'] !== undefined ? 'cuvs-indexing-time' : 'hnsw-indexing-time';
            indexSizeKey = metrics['cuvs-index-size'] !== undefined ? 'cuvs-index-size' : 'hnsw-index-size';
            meanLatencyKey = metrics['mean-latency'] !== undefined ? 'mean-latency' : 'hnsw-mean-latency';
        }

        let normalizedAlgorithm = algoType;
        if (algoType === 'cagra_hnsw') {
            normalizedAlgorithm = 'CAGRA_HNSW';
        } else if (algoType === 'hnsw') {
            normalizedAlgorithm = 'LUCENE_HNSW';
        }

        const extractedRun = {
            run_id: algorithmAndId,
            dataset: dataset,
            algorithm: normalizedAlgorithm,
            recall: metrics[recallKey] || 0,
            indexingTime: (metrics[indexingTimeKey] || 0) / 1000, // Convert ms to seconds
            indexSize: metrics[indexSizeKey] || 0,
            meanLatency: metrics[meanLatencyKey] || 0,
            queryThroughput: metrics['hnsw-query-throughput'] || 0,
            cagraGraphDegree: config.cagraGraphDegree,
            cagraIntermediateGraphDegree: config.cagraIntermediateGraphDegree,
            hnswMaxConn: config.hnswMaxConn,
            hnswBeamWidth: config.hnswBeamWidth,
            efSearch: config.efSearch || config.effectiveEfSearch,
            numDocs: config.numDocs,
            topK: config.topK
        };

        console.log(`Extracted run for ${algoType} -> ${normalizedAlgorithm}:`, {
            recallKey, indexingTimeKey, indexSizeKey, meanLatencyKey,
            recall: extractedRun.recall,
            indexingTime: extractedRun.indexingTime,
            meanLatency: extractedRun.meanLatency,
            metrics: Object.keys(metrics)
        });

        return extractedRun;
    }

    calculateTotalIndexingTime(sweep) {
        let totalSeconds = 0;
        sweep.runs.forEach(run => {
            totalSeconds += parseFloat(run.indexingTime || 0);
        });
        return totalSeconds;
    }

    formatDuration(seconds) {
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);

        if (hours > 0) {
            return `${hours}h ${minutes}m`;
        } else if (minutes > 0) {
            return `${minutes}m`;
        } else {
            return `${Math.round(seconds)}s`;
        }
    }

    renderSweepList() {
        const container = document.getElementById('sweep-list');
        container.innerHTML = '';

        this.sweeps.forEach(sweep => {
            const item = document.createElement('div');
            item.className = 'sweep-item';
            item.onclick = () => this.showSweepAnalysis(sweep);

            const totalIndexingTime = this.calculateTotalIndexingTime(sweep);
            const formattedDuration = this.formatDuration(totalIndexingTime);

            item.innerHTML = `
                <div class="sweep-title">${sweep.id}</div>
                <div class="sweep-info">
                    ${sweep.totalRuns} runs • ${sweep.algorithms.join(', ')}<br>
                    <strong>Datasets:</strong> ${sweep.datasets.join(', ')}<br>
                    <strong>Total Indexing Time:</strong> ${formattedDuration}<br>
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
                const algorithm = this.normalizeAlgorithmName(run.algorithm);
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

    normalizeAlgorithmName(algorithm) {
        return algorithm.replace('_HNSW', '').replace('cagra_hnsw', 'CAGRA').replace('hnsw', 'LUCENE');
    }

    createMeaningfulTitle(run) {
        const algorithm = this.normalizeAlgorithmName(run.algorithm);
        const dataset = run.dataset || 'unknown';

        if (algorithm === 'CAGRA') {
            const graphDegree = run.cagraGraphDegree || 'N/A';
            const intermediateDegree = run.cagraIntermediateGraphDegree || 'N/A';
            const efSearch = run.efSearch || 'N/A';
            return `CAGRA ${dataset} (degree: ${graphDegree}, intermediateDegree: ${intermediateDegree}, efSearch: ${efSearch})`;
        } else if (algorithm === 'LUCENE') {
            const maxConn = run.hnswMaxConn || 'N/A';
            const beamWidth = run.hnswBeamWidth || 'N/A';
            const efSearch = run.efSearch || 'N/A';
            return `Lucene HNSW ${dataset} (maxConn: ${maxConn}, beamWidth: ${beamWidth}, efSearch: ${efSearch})`;
        } else {
            return `${algorithm} ${dataset} (efSearch: ${run.efSearch || 'N/A'})`;
        }
    }

    createOverviewChart(canvas, combo) {
        const ctx = canvas.getContext('2d');

        const sweepLabels = [...new Set(combo.data.map(d => d.sweep))];
        console.log(`Creating chart for ${combo.runId}:`, combo.data, 'sweepLabels:', sweepLabels);

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

    async showSweepAnalysis(sweep) {
        this.currentSweep = sweep;

        document.querySelectorAll('.sweep-item').forEach(item => {
            item.classList.remove('active');
        });
        event.target.closest('.sweep-item').classList.add('active');

        document.getElementById('home-view').style.display = 'none';
        document.getElementById('sweep-analysis').style.display = 'block';

        this.sortState.column = 'algorithm';
        this.sortState.direction = 'asc';
        this.sortState.secondary = {
            column: 'recall',
            direction: 'desc'
        };

        this.populateSweepAnalysisFilters(sweep);

        const firstDataset = [...new Set(sweep.runs.map(run => run.dataset))][0];
        const filteredRuns = firstDataset ?
            sweep.runs.filter(run => run.dataset === firstDataset) :
            sweep.runs;

        this.renderSpeedupAnalysis({...sweep, runs: filteredRuns});

        await this.renderRunsTable({...sweep, runs: filteredRuns});
    }

    populateSweepAnalysisFilters(sweep) {
        const datasetFilter = document.getElementById('sweep-dataset-filter');
        const algorithmFilter = document.getElementById('sweep-algorithm-filter');

        datasetFilter.innerHTML = '';

        const uniqueDatasets = [...new Set(sweep.runs.map(run => run.dataset))];
        uniqueDatasets.forEach((dataset, index) => {
            const option = document.createElement('option');
            option.value = dataset;
            option.textContent = dataset;
            datasetFilter.appendChild(option);
        });

        if (uniqueDatasets.length > 0) {
            datasetFilter.value = uniqueDatasets[0];
        }

        datasetFilter.removeEventListener('change', this.sweepFilterHandler);
        algorithmFilter.removeEventListener('change', this.sweepFilterHandler);

        this.sweepFilterHandler = () => this.applySweepAnalysisFilters();
        datasetFilter.addEventListener('change', this.sweepFilterHandler);
        algorithmFilter.addEventListener('change', this.sweepFilterHandler);

        const paretoFilter = document.getElementById('sweep-pareto-filter');
        if (paretoFilter) {
            paretoFilter.removeEventListener('change', this.sweepFilterHandler);
            paretoFilter.addEventListener('change', this.sweepFilterHandler);
        }
    }

    async applySweepAnalysisFilters() {
        const selectedDataset = document.getElementById('sweep-dataset-filter').value;
        const selectedAlgorithm = document.getElementById('sweep-algorithm-filter').value;
        const selectedPareto = document.getElementById('sweep-pareto-filter').value;

        let filteredRuns = this.currentSweep.runs.filter(run => {
            const datasetMatch = run.dataset === selectedDataset;
            const algorithmMatch = selectedAlgorithm === 'all' || run.algorithm === selectedAlgorithm;
            return datasetMatch && algorithmMatch;
        });

        if (selectedPareto === 'pareto') {
            const algorithms = [...new Set(filteredRuns.map(run => run.algorithm))];
            const recallLevels = [85, 90, 95, 99];
            const { optimalRunIds } = await this.calculateParetoData(filteredRuns, recallLevels, algorithms);

            filteredRuns = filteredRuns.filter(run => optimalRunIds.has(run.run_id));
        }

        this.renderSpeedupAnalysis({...this.currentSweep, runs: filteredRuns});
        await this.renderRunsTable({...this.currentSweep, runs: filteredRuns});
    }

    renderSpeedupAnalysis(sweep) {
        const chartContainer = document.querySelector('.speedup-chart');
        const messageContainer = document.getElementById('pareto-message');

        if (this.charts.speedup) {
            this.charts.speedup.destroy();
        }

        if (!this.shouldShowParetoAnalysis(sweep)) {
            chartContainer.style.display = 'none';
            messageContainer.style.display = 'block';
            return;
        }

        chartContainer.style.display = 'block';
        messageContainer.style.display = 'none';

        this.renderNewParetoPlots(sweep);
    }

    renderNewParetoPlots(sweep) {
        const chartContainer = document.querySelector('.speedup-chart');

        const datasetFilter = document.getElementById('sweep-dataset-filter');
        const selectedDataset = datasetFilter ? datasetFilter.value : null;
        if (!selectedDataset) {
            chartContainer.innerHTML = '<div style="text-align: center; padding: 40px; color: #666;">No dataset selected</div>';
            return;
        }

        let datasetDir = selectedDataset.toLowerCase().replace(/\s+/g, '-');


        this.loadParetoMetadata(sweep.id, datasetDir, selectedDataset, chartContainer);
    }

    async loadParetoMetadata(sweepId, datasetDir, selectedDataset, chartContainer) {
        try {
            let metadataPath = 'results/pareto_data/' + sweepId + '/' + datasetDir + '/metadata.json';
            let response = await fetch(metadataPath);

            if (!response.ok) {
                try {
                    const datasetsResponse = await fetch('datasets.json');
                    if (datasetsResponse.ok) {
                        const datasets = await datasetsResponse.json();
                        const canonicalName = Object.keys(datasets.datasets).find(name =>
                            name === datasetDir ||
                            name.replace(/-/g, '') === datasetDir.replace(/-/g, '') ||
                            datasetDir.replace(/-/g, '') === name.replace(/-/g, '')
                        );
                        if (canonicalName) {
                            metadataPath = 'results/pareto_data/' + sweepId + '/' + canonicalName + '/metadata.json';
                            response = await fetch(metadataPath);
                        }
                    }
                } catch (e) {
                }
            }

            if (!response.ok) {
                throw new Error('Metadata not found');
            }

            const metadata = await response.json();
            this.renderParetoPlotsWithMetadata(sweepId, datasetDir, selectedDataset, metadata, chartContainer);

        } catch (error) {
            console.warn('Could not load metadata, using fallback:', error);
            this.renderParetoPlotsWithMetadata(sweepId, datasetDir, selectedDataset, {
                k: 100,
                n_queries: 500
            }, chartContainer);
        }
    }

    renderParetoPlotsWithMetadata(sweepId, datasetDir, selectedDataset, metadata, chartContainer) {
        const plotsPath = 'results/' + sweepId + '/' + datasetDir + '/plots';
        const k = metadata.k || 100;
        const n_queries = metadata.n_queries || 500;


        let plotsHtml = '<div style="margin-bottom: 20px;">';
        plotsHtml += '<h3 style="color: #2c3e50; margin-bottom: 15px;">Pareto Analysis Plots - ' + selectedDataset + '</h3>';
        plotsHtml += '<p style="color: #666; font-size: 0.9em; margin-bottom: 15px;">Parameters: k=' + k + ', n_queries=' + n_queries + '</p>';
        plotsHtml += '<div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 20px;">';

        const plotTypes = [
            { name: 'Latency vs Recall', filename: 'latency-' + datasetDir + '-k' + k + '-n_queries' + n_queries + '.png', description: 'Search latency performance' },
            { name: 'Throughput vs Recall', filename: 'throughput-' + datasetDir + '-k' + k + '-n_queries' + n_queries + '.png', description: 'Queries per second performance' },
            { name: 'Build Time Analysis', filename: 'build-' + datasetDir + '-k' + k + '-n_queries' + n_queries + '.png', description: 'Index build time with speedup annotations' }
        ];

        for (var i = 0; i < plotTypes.length; i++) {
            var plotType = plotTypes[i];
            plotsHtml += '<div style="text-align: center; padding: 15px; border: 1px solid #ddd; border-radius: 8px; background: #fafafa;">';
            plotsHtml += '<h4 style="margin: 0 0 10px 0; color: #555;">' + plotType.name + '</h4>';
            plotsHtml += '<p style="margin: 0 0 15px 0; color: #666; font-size: 0.9em;">' + plotType.description + '</p>';
            plotsHtml += '<div style="margin-bottom: 10px;">';

            plotsHtml += '<img src="' + plotsPath + '/' + plotType.filename + '?v=' + Date.now() + '" ';
            plotsHtml += 'alt="' + plotType.name + '" ';
            plotsHtml += 'style="max-width: 100%; height: auto; border: 1px solid #ccc; border-radius: 4px;" ';
            plotsHtml += 'onerror="this.style.display=\'none\'; this.nextElementSibling.style.display=\'block\';">';
            plotsHtml += '<div style="display: none; color: #999; font-style: italic;">Plot not available</div>';
            plotsHtml += '</div>';
            plotsHtml += '<a href="' + plotsPath + '/' + plotType.filename + '" target="_blank" ';
            plotsHtml += 'style="color: #3498db; text-decoration: none; font-weight: bold;">';
            plotsHtml += 'View Full Plot</a>';
            plotsHtml += '</div>';
        }

        plotsHtml += '</div></div>';

        chartContainer.innerHTML = plotsHtml;
    }

    async calculateParetoData(runs, recallLevels, algorithms) {
        const paretoData = {};
        const optimalRunIds = new Set();

        const fileOptimalRunIds = await this.loadParetoOptimalRunsFromFiles();
        console.log(`Loaded ${fileOptimalRunIds.size} Pareto optimal runs from is_pareto files`);
        fileOptimalRunIds.forEach(runId => optimalRunIds.add(runId));

        recallLevels.forEach(level => {
            paretoData[level] = {};

            algorithms.forEach(algo => {
                const eligibleRuns = runs.filter(run =>
                    run.algorithm === algo &&
                    parseFloat(run.recall) >= (level - this.RECALL_TOLERANCE)
                );

                if (eligibleRuns.length > 0) {
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

        return { paretoData, optimalRunIds };
    }

    async loadParetoOptimalRunsFromFiles() {
        const optimalRunIds = new Set();

        const datasetFilter = document.getElementById('sweep-dataset-filter');
        const selectedDataset = datasetFilter ? datasetFilter.value : null;

        if (!selectedDataset || !this.currentSweep) {
            console.warn('No dataset or sweep selected for loading Pareto optimal runs');
            return optimalRunIds;
        }

        for (const run of this.currentSweep.runs) {
            try {
                const isParetoPath = `../results/${this.currentSweep.id}/${selectedDataset}/${run.run_id}/is_pareto`;
                const response = await fetch(isParetoPath);

                if (response.ok) {
                    optimalRunIds.add(run.run_id);
                    console.log(`Found Pareto optimal run: ${run.run_id}`);
                }
            } catch (error) {
                // Not all runs have is_pareto files
            }
        }

        console.log(`Found ${optimalRunIds.size} Pareto optimal runs using is_pareto files`);
        return optimalRunIds;
    }

    shouldShowParetoAnalysis(sweep) {
        const datasetFilter = document.getElementById('sweep-dataset-filter');
        const algorithmFilter = document.getElementById('sweep-algorithm-filter');
        const selectedDataset = datasetFilter ? datasetFilter.value : null;
        const selectedAlgorithm = algorithmFilter ? algorithmFilter.value : 'all';

        if (selectedAlgorithm !== 'all') {
            console.log('Hiding pareto analysis: algorithm filter is not "all"');
            return false;
        }

        console.log('Showing pareto analysis: specific dataset selected and all algorithms');
        return true;
    }

    async renderRunsTable(sweep) {
        this.setupTableSorting();

        const algorithms = [...new Set(sweep.runs.map(run => run.algorithm))];
        const recallLevels = [85, 90, 95, 99];
        const { optimalRunIds } = await this.calculateParetoData(sweep.runs, recallLevels, algorithms);

        let sortedRuns = [...sweep.runs];
        if (this.sortState.column) {
            sortedRuns = this.sortRuns(sortedRuns, this.sortState.column, this.sortState.direction);
        }

        const tbody = document.querySelector('#runs-table tbody');
        tbody.innerHTML = '';

        sortedRuns.forEach(run => {
            const row = document.createElement('tr');

            const recall = parseFloat(run.recall || 0);
            if (recall >= (99 - this.RECALL_TOLERANCE)) {
                row.style.backgroundColor = '#c3e6cb'; // Darker green for >= 99% (with tolerance)
            } else if (recall >= (95 - this.RECALL_TOLERANCE)) {
                row.style.backgroundColor = '#d4edda'; // Light green for >= 95% (with tolerance)
            } else if (recall >= (90 - this.RECALL_TOLERANCE)) {
                row.style.backgroundColor = '#fff3cd'; // Light yellow for 90-95% (with tolerance)
            } else if (recall >= (85 - this.RECALL_TOLERANCE)) {
                row.style.backgroundColor = '#e9ecef'; // Light grey for 85-90% (with tolerance)
            } else {
                row.style.backgroundColor = '#f8d7da'; // Light red for < 85% (with tolerance)
            }

            row.innerHTML = `
                <td>${run.run_id || 'N/A'}</td>
                <td>${run.dataset || 'N/A'}</td>
                <td>${this.normalizeAlgorithmName(run.algorithm)}</td>
                <td>${recall.toFixed(2)}</td>
                <td>${parseFloat(run.indexingTime || 0).toFixed(2)}</td>
                <td>${this.formatParameters(run)}</td>
                <td>${parseFloat(run.meanLatency || 0).toFixed(2)}</td>
                <td>${optimalRunIds.has(run.run_id) ? '<span class="pareto-badge">★</span>' : '<span class="non-pareto">-</span>'}</td>
                <td>
                    <button class="btn btn-secondary" onclick="dashboard.downloadLogs('${run.run_id}')">Logs</button>
                    <button class="btn btn-success" onclick="dashboard.showResults('${run.run_id}')">Results</button>
                </td>
            `;

            tbody.appendChild(row);
        });
    }

    setupTableSorting() {
        const headers = document.querySelectorAll('#runs-table th');
        const sortableColumns = ['runId', 'dataset', 'algorithm', 'recall', 'indexTime', 'parameters', 'meanLatency'];

        headers.forEach((header, index) => {
            if (index < sortableColumns.length) { // Skip the Actions column
                const columnKey = sortableColumns[index];
                header.classList.add('sortable');
                header.dataset.column = columnKey;

                header.replaceWith(header.cloneNode(true));
                const newHeader = document.querySelectorAll('#runs-table th')[index];

                newHeader.addEventListener('click', () => {
                    this.handleColumnSort(columnKey);
                });

                this.updateSortIndicator(newHeader, columnKey);
            }
        });
    }

    async handleColumnSort(columnKey) {
        if (this.sortState.column === columnKey) {
            this.sortState.direction = this.sortState.direction === 'asc' ? 'desc' : 'asc';
        } else {
            this.sortState.column = columnKey;
            this.sortState.direction = 'asc';
        }

        if (this.currentSweep) {
            const datasetFilter = document.getElementById('sweep-dataset-filter');
            const algorithmFilter = document.getElementById('sweep-algorithm-filter');
            const paretoFilter = document.getElementById('sweep-pareto-filter');
            const selectedDataset = datasetFilter ? datasetFilter.value : null;
            const selectedAlgorithm = algorithmFilter ? algorithmFilter.value : 'all';
            const selectedPareto = paretoFilter ? paretoFilter.value : 'all';

            let filteredRuns = this.currentSweep.runs.filter(run => {
                const datasetMatch = run.dataset === selectedDataset;
                const algorithmMatch = selectedAlgorithm === 'all' || run.algorithm === selectedAlgorithm;
                return datasetMatch && algorithmMatch;
            });

            if (selectedPareto === 'pareto') {
                const algorithms = [...new Set(filteredRuns.map(run => run.algorithm))];
                const recallLevels = [85, 90, 95, 99];
                const { optimalRunIds } = await this.calculateParetoData(filteredRuns, recallLevels, algorithms);
                filteredRuns = filteredRuns.filter(run => optimalRunIds.has(run.run_id));
            }

            await this.renderRunsTable({...this.currentSweep, runs: filteredRuns});
        }
    }

    updateSortIndicator(header, columnKey) {
        header.classList.remove('sorted-asc', 'sorted-desc');

        if (this.sortState.column === columnKey) {
            header.classList.add(this.sortState.direction === 'asc' ? 'sorted-asc' : 'sorted-desc');
        }
    }

    sortRuns(runs, columnKey, direction) {
        return runs.sort((a, b) => {
            let valueA, valueB;

            switch (columnKey) {
                case 'runId':
                    valueA = a.run_id || '';
                    valueB = b.run_id || '';
                    break;
                case 'dataset':
                    valueA = a.dataset || '';
                    valueB = b.dataset || '';
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

            let comparison = 0;
            if (typeof valueA === 'string' && typeof valueB === 'string') {
                comparison = valueA.localeCompare(valueB);
            } else {
                comparison = valueA - valueB;
            }

            comparison = direction === 'asc' ? comparison : -comparison;

            if (comparison === 0 && this.sortState.secondary) {
                const secCol = this.sortState.secondary.column;
                const secDir = this.sortState.secondary.direction;

                let secValueA, secValueB;
                switch (secCol) {
                    case 'recall':
                        secValueA = parseFloat(a.recall || 0);
                        secValueB = parseFloat(b.recall || 0);
                        break;
                    case 'algorithm':
                        secValueA = a.algorithm || '';
                        secValueB = b.algorithm || '';
                        break;
                    case 'indexTime':
                        secValueA = parseFloat(a.indexingTime || 0);
                        secValueB = parseFloat(b.indexingTime || 0);
                        break;
                    default:
                        return 0;
                }

                let secComparison = 0;
                if (typeof secValueA === 'string' && typeof secValueB === 'string') {
                    secComparison = secValueA.localeCompare(secValueB);
                } else {
                    secComparison = secValueA - secValueB;
                }

                return secDir === 'asc' ? secComparison : -secComparison;
            }

            return comparison;
        });
    }

    formatRunId(runId) {
        // Return the full run ID
        if (!runId) return 'N/A';
        return runId;
    }

    formatParameters(run) {
        const algorithm = run.algorithm;
        const efSearch = run.efSearch || 'N/A';

        if (algorithm === 'CAGRA_HNSW') {
            const graphDegree = run.cagraGraphDegree || 'N/A';
            const intermediateDegree = run.cagraIntermediateGraphDegree || 'N/A';
            return `degree: ${graphDegree}, intermediateDegree: ${intermediateDegree}, efSearch: ${efSearch}`;
        } else if (algorithm === 'LUCENE_HNSW') {
            const maxConn = run.hnswMaxConn || 'N/A';
            const beamWidth = run.hnswBeamWidth || 'N/A';
            return `maxConn: ${maxConn}, beamWidth: ${beamWidth}, efSearch: ${efSearch}`;
        } else {
            return `efSearch: ${efSearch}`;
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
