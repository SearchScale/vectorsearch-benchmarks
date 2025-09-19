#!/usr/bin/env python3

import os
import json
import re
from http.server import HTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse

class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        path = urlparse(self.path).path
        
        if path == '/list-sweep-dirs':
            self.send_json(self.get_sweep_dirs())
        elif path == '/list-datasets':
            self.send_json(self.get_all_datasets())
        elif path.startswith('/results/'):
            self.serve_results_file(path)
        else:
            # Serve static files (HTML, CSS, JS, etc.)
            if path == '/':
                self.path = '/index.html'
            super().do_GET()
    
    def serve_results_file(self, path):
        # Remove /results/ prefix and construct path to actual results directory
        file_path = path[8:]  # Remove '/results/'
        # Remove leading slash if present
        if file_path.startswith('/'):
            file_path = file_path[1:]
        # Use current working directory (web-ui-new) and go up one level to results
        full_path = os.path.join(os.getcwd(), '..', 'results', file_path)
        
        if os.path.exists(full_path) and os.path.isfile(full_path):
            with open(full_path, 'rb') as f:
                content = f.read()
            self.send_response(200)
            self.send_header('Content-type', 'application/octet-stream')
            self.end_headers()
            self.wfile.write(content)
        else:
            self.send_error(404, "Not Found")
    
    def send_json(self, data):
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())
    
    def get_sweep_dirs(self):
        results_dir = os.path.join(os.getcwd(), '..', 'results', 'raw')
        if not os.path.exists(results_dir):
            return []
        
        sweeps = []
        for item in os.listdir(results_dir):
            item_path = os.path.join(results_dir, item)
            if os.path.isdir(item_path) and re.match(r'^\d{2}-\d{2}-\d{4}-[a-f0-9]+-\d{6}$', item):
                csv_file = os.path.join(item_path, f"{item}.csv")
                if os.path.exists(csv_file):
                    # Extract datasets and commit info
                    datasets = self.get_datasets_from_csv(csv_file)
                    commit_id = self.get_commit_id()
                    runs = self.get_runs_from_csv(csv_file)
                    
                    sweeps.append({
                        'id': item,
                        'datasets': datasets,
                        'commit_id': commit_id,
                        'date': self.extract_date_from_id(item),
                        'runs': runs
                    })
        return sweeps
    
    def get_datasets_from_csv(self, csv_file):
        try:
            with open(csv_file, 'r') as f:
                lines = f.readlines()
            if len(lines) < 2:
                return []
            
            # Find dataset column index
            headers = lines[0].strip().split(',')
            dataset_idx = headers.index('dataset') if 'dataset' in headers else -1
            if dataset_idx == -1:
                return []
            
            # Extract unique datasets, filtering out empty values
            datasets = set()
            for line in lines[1:]:
                if line.strip():  # Skip empty lines
                    values = line.strip().split(',')
                    if len(values) > dataset_idx and values[dataset_idx].strip():
                        datasets.add(values[dataset_idx].strip())
            return sorted(list(datasets))  # Return sorted list
        except Exception as e:
            print(f"Error extracting datasets from {csv_file}: {e}")
            return []
    
    def get_runs_from_csv(self, csv_file):
        try:
            with open(csv_file, 'r') as f:
                lines = f.readlines()
            if len(lines) < 2:
                return []
            
            headers = lines[0].strip().split(',')
            runs = []
            
            for line in lines[1:]:
                if line.strip():  # Skip empty lines
                    values = line.strip().split(',')
                    if len(values) >= len(headers):
                        run = {}
                        for i, header in enumerate(headers):
                            if i < len(values):
                                run[header] = values[i].strip()
                        runs.append(run)
            return runs
        except Exception as e:
            print(f"Error extracting runs from {csv_file}: {e}")
            return []
    
    def get_all_datasets(self):
        # Collect all unique datasets from all sweep CSVs
        all_datasets = set()
        results_dir = os.path.join(os.getcwd(), '..', 'results', 'raw')
        if not os.path.exists(results_dir):
            return []
        
        for item in os.listdir(results_dir):
            item_path = os.path.join(results_dir, item)
            if os.path.isdir(item_path):
                csv_file = os.path.join(item_path, f"{item}.csv")
                if os.path.exists(csv_file):
                    datasets = self.get_datasets_from_csv(csv_file)
                    all_datasets.update(datasets)
        
        return sorted(list(all_datasets))

    def get_commit_id(self):
        try:
            pom_path = os.path.join(os.getcwd(), '..', 'pom.xml')
            with open(pom_path, 'r') as f:
                content = f.read()
            # Extract commit from cuvs-lucene version
            match = re.search(r'cuvs-lucene.*?version>([^-]+)-([a-f0-9]+)-SNAPSHOT', content, re.DOTALL)
            return match.group(2) if match else 'unknown'
        except:
            return 'unknown'
    
    def extract_date_from_id(self, sweep_id):
        match = re.match(r'^(\d{2})-(\d{2})-(\d{4})', sweep_id)
        return f"{match.group(3)}-{match.group(2)}-{match.group(1)}" if match else 'Unknown'
    
    def get_all_datasets(self):
        sweeps = self.get_sweep_dirs()
        all_datasets = set()
        for sweep in sweeps:
            all_datasets.update(sweep['datasets'])
        return sorted(list(all_datasets))

if __name__ == '__main__':
    port = int(os.sys.argv[1]) if len(os.sys.argv) > 1 else 8000
    # Change to the web-ui-new directory
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    server = HTTPServer(('localhost', port), Handler)
    print(f"Server running on http://localhost:{port}")
    server.serve_forever()
