/**
 * Manages run details modal and file access
 */
class RunDetailsManager {
    constructor() {
        this.currentRun = null;
        this.modal = null;
        this.init();
    }

    init() {
        this.createModal();
        this.setupEventListeners();
    }

    createModal() {
        // Create modal HTML
        const modalHTML = `
            <div id="run-details-modal" class="run-details-modal">
                <div class="modal-content">
                    <div class="modal-header">
                        <h3 id="modal-title">Run Details</h3>
                        <span class="close" id="modal-close">&times;</span>
                    </div>
                    <div class="modal-body">
                        <div class="modal-tabs">
                            <button class="tab-button active" data-tab="overview">Overview</button>
                            <button class="tab-button" data-tab="config">Configuration</button>
                            <button class="tab-button" data-tab="results">Results</button>
                            <button class="tab-button" data-tab="logs">Logs</button>
                            <button class="tab-button" data-tab="metrics">Metrics</button>
                        </div>
                        <div class="tab-content">
                            <div id="overview-tab" class="tab-panel active">
                                <div id="overview-content"></div>
                            </div>
                            <div id="config-tab" class="tab-panel">
                                <div class="file-viewer">
                                    <div class="file-actions">
                                        <button class="btn btn-primary" id="download-config">Download Config</button>
                                        <button class="btn btn-secondary" id="view-raw-config">View Raw</button>
                                    </div>
                                    <pre id="config-content"></pre>
                                </div>
                            </div>
                            <div id="results-tab" class="tab-panel">
                                <div class="file-viewer">
                                    <div class="file-actions">
                                        <button class="btn btn-primary" id="download-results">Download Results</button>
                                        <button class="btn btn-secondary" id="view-raw-results">View Raw</button>
                                    </div>
                                    <pre id="results-content"></pre>
                                </div>
                            </div>
                            <div id="logs-tab" class="tab-panel">
                                <div class="logs-viewer">
                                    <div class="log-tabs">
                                        <button class="log-tab active" data-log="stdout">Stdout</button>
                                        <button class="log-tab" data-log="stderr">Stderr</button>
                                    </div>
                                    <div class="log-actions">
                                        <button class="btn btn-primary" id="download-stdout">Download Stdout</button>
                                        <button class="btn btn-primary" id="download-stderr">Download Stderr</button>
                                    </div>
                                    <pre id="log-content"></pre>
                                </div>
                            </div>
                            <div id="metrics-tab" class="tab-panel">
                                <div class="metrics-viewer">
                                    <div class="file-actions">
                                        <button class="btn btn-primary" id="download-metrics">Download Metrics</button>
                                        <button class="btn btn-secondary" id="view-raw-metrics">View Raw</button>
                                    </div>
                                    <div id="metrics-charts"></div>
                                    <pre id="metrics-content"></pre>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;

        // Add modal to body
        document.body.insertAdjacentHTML('beforeend', modalHTML);
        this.modal = document.getElementById('run-details-modal');
    }

    setupEventListeners() {
        // Close modal
        document.getElementById('modal-close').addEventListener('click', () => {
            this.closeModal();
        });

        // Close modal when clicking outside
        this.modal.addEventListener('click', (event) => {
            if (event.target === this.modal) {
                this.closeModal();
            }
        });

        // Tab switching
        document.querySelectorAll('.tab-button').forEach(button => {
            button.addEventListener('click', (event) => {
                this.switchTab(event.target.dataset.tab);
            });
        });

        // Log tab switching
        document.querySelectorAll('.log-tab').forEach(button => {
            button.addEventListener('click', (event) => {
                this.switchLogTab(event.target.dataset.log);
            });
        });

        // File download buttons
        document.getElementById('download-config').addEventListener('click', () => {
            this.downloadFile('config');
        });

        document.getElementById('download-results').addEventListener('click', () => {
            this.downloadFile('results');
        });

        document.getElementById('download-stdout').addEventListener('click', () => {
            this.downloadFile('stdout');
        });

        document.getElementById('download-stderr').addEventListener('click', () => {
            this.downloadFile('stderr');
        });

        document.getElementById('download-metrics').addEventListener('click', () => {
            this.downloadFile('metrics');
        });

        // View raw buttons
        document.getElementById('view-raw-config').addEventListener('click', () => {
            this.viewRawFile('config');
        });

        document.getElementById('view-raw-results').addEventListener('click', () => {
            this.viewRawFile('results');
        });

        document.getElementById('view-raw-metrics').addEventListener('click', () => {
            this.viewRawFile('metrics');
        });

        // Escape key to close modal
        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape' && this.modal.style.display === 'block') {
                this.closeModal();
            }
        });
    }

    showRunDetails(runId) {
        // Find the run data by runId
        const dataLoader = window.app?.dataLoader;
        if (!dataLoader) {
            alert('Data loader not available');
            return;
        }
        
        const allData = dataLoader.getData();
        const run = allData.find(r => r.runId === runId);
        
        if (!run) {
            alert(`Run not found: ${runId}`);
            return;
        }
        
        this.currentRun = run;
        document.getElementById('modal-title').textContent = `Run Details: ${run.runId}`;
        
        // Load all tab content
        this.loadOverviewTab();
        this.loadConfigTab();
        this.loadResultsTab();
        this.loadLogsTab();
        this.loadMetricsTab();
        
        // Show modal
        this.modal.style.display = 'block';
        
        // Switch to overview tab
        this.switchTab('overview');
    }

    loadOverviewTab() {
        const content = document.getElementById('overview-content');
        content.innerHTML = `
            <div class="run-overview">
                <div class="overview-grid">
                    <div class="overview-item">
                        <label>Run ID:</label>
                        <span>${this.currentRun.runId}</span>
                    </div>
                    <div class="overview-item">
                        <label>Algorithm:</label>
                        <span class="algorithm-badge ${this.currentRun.algorithm.toLowerCase()}">${this.currentRun.algorithm}</span>
                    </div>
                    <div class="overview-item">
                        <label>Dataset:</label>
                        <span>${this.currentRun.dataset}</span>
                    </div>
                    <div class="overview-item">
                        <label>Created:</label>
                        <span>${new Date(this.currentRun.createdAt).toLocaleString()}</span>
                    </div>
                    <div class="overview-item">
                        <label>Commit ID:</label>
                        <span><a href="#" onclick="runDetailsManager.viewCommit('${this.currentRun.commitId}')">${this.currentRun.commitId}</a></span>
                    </div>
                    <div class="overview-item">
                        <label>Sweep ID:</label>
                        <span>${this.currentRun.sweepId}</span>
                    </div>
                </div>
                
                <div class="performance-metrics">
                    <h4>Performance Metrics</h4>
                    <div class="metrics-grid">
                        <div class="metric-item">
                            <label>Indexing Time:</label>
                            <span>${(this.currentRun.indexingTime / 1000).toFixed(2)}s</span>
                        </div>
                        <div class="metric-item">
                            <label>Query Time:</label>
                            <span>${(this.currentRun.queryTime / 1000).toFixed(2)}s</span>
                        </div>
                        <div class="metric-item">
                            <label>Recall:</label>
                            <span>${(this.currentRun.recall * 100).toFixed(1)}%</span>
                        </div>
                        <div class="metric-item">
                            <label>QPS:</label>
                            <span>${this.currentRun.qps.toFixed(1)}</span>
                        </div>
                        <div class="metric-item">
                            <label>Mean Latency:</label>
                            <span>${this.currentRun.meanLatency.toFixed(2)}ms</span>
                        </div>
                        <div class="metric-item">
                            <label>Index Size:</label>
                            <span>${this.formatBytes(this.currentRun.indexSize)}</span>
                        </div>
                    </div>
                </div>
                
                <div class="parameters">
                    <h4>Parameters</h4>
                    <div class="parameters-grid">
                        ${this.currentRun.parameters.map(param => `
                            <div class="parameter-item">
                                <label>${param.label}:</label>
                                <span>${param.value}</span>
                            </div>
                        `).join('')}
                    </div>
                </div>
            </div>
        `;
    }

    async loadConfigTab() {
        const content = document.getElementById('config-content');
        content.textContent = 'Loading configuration...';
        
        try {
            const configData = await this.fetchRunFile('config');
            content.textContent = JSON.stringify(configData, null, 2);
        } catch (error) {
            content.textContent = `Error loading config: ${error.message}`;
        }
    }

    async loadResultsTab() {
        const content = document.getElementById('results-content');
        content.textContent = 'Loading results...';
        
        try {
            const resultsData = await this.fetchRunFile('results');
            content.textContent = JSON.stringify(resultsData, null, 2);
        } catch (error) {
            content.textContent = `Error loading results: ${error.message}`;
        }
    }

    async loadLogsTab() {
        const content = document.getElementById('log-content');
        content.textContent = 'Loading logs...';
        
        try {
            const stdoutData = await this.fetchRunFile('stdout');
            content.textContent = stdoutData;
            this.switchLogTab('stdout');
        } catch (error) {
            content.textContent = `Error loading logs: ${error.message}`;
        }
    }

    async loadMetricsTab() {
        const content = document.getElementById('metrics-content');
        const chartsContainer = document.getElementById('metrics-charts');
        
        content.textContent = 'Loading metrics...';
        chartsContainer.innerHTML = '';
        
        try {
            const metricsData = await this.fetchRunFile('metrics');
            content.textContent = JSON.stringify(metricsData, null, 2);
            
            // Create simple metrics charts if data is available
            if (metricsData && typeof metricsData === 'object') {
                this.createMetricsCharts(metricsData, chartsContainer);
            }
        } catch (error) {
            content.textContent = `Error loading metrics: ${error.message}`;
        }
    }

    async fetchRunFile(fileType) {
        const runId = this.currentRun.runId;
        let url;
        
        switch (fileType) {
            case 'config':
                url = `../runs/${runId}/materialized-config.json`;
                break;
            case 'results':
                url = `../runs/${runId}/results.json`;
                break;
            case 'stdout':
                url = `logs/${runId}_stdout.log`;
                break;
            case 'stderr':
                url = `logs/${runId}_stderr.log`;
                break;
            case 'metrics':
                url = `../runs/${runId}/metrics.json`;
                break;
            default:
                throw new Error(`Unknown file type: ${fileType}`);
        }
        
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }
        
        if (fileType === 'stdout' || fileType === 'stderr') {
            return await response.text();
        } else {
            return await response.json();
        }
    }

    createMetricsCharts(metricsData, container) {
        // Simple metrics visualization
        if (metricsData.memory) {
            const memoryChart = document.createElement('div');
            memoryChart.innerHTML = `
                <h5>Memory Usage</h5>
                <div class="metric-bar">
                    <div class="metric-label">Heap Used:</div>
                    <div class="metric-value">${this.formatBytes(metricsData.memory.heapUsed || 0)}</div>
                </div>
                <div class="metric-bar">
                    <div class="metric-label">Heap Max:</div>
                    <div class="metric-value">${this.formatBytes(metricsData.memory.heapMax || 0)}</div>
                </div>
            `;
            container.appendChild(memoryChart);
        }
    }

    switchTab(tabName) {
        // Update tab buttons
        document.querySelectorAll('.tab-button').forEach(button => {
            button.classList.remove('active');
        });
        document.querySelector(`[data-tab="${tabName}"]`).classList.add('active');
        
        // Update tab panels
        document.querySelectorAll('.tab-panel').forEach(panel => {
            panel.classList.remove('active');
        });
        document.getElementById(`${tabName}-tab`).classList.add('active');
    }

    switchLogTab(logType) {
        // Update log tab buttons
        document.querySelectorAll('.log-tab').forEach(button => {
            button.classList.remove('active');
        });
        document.querySelector(`[data-log="${logType}"]`).classList.add('active');
        
        // Load the appropriate log
        this.loadLogContent(logType);
    }

    async loadLogContent(logType) {
        const content = document.getElementById('log-content');
        content.textContent = 'Loading log...';
        
        try {
            const logData = await this.fetchRunFile(logType);
            content.textContent = logData;
        } catch (error) {
            content.textContent = `Error loading ${logType}: ${error.message}`;
        }
    }

    downloadFile(fileType) {
        const runId = this.currentRun.runId;
        let url, filename;
        
        switch (fileType) {
            case 'config':
                url = `../runs/${runId}/materialized-config.json`;
                filename = `${runId}_config.json`;
                break;
            case 'results':
                url = `../runs/${runId}/results.json`;
                filename = `${runId}_results.json`;
                break;
            case 'stdout':
                url = `logs/${runId}_stdout.log`;
                filename = `${runId}_stdout.log`;
                break;
            case 'stderr':
                url = `logs/${runId}_stderr.log`;
                filename = `${runId}_stderr.log`;
                break;
            case 'metrics':
                url = `../runs/${runId}/metrics.json`;
                filename = `${runId}_metrics.json`;
                break;
            default:
                alert(`Unknown file type: ${fileType}`);
                return;
        }
        
        // Create download link
        const link = document.createElement('a');
        link.href = url;
        link.download = filename;
        link.style.display = 'none';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
    }

    viewRawFile(fileType) {
        const runId = this.currentRun.runId;
        let url;
        
        switch (fileType) {
            case 'config':
                url = `../runs/${runId}/materialized-config.json`;
                break;
            case 'results':
                url = `../runs/${runId}/results.json`;
                break;
            case 'metrics':
                url = `../runs/${runId}/metrics.json`;
                break;
            default:
                alert(`Cannot view raw ${fileType}`);
                return;
        }
        
        window.open(url, '_blank');
    }

    viewCommit(commitId) {
        // Open commit in new tab (placeholder for now)
        alert(`Viewing commit: ${commitId}`);
    }

    closeModal() {
        this.modal.style.display = 'none';
        this.currentRun = null;
    }

    formatBytes(bytes) {
        if (bytes === null || bytes === undefined) return 'N/A';
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        if (bytes === 0) return '0 Bytes';
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        return Math.round(bytes / Math.pow(1024, i) * 100) / 100 + ' ' + sizes[i];
    }
}

// Create global instance
window.runDetailsManager = new RunDetailsManager();
