class FilterManager {
    constructor(dataLoader, tableManager) {
        this.dataLoader = dataLoader;
        this.tableManager = tableManager;
        this.filters = {
            dataset: '',
            algorithm: '',
            minRecall: null
        };
    }

    init() {
        this.populateFilterOptions();
        this.setupEventListeners();
    }

    populateFilterOptions() {
        const data = this.dataLoader.getOriginalData();
        
        // Populate dataset filter
        const datasets = [...new Set(data.map(row => row.dataset))].sort();
        const datasetSelect = document.getElementById('dataset-filter');
        if (datasetSelect) {
            datasets.forEach(dataset => {
                const option = document.createElement('option');
                option.value = dataset;
                option.textContent = dataset;
                datasetSelect.appendChild(option);
            });
        }

        // Populate algorithm filter
        const algorithms = [...new Set(data.map(row => row.algorithm))].sort();
        const algorithmSelect = document.getElementById('algorithm-filter');
        if (algorithmSelect) {
            algorithms.forEach(algorithm => {
                const option = document.createElement('option');
                option.value = algorithm;
                option.textContent = algorithm.replace('_HNSW', '');
                algorithmSelect.appendChild(option);
            });
        }
    }

    setupEventListeners() {
        // Only set up event listeners if elements exist
        const datasetFilter = document.getElementById('dataset-filter');
        if (datasetFilter) {
            datasetFilter.addEventListener('change', (e) => {
                this.filters.dataset = e.target.value;
                this.applyFilters();
            });
        }

        const algorithmFilter = document.getElementById('algorithm-filter');
        if (algorithmFilter) {
            algorithmFilter.addEventListener('change', (e) => {
                this.filters.algorithm = e.target.value;
                this.applyFilters();
            });
        }

        const recallFilter = document.getElementById('recall-filter');
        if (recallFilter) {
            recallFilter.addEventListener('input', (e) => {
                this.filters.minRecall = e.target.value ? parseFloat(e.target.value) : null;
                this.applyFilters();
            });
        }

        const clearFiltersBtn = document.getElementById('clear-filters');
        if (clearFiltersBtn) {
            clearFiltersBtn.addEventListener('click', () => {
                this.clearFilters();
            });
        }
    }

    applyFilters() {
        const data = this.dataLoader.getOriginalData();
        let filteredData = [...data];

        // Apply dataset filter
        if (this.filters.dataset) {
            filteredData = filteredData.filter(row => row.dataset === this.filters.dataset);
        }

        // Apply algorithm filter
        if (this.filters.algorithm) {
            filteredData = filteredData.filter(row => row.algorithm === this.filters.algorithm);
        }

        // Apply recall filter
        if (this.filters.minRecall !== null) {
            filteredData = filteredData.filter(row => row.recall >= this.filters.minRecall);
        }

        this.tableManager.setFilteredData(filteredData);
    }

    clearFilters() {
        // Reset filter values
        document.getElementById('dataset-filter').value = '';
        document.getElementById('algorithm-filter').value = '';
        document.getElementById('recall-filter').value = '';

        // Reset filter state
        this.filters = {
            dataset: '',
            algorithm: '',
            minRecall: null
        };

        // Reset table
        this.tableManager.setFilteredData([]);
    }
}


