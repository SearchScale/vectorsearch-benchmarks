/**
 * Main application controller
 */
class App {
    constructor() {
        this.dataLoader = new DataLoader();
        this.sweepManager = null;
        this.graphManager = null;
        this.tableManager = null;
        this.filterManager = null;
        this.metricsManager = null;
        
        // Wait for DOM to be ready before initializing
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => this.init());
        } else {
            this.init();
        }
    }

    async init() {
        try {
            // Show loading state
            this.showLoading();
            
            // Initialize components
            this.initializeComponents();
            
            // Load initial data
            await this.loadInitialData();
            
            // Hide loading state
            this.hideLoading();
            
        } catch (error) {
            console.error('Failed to initialize app:', error);
            this.showError('Failed to initialize the application. Please check the console for details.');
        }
    }

    initializeComponents() {
        // Initialize sweep manager
        this.sweepManager = new SweepManager(this.dataLoader);
        window.sweepManager = this.sweepManager; // Make it globally accessible
        
        // Initialize graph manager
        this.graphManager = new GraphManager();
        window.graphManager = this.graphManager;
        
        // Initialize table manager
        this.tableManager = new TableManager(this.dataLoader);
        
        // Initialize filter manager
        this.filterManager = new FilterManager(this.dataLoader, this.tableManager);
        
        // Initialize metrics manager
        this.metricsManager = new MetricsManager();
        window.metricsManager = this.metricsManager;
        
        // Initialize Pareto manager
        this.paretoManager = new ParetoManager(this.dataLoader);
        window.paretoManager = this.paretoManager;
        
        // Setup modal functionality
        this.setupModal();
    }

    async loadInitialData() {
        try {
            // Load data through the data loader
            const data = await this.dataLoader.loadData();
            
            if (!data || data.length === 0) {
                throw new Error('No data loaded from CSV files');
            }
            
            console.log(`Loaded ${data.length} benchmark runs`);
            
            // Initialize filter options
            this.filterManager.init();
            
        } catch (error) {
            console.error('Failed to load initial data:', error);
            throw error;
        }
    }

    setupModal() {
        const modal = document.getElementById('raw-data-modal');
        const closeBtn = document.querySelector('.close');
        
        // Close modal when clicking the X
        closeBtn.addEventListener('click', () => {
            modal.style.display = 'none';
        });
        
        // Close modal when clicking outside
        window.addEventListener('click', (event) => {
            if (event.target === modal) {
                modal.style.display = 'none';
            }
        });
        
        // Close modal with Escape key
        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape' && modal.style.display === 'block') {
                modal.style.display = 'none';
            }
        });
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
        // Show error in the main content area
        const contentPanels = document.querySelector('.content-panels');
        if (contentPanels) {
            contentPanels.innerHTML = `
                <div class="panel">
                    <div class="panel-header">
                        <h3>Error</h3>
                    </div>
                    <div class="panel-content">
                        <div class="empty-state">
                            <h3>Application Error</h3>
                            <p>${message}</p>
                            <button class="btn btn-primary" onclick="location.reload()">Reload Application</button>
                        </div>
                    </div>
                </div>
            `;
        } else {
            console.error('Error: ' + message);
        }
    }

    // Method to refresh all data
    async refreshData() {
        try {
            this.showLoading();
            await this.dataLoader.loadData();
            this.filterManager.init();
            console.log('Data refreshed successfully');
        } catch (error) {
            console.error('Failed to refresh data:', error);
            alert('Failed to refresh data. Please try again.');
        } finally {
            this.hideLoading();
        }
    }

    // Method to export all data
    exportAllData() {
        try {
            const data = this.dataLoader.getData();
            if (!data || data.length === 0) {
                alert('No data available to export');
                return;
            }
            
            const csv = this.generateCSV(data);
            this.downloadCSV(csv, 'all_benchmark_results.csv');
        } catch (error) {
            console.error('Failed to export data:', error);
            alert('Failed to export data. Please try again.');
        }
    }

    generateCSV(data) {
        if (data.length === 0) return '';
        
        const headers = Object.keys(data[0]);
        const csvRows = [headers.join(',')];
        
        data.forEach(row => {
            const values = headers.map(header => {
                const value = row[header];
                if (value === null || value === undefined) return '';
                const stringValue = String(value);
                return stringValue.includes(',') || stringValue.includes('"') || stringValue.includes('\n') 
                    ? `"${stringValue.replace(/"/g, '""')}"` 
                    : stringValue;
            });
            csvRows.push(values.join(','));
        });
        
        return csvRows.join('\n');
    }

    downloadCSV(csv, filename) {
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        const link = document.createElement('a');
        
        if (link.download !== undefined) {
            const url = URL.createObjectURL(blob);
            link.setAttribute('href', url);
            link.setAttribute('download', filename);
            link.style.visibility = 'hidden';
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
        }
    }
}

// Initialize the app when the DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
    const app = new App();
    window.app = app; // Make it globally accessible for debugging
});

// Add some utility functions for the UI
window.utils = {
    formatNumber: (num, decimals = 2) => {
        if (num === null || num === undefined) return 'N/A';
        return Number(num).toFixed(decimals);
    },
    
    formatBytes: (bytes) => {
        if (bytes === null || bytes === undefined) return 'N/A';
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        if (bytes === 0) return '0 Bytes';
        const i = Math.floor(Math.log(bytes) / Math.log(1024));
        return Math.round(bytes / Math.pow(1024, i) * 100) / 100 + ' ' + sizes[i];
    },
    
    formatDuration: (milliseconds) => {
        if (milliseconds === null || milliseconds === undefined) return 'N/A';
        const seconds = milliseconds / 1000;
        if (seconds < 60) return seconds.toFixed(2) + 's';
        const minutes = Math.floor(seconds / 60);
        const remainingSeconds = seconds % 60;
        return minutes + 'm ' + remainingSeconds.toFixed(1) + 's';
    },
    
    formatPercentage: (value) => {
        if (value === null || value === undefined) return 'N/A';
        return (value * 100).toFixed(1) + '%';
    }
};