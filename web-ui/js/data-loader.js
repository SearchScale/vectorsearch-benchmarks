class DataLoader {
    constructor() {
        this.data = [];
        this.originalData = [];
    }

    async loadData() {
        try {
            // First try to load multiple CSV files from reports directory
            let allData = await this.loadMultipleCSVFiles();
            
            if (allData.length === 0) {
                // Fallback to single file loading
                console.log('No CSV files found in reports directory, trying single file fallback');
                allData = await this.loadSingleCSVFile();
            }
            
            this.originalData = allData;
            this.data = [...this.originalData];
            console.log(`Loaded ${this.data.length} runs from CSV files`);
            return this.data;
        } catch (error) {
            console.error('Error loading data:', error);
            throw error;
        }
    }

    async loadMultipleCSVFiles() {
        try {
            // Get list of CSV files in reports directory
            const csvFiles = await this.getCSVFilesFromReports();
            console.log(`Found ${csvFiles.length} CSV files in reports directory`);
            
            const allData = [];
            
            // Load each CSV file
            for (const fileName of csvFiles) {
                try {
                    const response = await fetch(`../reports/${fileName}`);
                    if (response.ok) {
                        const csvText = await response.text();
                        const fileData = this.parseCSV(csvText);
                        allData.push(...fileData);
                        console.log(`Loaded ${fileData.length} runs from ${fileName}`);
                    }
                } catch (error) {
                    console.warn(`Failed to load ${fileName}:`, error);
                }
            }
            
            return allData;
        } catch (error) {
            console.warn('Failed to load multiple CSV files:', error);
            return [];
        }
    }

    async getCSVFilesFromReports() {
        try {
            const csvFiles = [];
            
            // Try to use a directory listing endpoint first
            try {
                const response = await fetch('../list-csv-files');
                if (response.ok) {
                    const files = await response.json();
                    return files.filter(f => f.endsWith('.csv') && f.match(/^\d{2}-\d{2}-\d{4}-[a-f0-9]+\.csv$/));
                }
            } catch (error) {
                console.log('No directory listing endpoint available, trying known files');
            }
            
            // Fallback: Try known file patterns and existing files
            const knownFiles = [
                '15-09-2025-60529730.csv',
                '15-09-2025-7116054.csv', 
                '16-09-2025-13671303.csv',
                '16-09-2025-14135507.csv'
            ];
            
            // Test which files exist
            for (const fileName of knownFiles) {
                try {
                    const response = await fetch(`../reports/${fileName}`, { method: 'HEAD' });
                    if (response.ok) {
                        csvFiles.push(fileName);
                    }
                } catch (error) {
                    // File doesn't exist, continue
                }
            }
            
            // Also try to discover files by pattern matching for recent dates
            const today = new Date();
            const recentDates = [];
            
            // Generate dates for last 7 days
            for (let i = 0; i < 7; i++) {
                const date = new Date(today);
                date.setDate(today.getDate() - i);
                const dateStr = date.toISOString().split('T')[0];
                const [year, month, day] = dateStr.split('-');
                const formattedDate = `${day}-${month}-${year}`;
                recentDates.push(formattedDate);
            }
            
            // Try common hash patterns for recent dates
            const commonHashes = [
                '60529730', '7116054', '13671303', '14135507',
                'a1b2c3d4', 'e5f6g7h8', '9i0j1k2l', 'm3n4o5p6'
            ];
            
            for (const date of recentDates) {
                for (const hash of commonHashes) {
                    const fileName = `${date}-${hash}.csv`;
                    if (!csvFiles.includes(fileName)) {
                        try {
                            const response = await fetch(`../reports/${fileName}`, { method: 'HEAD' });
                            if (response.ok) {
                                csvFiles.push(fileName);
                            }
                        } catch (error) {
                            // File doesn't exist, continue
                        }
                    }
                }
            }
            
            return csvFiles;
        } catch (error) {
            console.warn('Failed to get CSV file list:', error);
            return [];
        }
    }

    async loadSingleCSVFile() {
        // Try to load from reports directory first
        let response = await fetch('../reports/benchmark_results.csv');
        
        if (!response.ok) {
            // Fallback to web-ui data directory
            response = await fetch('data/consolidated_results.csv');
        }
        
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        const csvText = await response.text();
        return this.parseCSV(csvText);
    }

    parseCSV(csvText) {
        try {
            const lines = csvText.trim().split('\n');
            if (lines.length < 2) {
                throw new Error('CSV file appears to be empty or invalid');
            }
            
            const headers = lines[0].split(',').map(h => h.trim());
            const data = [];

            for (let i = 1; i < lines.length; i++) {
                const values = this.parseCSVLine(lines[i]);
                
                // Handle cases where fields might be missing (pad with empty strings)
                while (values.length < headers.length) {
                    values.push('');
                }
                
                // Truncate if too many fields
                if (values.length > headers.length) {
                    values.splice(headers.length);
                }
                
                const row = {};
                headers.forEach((header, index) => {
                    row[header] = values[index] || '';
                });
                data.push(this.cleanRow(row));
            }

            console.log(`Successfully parsed ${data.length} rows from CSV`);
            return data;
        } catch (error) {
            console.error('Error parsing CSV:', error);
            throw error;
        }
    }

    parseCSVLine(line) {
        // Handle CSV parsing with proper field counting
        const fields = [];
        let currentField = '';
        let inQuotes = false;
        
        for (let i = 0; i < line.length; i++) {
            const char = line[i];
            
            if (char === '"') {
                inQuotes = !inQuotes;
            } else if (char === ',' && !inQuotes) {
                fields.push(currentField.trim());
                currentField = '';
            } else {
                currentField += char;
            }
        }
        
        // Add the last field
        fields.push(currentField.trim());
        
        return fields;
    }

    cleanRow(row) {
        // Clean and structure the data, focusing on varying parameters
        const cleaned = {
            runId: row['runId'] || '',
            algo: row['algorithm'] || '', // Updated to match new CSV header
            dataset: row['dataset'] || '',
            indexingTime: parseFloat(row['indexingTime']) || 0,
            queryTime: parseFloat(row['queryTime']) || 0,
            recall: parseFloat(row['recall']) || 0,
            qps: parseFloat(row['qps']) || 0,
            meanLatency: parseFloat(row['meanLatency']) || 0,
            indexSize: parseFloat(row['indexSize']) || 0,
            segmentCount: parseInt(row['segmentCount']) || 0,
            efSearch: parseInt(row['efSearch']) || 0,
            createdAt: row['createdAt'] || '',
            sweepId: row['unique_sweep_id'] || row['sweepId'] || this.generateSweepId(row), // Use unique_sweep_id if available
            commitId: row['commitId'] || this.generateCommitId(row),
            parameters: this.extractParameters(row),
            // Add new CSV fields - handle empty strings properly
            cagraGraphDegree: row['cagraGraphDegree'] && row['cagraGraphDegree'] !== '' ? parseInt(row['cagraGraphDegree']) : 0,
            cagraIntermediateGraphDegree: row['cagraIntermediateGraphDegree'] && row['cagraIntermediateGraphDegree'] !== '' ? parseInt(row['cagraIntermediateGraphDegree']) : 0,
            cuvsWriterThreads: row['cuvsWriterThreads'] && row['cuvsWriterThreads'] !== '' ? parseInt(row['cuvsWriterThreads']) : 0,
            hnswMaxConn: row['hnswMaxConn'] && row['hnswMaxConn'] !== '' ? parseInt(row['hnswMaxConn']) : 0,
            hnswBeamWidth: row['hnswBeamWidth'] && row['hnswBeamWidth'] !== '' ? parseInt(row['hnswBeamWidth']) : 0
        };

        return cleaned;
    }

    generateCommitId(row) {
        // Try to extract commit ID from runId or use a default
        const runId = row['runId'] || '';
        if (runId.length >= 8) {
            return runId.substring(0, 8);
        }
        return 'unknown';
    }

    generateSweepId(row) {
        const date = row['createdAt'] ? row['createdAt'].substring(0, 10) : new Date().toISOString().substring(0, 10);
        return `${row['dataset'] || 'unknown'}_${date}`;
    }

    extractParameters(row) {
        const params = [];
        
        // Only include parameters that have values and are varying
        if (row['cagraGraphDegree']) {
            params.push({ label: 'Graph Degree', value: row['cagraGraphDegree'] });
        }
        if (row['cagraIntermediateGraphDegree']) {
            params.push({ label: 'Int. Graph Degree', value: row['cagraIntermediateGraphDegree'] });
        }
        if (row['hnswMaxConn']) {
            params.push({ label: 'HNSW Max Conn', value: row['hnswMaxConn'] });
        }
        if (row['hnswBeamWidth']) {
            params.push({ label: 'HNSW Beam Width', value: row['hnswBeamWidth'] });
        }
        if (row['cuvsWriterThreads']) {
            params.push({ label: 'CUVS Writer Threads', value: row['cuvsWriterThreads'] });
        }
        if (row['efSearch']) {
            params.push({ label: 'EF Search', value: row['efSearch'] });
        }
        if (row['segmentCount']) {
            params.push({ label: 'Segments', value: row['segmentCount'] });
        }
        if (row['hnsw-segment-count']) {
            params.push({ label: 'HNSW Segments', value: row['hnsw-segment-count'] });
        }

        return params;
    }

    getUniqueValues(column) {
        const values = [...new Set(this.originalData.map(row => row[column]))];
        return values.filter(value => value !== '' && value !== null && value !== undefined);
    }

    resetData() {
        this.data = [...this.originalData];
    }

    getData() {
        return this.data;
    }

    getOriginalData() {
        return this.originalData;
    }

    /**
     * Filter data for sift-1m dataset only and show only the latest sweep with 9 runs
     */
    filterForSift1M(data) {
        return data.filter(run => run.dataset === 'sift-1m');
    }
}
