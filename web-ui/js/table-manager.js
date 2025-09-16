class TableManager {
    constructor(dataLoader) {
        this.dataLoader = dataLoader;
        this.currentSort = { column: null, direction: 'asc' };
        this.currentPage = 1;
        this.rowsPerPage = 50;
        this.filteredData = [];
    }

    init() {
        this.setupEventListeners();
        this.renderTable();
    }

    setupEventListeners() {
        // Sort functionality
        document.querySelectorAll('.sortable').forEach(header => {
            header.addEventListener('click', (e) => {
                const column = e.target.getAttribute('data-column');
                this.sort(column);
            });
        });

        // Pagination
        document.getElementById('prev-page').addEventListener('click', () => {
            this.previousPage();
        });

        document.getElementById('next-page').addEventListener('click', () => {
            this.nextPage();
        });
    }

    sort(column) {
        if (this.currentSort.column === column) {
            this.currentSort.direction = this.currentSort.direction === 'asc' ? 'desc' : 'asc';
        } else {
            this.currentSort.column = column;
            this.currentSort.direction = 'asc';
        }

        this.updateSortHeaders();
        this.renderTable();
    }

    updateSortHeaders() {
        document.querySelectorAll('.sortable').forEach(header => {
            header.classList.remove('sort-asc', 'sort-desc');
            if (header.getAttribute('data-column') === this.currentSort.column) {
                header.classList.add(`sort-${this.currentSort.direction}`);
            }
        });
    }

    setFilteredData(data) {
        this.filteredData = data;
        this.currentPage = 1;
        this.renderTable();
    }

    renderTable() {
        const data = this.filteredData.length > 0 ? this.filteredData : this.dataLoader.getData();
        const sortedData = this.sortData(data);
        const paginatedData = this.paginateData(sortedData);
        
        this.renderTableBody(paginatedData);
        this.updatePagination(data.length);
        this.updateStats(data.length);
    }

    sortData(data) {
        if (!this.currentSort.column) return data;

        return [...data].sort((a, b) => {
            let aVal = a[this.currentSort.column];
            let bVal = b[this.currentSort.column];

            // Handle numeric values
            if (typeof aVal === 'number' && typeof bVal === 'number') {
                return this.currentSort.direction === 'asc' ? aVal - bVal : bVal - aVal;
            }

            // Handle string values
            aVal = String(aVal).toLowerCase();
            bVal = String(bVal).toLowerCase();

            if (aVal < bVal) return this.currentSort.direction === 'asc' ? -1 : 1;
            if (aVal > bVal) return this.currentSort.direction === 'asc' ? 1 : -1;
            return 0;
        });
    }

    paginateData(data) {
        const startIndex = (this.currentPage - 1) * this.rowsPerPage;
        const endIndex = startIndex + this.rowsPerPage;
        return data.slice(startIndex, endIndex);
    }

    renderTableBody(data) {
        const tbody = document.getElementById('table-body');
        
        if (data.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="12" class="empty-state">
                        <h3>No data found</h3>
                        <p>Try adjusting your filters</p>
                    </td>
                </tr>
            `;
            return;
        }

        tbody.innerHTML = data.map(row => this.createTableRow(row)).join('');
    }

    createTableRow(row) {
        const algorithmClass = row.algorithm === 'CAGRA_HNSW' ? 'algorithm-cagra' : 'algorithm-lucene';
        const parametersHtml = row.parameters.map(param => 
            `<div class="parameter-item">
                <span class="parameter-label">${param.label}:</span> ${param.value}
            </div>`
        ).join('');

        return `
            <tr>
                <td>
                    <span class="algorithm-badge ${algorithmClass}">
                        ${row.algorithm.replace('_HNSW', '')}
                    </span>
                </td>
                <td>${row.dataset}</td>
                <td>${row.indexingTime.toFixed(2)}</td>
                <td>${row.queryTime.toFixed(2)}</td>
                <td>${row.recall.toFixed(2)}</td>
                <td>${row.qps.toFixed(3)}</td>
                <td>${row.meanLatency.toFixed(1)}</td>
                <td>${(row.indexSize / 1024 / 1024).toFixed(1)}</td>
                <td>${row.segmentCount}</td>
                <td>${row.efSearch || '-'}</td>
                <td>
                    <div class="parameters">
                        ${parametersHtml}
                    </div>
                </td>
                <td>
                    <div class="run-id">${row.runId}</div>
                </td>
                <td>
                    <div class="action-buttons">
                        <button class="btn btn-small btn-primary" onclick="runDetailsManager.showRunDetails('${row.runId}')">
                            View Details
                        </button>
                        <button class="btn btn-small btn-secondary" onclick="runDetailsManager.downloadFile('stdout')">
                            Download Logs
                        </button>
                    </div>
                </td>
            </tr>
        `;
    }

    updatePagination(totalRows) {
        const totalPages = Math.ceil(totalRows / this.rowsPerPage);
        const pageInfo = document.getElementById('page-info');
        const prevBtn = document.getElementById('prev-page');
        const nextBtn = document.getElementById('next-page');

        pageInfo.textContent = `Page ${this.currentPage} of ${totalPages}`;
        prevBtn.disabled = this.currentPage === 1;
        nextBtn.disabled = this.currentPage === totalPages || totalPages === 0;
    }

    updateStats(totalRows) {
        const originalTotal = this.dataLoader.getOriginalData().length;
        document.getElementById('total-runs').textContent = `${originalTotal} total runs`;
        
        if (totalRows !== originalTotal) {
            document.getElementById('filtered-runs').textContent = `(${totalRows} filtered)`;
        } else {
            document.getElementById('filtered-runs').textContent = '';
        }
    }

    previousPage() {
        if (this.currentPage > 1) {
            this.currentPage--;
            this.renderTable();
        }
    }

    nextPage() {
        const totalRows = this.filteredData.length > 0 ? this.filteredData.length : this.dataLoader.getData().length;
        const totalPages = Math.ceil(totalRows / this.rowsPerPage);
        
        if (this.currentPage < totalPages) {
            this.currentPage++;
            this.renderTable();
        }
    }

    /**
     * Update table with specific data (for sweep details)
     */
    updateTableWithData(runs) {
        const tbody = document.getElementById('results-table-body');
        if (!tbody) {
            console.error('Results table body not found');
            return;
        }

        if (!runs || runs.length === 0) {
            tbody.innerHTML = '<tr><td colspan="10" class="empty-state">No runs found for this sweep</td></tr>';
            return;
        }

        tbody.innerHTML = '';
        runs.forEach(run => {
            const row = this.createTableRow(run);
            tbody.appendChild(row);
        });

        // Update pagination info
        const totalPages = Math.ceil(runs.length / this.rowsPerPage);
        document.getElementById('page-info').textContent = `Page 1 of ${totalPages}`;
        
        // Disable pagination buttons for sweep view
        document.getElementById('prev-page').disabled = true;
        document.getElementById('next-page').disabled = true;
    }
}
