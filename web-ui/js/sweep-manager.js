/**
 * Manages sweep data and UI interactions
 */
class SweepManager {
    constructor(dataLoader) {
        this.dataLoader = dataLoader;
        this.currentSweep = null;
        this.sweeps = [];
        this.init();
    }

    init() {
        this.setupEventListeners();
        this.loadSweeps();
    }

    setupEventListeners() {
        // Only set up event listeners if the elements exist
        const refreshBtn = document.getElementById('refresh-data');
        if (refreshBtn) {
            refreshBtn.addEventListener('click', () => {
                this.loadSweeps();
            });
        }

        const exportBtn = document.getElementById('export-sweep');
        if (exportBtn) {
            exportBtn.addEventListener('click', () => {
                this.exportSweep();
            });
        }

        const downloadBtn = document.getElementById('download-logs');
        if (downloadBtn) {
            downloadBtn.addEventListener('click', () => {
                this.downloadLogs();
            });
        }

        const viewConfigBtn = document.getElementById('view-config');
        if (viewConfigBtn) {
            viewConfigBtn.addEventListener('click', () => {
                this.viewConfig();
            });
        }
    }

    async loadSweeps() {
        try {
            this.showLoading();
            const data = await this.dataLoader.loadData();
            this.sweeps = this.groupRunsBySweep(data);
            this.renderSweepList();
            this.updateStats();
        } catch (error) {
            console.error('Failed to load sweeps:', error);
            this.showError('Failed to load sweep data');
        } finally {
            this.hideLoading();
        }
    }

    groupRunsBySweep(runs) {
        const sweepMap = new Map();
        
        runs.forEach(run => {
            // Use unique_sweep_id if available, otherwise fall back to date grouping
            const sweepId = run.sweepId || new Date(run.createdAt).toDateString();
            
            if (!sweepMap.has(sweepId)) {
                sweepMap.set(sweepId, {
                    id: sweepId,
                    dataset: run.dataset,
                    algorithm: run.algo,
                    createdAt: run.createdAt,
                    runs: [],
                    fileName: this.generateSweepFileName(sweepId, run.createdAt),
                    commitId: run.commitId
                });
            }
            sweepMap.get(sweepId).runs.push(run);
        });

        return Array.from(sweepMap.values()).sort((a, b) => 
            new Date(b.createdAt) - new Date(a.createdAt)
        );
    }

    generateSweepId(run) {
        const date = run.createdAt.substring(0, 10);
        return `${run.dataset}_${date}`;
    }

    generateSweepFileName(sweepId, createdAt) {
        const dateStr = createdAt.substring(0, 10);
        const [year, month, day] = dateStr.split('-');
        const formattedDate = `${day}-${month}-${year}`;
        const hash = this.generateHash(sweepId);
        return `${formattedDate}-${hash}.csv`;
    }

    generateHash(str) {
        let hash = 0;
        for (let i = 0; i < str.length; i++) {
            const char = str.charCodeAt(i);
            hash = ((hash << 5) - hash) + char;
            hash = hash & hash; // Convert to 32bit integer
        }
        return Math.abs(hash).toString(16).substring(0, 8);
    }

    renderSweepList() {
        const container = document.getElementById('sweep-list');
        if (!container) {
            return; // Skip if container doesn't exist
        }
        
        container.innerHTML = '';

        if (this.sweeps.length === 0) {
            container.innerHTML = '<div class="empty-state">No sweeps found</div>';
            return;
        }

        this.sweeps.forEach(sweep => {
            const sweepElement = this.createSweepElement(sweep);
            container.appendChild(sweepElement);
        });
    }

    createSweepElement(sweep) {
        const element = document.createElement('div');
        element.className = 'sweep-item';
        element.dataset.sweepId = sweep.id;

        const cagraRuns = sweep.runs.filter(r => r.algo === 'CAGRA_HNSW').length;
        const luceneRuns = sweep.runs.filter(r => r.algo === 'LUCENE_HNSW').length;
        
        // Extract meaningful title from sweep ID
        const sweepTitle = this.formatSweepTitle(sweep.id);

        element.innerHTML = `
            <div class="sweep-item-header">
                <div class="sweep-item-title">${sweepTitle}</div>
                <div class="sweep-item-date">${this.formatDate(sweep.createdAt)}</div>
            </div>
            <div class="sweep-item-stats">
                ${sweep.runs.length} runs (${cagraRuns} CAGRA, ${luceneRuns} Lucene)
            </div>
            <div class="sweep-item-actions">
                <button class="btn btn-small btn-primary" onclick="sweepManager.selectSweep('${sweep.id}')">
                    View Details
                </button>
                <button class="btn btn-small btn-secondary" onclick="sweepManager.downloadSweepData('${sweep.id}')">
                    Download
                </button>
            </div>
        `;

        element.addEventListener('click', (e) => {
            if (!e.target.closest('button')) {
                this.selectSweep(sweep.id);
            }
        });

        return element;
    }
    
    formatSweepTitle(sweepId) {
        // If it's a unique_sweep_id, extract meaningful parts
        if (sweepId.includes('_')) {
            const parts = sweepId.split('_');
            if (parts.length >= 3) {
                const dataset = parts[0];
                const date = parts[1];
                const time = parts[2];
                // Format as "SIFT-1M 15/09/2025 10:52"
                const formattedDate = date.replace(/(\d{4})(\d{2})(\d{2})/, '$3/$2/$1');
                const formattedTime = time.replace(/(\d{2})(\d{2})/, '$1:$2');
                return `${dataset.toUpperCase()} ${formattedDate} ${formattedTime}`;
            }
        }
        
        // Fallback to original format
        return sweepId.length > 30 ? sweepId.substring(0, 30) + '...' : sweepId;
    }

    selectSweep(sweepId) {
        // Update UI state
        document.querySelectorAll('.sweep-item').forEach(item => {
            item.classList.remove('active');
        });
        document.querySelector(`[data-sweep-id="${sweepId}"]`).classList.add('active');

        // Set current sweep
        this.currentSweep = this.sweeps.find(s => s.id === sweepId);
        
        // Update page title
        document.getElementById('page-title').textContent = 
            `Sweep: ${this.currentSweep.dataset} - ${this.formatDate(this.currentSweep.createdAt)}`;

        // Update panels
        this.updateSweepInfo();
        this.updateResultsTable();
        
        // Notify other managers
        if (window.graphManager) {
            window.graphManager.updateGraphs(this.currentSweep);
        }
        if (window.metricsManager) {
            window.metricsManager.updateMetrics(this.currentSweep);
        }
        if (window.paretoManager) {
            window.paretoManager.generateParetoAnalysis(sweepId, this.currentSweep.runs);
        }
    }

    updateSweepInfo() {
        const container = document.getElementById('sweep-info');
        if (!this.currentSweep) {
            container.innerHTML = '<p>Select a sweep from the sidebar to view details</p>';
            return;
        }

        // Clear existing content (including Pareto charts)
        container.innerHTML = '';

        const sweep = this.currentSweep;
        const avgIndexingTime = this.calculateAverage(sweep.runs, 'indexingTime');
        const avgQueryTime = this.calculateAverage(sweep.runs, 'queryTime');
        const avgRecall = this.calculateAverage(sweep.runs, 'recall');
        const avgQPS = this.calculateAverage(sweep.runs, 'qps');

        container.innerHTML = `
            <div class="info-card">
                <h4>Dataset</h4>
                <p>${sweep.dataset}</p>
            </div>
            <div class="info-card">
                <h4>Total Runs</h4>
                <p>${sweep.runs.length}</p>
            </div>
            <div class="info-card">
                <h4>Avg Indexing Time</h4>
                <p>${avgIndexingTime.toFixed(2)}s</p>
            </div>
            <div class="info-card">
                <h4>Avg Query Time</h4>
                <p>${avgQueryTime.toFixed(2)}s</p>
            </div>
            <div class="info-card">
                <h4>Avg Recall</h4>
                <p>${(avgRecall * 100).toFixed(1)}%</p>
            </div>
            <div class="info-card">
                <h4>Avg QPS</h4>
                <p>${avgQPS.toFixed(1)}</p>
            </div>
            <div class="info-card">
                <h4>Commit ID</h4>
                <p><a href="#" onclick="sweepManager.viewCommit('${sweep.commitId}')">${sweep.commitId}</a></p>
            </div>
            <div class="info-card">
                <h4>File Name</h4>
                <p>${sweep.fileName}</p>
            </div>
        `;
    }

    updateResultsTable() {
        const tbody = document.getElementById('results-table-body');
        if (!tbody) {
            console.error('Table body element not found');
            return;
        }
        
        if (!this.currentSweep) {
            tbody.innerHTML = '<tr><td colspan="10" class="empty-state">Select a sweep to view detailed results</td></tr>';
            return;
        }

        console.log(`Updating table with ${this.currentSweep.runs.length} runs`);
        
        // Always update table directly
        tbody.innerHTML = '';
        this.currentSweep.runs.forEach(run => {
            const row = this.createTableRow(run);
            tbody.appendChild(row);
        });
    }

    createTableRow(run) {
        const row = document.createElement('tr');
        
        // Handle -1 values gracefully
        const indexTime = run.indexingTime > 0 ? (run.indexingTime / 1000).toFixed(2) : 'Failed';
        const queryTime = run.queryTime > 0 ? (run.queryTime / 1000).toFixed(2) : 'Failed';
        const recall = run.recall > 0 ? (run.recall * 100).toFixed(1) : 'Failed';
        const qps = run.qps > 0 ? run.qps.toFixed(1) : 'Failed';
        const meanLatency = run.meanLatency > 0 ? run.meanLatency.toFixed(1) : 'Failed';
        const indexSize = run.indexSize > 0 ? (run.indexSize / 1024 / 1024).toFixed(1) : 'Failed';
        
        row.innerHTML = `
            <td>${run.runId.substring(0, 8)}</td>
            <td><span class="algorithm-badge algorithm-${run.algo.toLowerCase()}">${run.algo.replace('_HNSW', '')}</span></td>
            <td>${indexTime}</td>
            <td>${queryTime}</td>
            <td>${recall}</td>
            <td>${qps}</td>
            <td>${meanLatency}</td>
            <td>${indexSize}</td>
            <td><code>${run.commitId ? run.commitId.substring(0, 8) : 'N/A'}</code></td>
            <td>
                <button class="btn btn-small btn-primary" onclick="sweepManager.viewRunConfig('${run.runId}')">
                    Config
                </button>
                <button class="btn btn-small btn-secondary" onclick="sweepManager.viewRunMetrics('${run.runId}')">
                    Metrics
                </button>
            </td>
        `;
        return row;
    }

    calculateAverage(runs, field) {
        const validRuns = runs.filter(run => run[field] > 0);
        if (validRuns.length === 0) return 0;
        return validRuns.reduce((sum, run) => sum + run[field], 0) / validRuns.length;
    }

    formatDate(dateString) {
        const date = new Date(dateString);
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
    }

    updateStats() {
        const totalSweepsElement = document.getElementById('total-sweeps');
        if (totalSweepsElement) {
            totalSweepsElement.textContent = `${this.sweeps.length} sweeps`;
        }
    }

    exportSweep() {
        if (!this.currentSweep) {
            alert('Please select a sweep first');
            return;
        }
        
        // Create CSV content
        const csv = this.generateCSV(this.currentSweep.runs);
        this.downloadCSV(csv, this.currentSweep.fileName);
    }

    downloadSweepData(sweepId) {
        const sweep = this.sweeps.find(s => s.id === sweepId);
        if (!sweep) return;
        
        const csv = this.generateCSV(sweep.runs);
        this.downloadCSV(csv, sweep.fileName);
    }

    generateCSV(runs) {
        if (runs.length === 0) return '';
        
        const headers = Object.keys(runs[0]);
        const csvRows = [headers.join(',')];
        
        runs.forEach(run => {
            const values = headers.map(header => {
                const value = run[header];
                return typeof value === 'string' && value.includes(',') ? `"${value}"` : value;
            });
            csvRows.push(values.join(','));
        });
        
        return csvRows.join('\n');
    }

    downloadCSV(csv, filename) {
        const blob = new Blob([csv], { type: 'text/csv' });
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
    }

    downloadLogs() {
        if (!this.currentSweep) {
            alert('Please select a sweep first');
            return;
        }
        
        // This would typically fetch logs from the server
        alert('Log download functionality will be implemented with server integration');
    }

    viewConfig() {
        if (!this.currentSweep) {
            alert('Please select a sweep first');
            return;
        }
        
        // This would typically fetch config from the server
        alert('Config view functionality will be implemented with server integration');
    }

    viewCommit(commitId) {
        // This would typically open the commit in the repository
        alert(`Viewing commit: ${commitId}`);
    }

    viewRunConfig(runId) {
        // This would typically fetch and display the run config
        alert(`Viewing config for run: ${runId}`);
    }

    viewRunMetrics(runId) {
        // This would typically fetch and display the run metrics
        alert(`Viewing metrics for run: ${runId}`);
    }

    showLoading() {
        const overlay = document.getElementById('loading-overlay');
        if (overlay) {
            overlay.classList.add('show');
        }
    }

    hideLoading() {
        const overlay = document.getElementById('loading-overlay');
        if (overlay) {
            overlay.classList.remove('show');
        }
    }

    showError(message) {
        const container = document.getElementById('sweep-list');
        if (container) {
            container.innerHTML = `<div class="empty-state">${message}</div>`;
        }
    }
}

// Make it globally available
window.SweepManager = SweepManager;


