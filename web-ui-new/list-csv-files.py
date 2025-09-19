#!/usr/bin/env python3

import os
import json
import re
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

class CSVFileHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed_path = urlparse(self.path)
        
        if parsed_path.path == '/list-csv-files':
            self.list_csv_files()
        elif parsed_path.path == '/list-sweep-dirs':
            self.list_sweep_dirs()
        else:
            self.send_error(404, "Not Found")
    
    def send_json_response(self, data):
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())
    
    def list_csv_files(self):
        try:
            results_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'results', 'raw')
            
            if not os.path.exists(results_dir):
                self.send_json_response([])
                return
            
            csv_files = []
            for item in os.listdir(results_dir):
                item_path = os.path.join(results_dir, item)
                if os.path.isdir(item_path):
                    csv_file = os.path.join(item_path, f"{item}.csv")
                    if os.path.exists(csv_file):
                        csv_files.append({
                            'sweep_id': item,
                            'csv_path': f"../results/raw/{item}/{item}.csv"
                        })
            
            self.send_json_response(csv_files)
        except Exception as e:
            self.send_json_response({'error': str(e)})
    
    def list_sweep_dirs(self):
        try:
            results_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'results', 'raw')
            
            if not os.path.exists(results_dir):
                self.send_json_response([])
                return
            
            sweep_dirs = []
            for item in os.listdir(results_dir):
                item_path = os.path.join(results_dir, item)
                if os.path.isdir(item_path) and re.match(r'^\d{2}-\d{2}-\d{4}-[a-f0-9]+-\d{6}$', item):
                    csv_file = os.path.join(item_path, f"{item}.csv")
                    if os.path.exists(csv_file):
                        sweep_dirs.append(item)
            
            self.send_json_response(sweep_dirs)
        except Exception as e:
            self.send_json_response({'error': str(e)})

if __name__ == '__main__':
    server = HTTPServer(('localhost', 8001), CSVFileHandler)
    print("CSV API server running on http://localhost:8001")
    server.serve_forever()
