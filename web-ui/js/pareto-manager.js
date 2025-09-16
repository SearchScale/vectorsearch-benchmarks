/**
 * Manages Pareto analysis and speedup comparison charts
 */
class ParetoManager {
    constructor(dataLoader) {
        this.dataLoader = dataLoader;
        this.charts = new Map();
    }

    /**
     * Generate Pareto analysis for a sweep and create speedup comparison chart
     */
    async generateParetoAnalysis(sweepId, runs) {
        // Filter runs for sift-1m dataset only
        const siftRuns = runs.filter(run => run.dataset === 'sift-1m');
        
        if (siftRuns.length === 0) {
            console.warn('No sift-1m runs found for Pareto analysis');
            return null;
        }

        try {
            // Try to load Java-generated Pareto analysis first
            const javaSpeedupData = await this.loadJavaParetoAnalysis();
            if (javaSpeedupData) {
                this.createSpeedupChart(sweepId, javaSpeedupData);
                return javaSpeedupData;
            }
        } catch (error) {
            console.warn('Could not load Java Pareto analysis, falling back to JavaScript analysis:', error);
        }

        // Fallback to JavaScript analysis
        const runsByAlgo = this.groupRunsByAlgorithm(siftRuns);
        const recallThresholds = [0.90, 0.95];
        const optimalConfigs = this.findOptimalConfigs(runsByAlgo, recallThresholds);
        const speedupData = this.calculateSpeedupRatios(optimalConfigs);
        
        this.createSpeedupChart(sweepId, speedupData);
        return speedupData;
    }

    /**
     * Load Java-generated Pareto analysis from speedup_comparison.json
     */
    async loadJavaParetoAnalysis() {
        try {
            const response = await fetch('../reports/speedup_comparison.json');
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            const data = await response.json();
            console.log('Loaded Java Pareto analysis:', data);
            return data;
        } catch (error) {
            console.warn('Failed to load Java Pareto analysis:', error);
            return null;
        }
    }

    /**
     * Group runs by algorithm
     */
    groupRunsByAlgorithm(runs) {
        const groups = {
            'CAGRA_HNSW': [],
            'LUCENE_HNSW': []
        };
        
        runs.forEach(run => {
            if (groups[run.algorithm]) {
                groups[run.algorithm].push(run);
            }
        });
        
        return groups;
    }

    /**
     * Find optimal configurations for each algorithm and recall threshold
     */
    findOptimalConfigs(runsByAlgo, recallThresholds) {
        const optimalConfigs = {};
        
        for (const [algorithm, runs] of Object.entries(runsByAlgo)) {
            optimalConfigs[algorithm] = {};
            
            for (const threshold of recallThresholds) {
                // Find runs that meet the recall threshold
                const validRuns = runs.filter(run => run.recall >= threshold);
                
                if (validRuns.length === 0) continue;
                
                // Find Pareto optimal runs (best indexing time for given recall)
                const paretoOptimal = this.findParetoOptimal(validRuns);
                
                if (paretoOptimal.length > 0) {
                    // Select the best configuration (lowest indexing time)
                    const bestRun = paretoOptimal.reduce((best, current) => 
                        current.indexingTime < best.indexingTime ? current : best
                    );
                    
                    optimalConfigs[algorithm][threshold] = bestRun;
                }
            }
        }
        
        return optimalConfigs;
    }

    /**
     * Find Pareto optimal runs (not dominated by others)
     */
    findParetoOptimal(runs) {
        const paretoOptimal = [];
        
        for (const candidate of runs) {
            let isDominated = false;
            
            for (const other of runs) {
                if (candidate === other) continue;
                
                // Check if 'other' dominates 'candidate'
                if (other.indexingTime <= candidate.indexingTime && 
                    other.queryTime <= candidate.queryTime &&
                    (other.indexingTime < candidate.indexingTime || other.queryTime < candidate.queryTime)) {
                    isDominated = true;
                    break;
                }
            }
            
            if (!isDominated) {
                paretoOptimal.push(candidate);
            }
        }
        
        return paretoOptimal;
    }

    /**
     * Calculate speedup ratios between CAGRA and Lucene
     */
    calculateSpeedupRatios(optimalConfigs) {
        const speedupData = {
            '~90%': { cagra: null, lucene: null, speedup: null },
            '~95%': { cagra: null, lucene: null, speedup: null }
        };
        
        const thresholds = [0.90, 0.95];
        const thresholdLabels = ['~90%', '~95%'];
        
        thresholds.forEach((threshold, index) => {
            const label = thresholdLabels[index];
            const cagraConfig = optimalConfigs['CAGRA_HNSW']?.[threshold];
            const luceneConfig = optimalConfigs['LUCENE_HNSW']?.[threshold];
            
            if (cagraConfig && luceneConfig) {
                speedupData[label] = {
                    cagra: {
                        indexingTime: cagraConfig.indexingTime / 1000, // Convert to seconds
                        recall: cagraConfig.recall * 100 // Convert to percentage
                    },
                    lucene: {
                        indexingTime: luceneConfig.indexingTime / 1000, // Convert to seconds
                        recall: luceneConfig.recall * 100 // Convert to percentage
                    },
                    speedup: luceneConfig.indexingTime / cagraConfig.indexingTime
                };
            }
        });
        
        return speedupData;
    }

    /**
     * Create the speedup comparison bar chart
     */
    createSpeedupChart(sweepId, speedupData) {
        const canvas = document.getElementById('pareto-chart');
        if (!canvas) {
            console.error('Pareto chart canvas not found');
            return;
        }
        
        // Destroy existing chart if it exists
        const existingChart = this.charts.get('pareto');
        if (existingChart) {
            existingChart.destroy();
        }
        
        // Prepare chart data
        const chartData = this.prepareChartData(speedupData);
        
        // Create or update chart
        const ctx = canvas.getContext('2d');
        
        const chart = new Chart(ctx, {
            type: 'bar',
            data: chartData,
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    title: {
                        display: true,
                        text: 'CAGRA vs Lucene Indexing Performance Comparison'
                    },
                    legend: {
                        display: true,
                        position: 'top'
                    }
                },
                scales: {
                    x: {
                        title: {
                            display: true,
                            text: 'Recall Level'
                        }
                    },
                    y: {
                        title: {
                            display: true,
                            text: 'Indexing Time (s)'
                        },
                        beginAtZero: true
                    }
                },
                onClick: (event, elements) => {
                    if (elements.length > 0) {
                        const element = elements[0];
                        const datasetIndex = element.datasetIndex;
                        const dataIndex = element.index;
                        this.showRunDetails(speedupData, datasetIndex, dataIndex);
                    }
                }
            }
        });
        
        this.charts.set('pareto', chart);
        
        // Update speedup summary
        this.updateSpeedupSummary(speedupData);
    }

    /**
     * Update the speedup summary display
     */
    updateSpeedupSummary(speedupData) {
        const summaryElement = document.getElementById('speedup-summary');
        if (!summaryElement) return;

        let summaryHtml = '<h4>Speedup Summary</h4>';
        
        for (const [threshold, data] of Object.entries(speedupData)) {
            if (data.speedup) {
                summaryHtml += `
                    <div style="margin: 0.5rem 0; padding: 0.5rem; background: white; border-radius: 4px;">
                        <strong>${threshold} Recall:</strong> CAGRA is ${data.speedup.toFixed(2)}x faster than Lucene
                        <br>
                        <small>CAGRA: ${data.cagra.indexingTime.toFixed(2)}s, Lucene: ${data.lucene.indexingTime.toFixed(2)}s</small>
                    </div>
                `;
            }
        }
        
        summaryElement.innerHTML = summaryHtml;
    }

    /**
     * Prepare chart data for Chart.js
     */
    prepareChartData(speedupData) {
        const datasets = [
            {
                label: 'CAGRA_HNSW',
                data: [],
                backgroundColor: 'rgba(255, 159, 64, 0.8)', // Orange
                borderColor: 'rgba(255, 159, 64, 1)',
                borderWidth: 1
            },
            {
                label: 'LUCENE_HNSW',
                data: [],
                backgroundColor: 'rgba(54, 162, 235, 0.8)', // Blue
                borderColor: 'rgba(54, 162, 235, 1)',
                borderWidth: 1
            }
        ];
        
        const labels = [];
        
        // Process each recall level
        Object.entries(speedupData).forEach(([recallLevel, data]) => {
            if (data.cagra && data.lucene) {
                labels.push(recallLevel);
                
                // Handle both Java and JavaScript data formats
                const cagraTime = data.cagra.indexingTime || data.cagra.indexingTime;
                const luceneTime = data.lucene.indexingTime || data.lucene.indexingTime;
                
                datasets[0].data.push(cagraTime);
                datasets[1].data.push(luceneTime);
                
                // Add speedup annotation
                const speedup = data.speedup || (luceneTime / cagraTime);
                this.addSpeedupAnnotation(recallLevel, speedup);
            }
        });
        
        return {
            labels: labels,
            datasets: datasets
        };
    }

    /**
     * Add speedup annotation to the chart
     */
    addSpeedupAnnotation(recallLevel, speedup) {
        // This would be implemented with Chart.js annotations plugin
        // For now, we'll add it as a text element
        console.log(`Speedup at ${recallLevel}: ${speedup.toFixed(1)}x`);
    }

    /**
     * Show detailed run information when clicking on chart
     */
    showRunDetails(speedupData, datasetIndex, dataIndex) {
        const recallLevels = Object.keys(speedupData);
        const recallLevel = recallLevels[dataIndex];
        const algorithm = datasetIndex === 0 ? 'CAGRA_HNSW' : 'LUCENE_HNSW';
        
        const data = speedupData[recallLevel];
        const runData = datasetIndex === 0 ? data.cagra : data.lucene;
        
        if (data) {
            alert(`Run Details:\nAlgorithm: ${algorithm}\nRecall Level: ${recallLevel}\nIndexing Time: ${runData.indexingTime.toFixed(2)}s\nRecall: ${runData.recall.toFixed(1)}%`);
        }
    }

    /**
     * Generate Pareto analysis for all sweeps
     */
    generateAllParetoAnalyses(sweeps) {
        sweeps.forEach(sweep => {
            if (sweep.runs && sweep.runs.length > 0) {
                this.generateParetoAnalysis(sweep.id, sweep.runs);
            }
        });
    }

    /**
     * Clear all charts
     */
    clearCharts() {
        this.charts.forEach(chart => chart.destroy());
        this.charts.clear();
    }
}

// Make it globally available
window.ParetoManager = ParetoManager;
