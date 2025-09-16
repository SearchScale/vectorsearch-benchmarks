#!/usr/bin/env python3
"""
Simple HTTP server to serve the web UI with proper CORS headers.
This avoids CORS issues when loading CSV files from the browser.
"""

import http.server
import socketserver
import os
import sys
import json
import re
from pathlib import Path

class CORSRequestHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.end_headers()
    
    def do_GET(self):
        if self.path == '/list-csv-files':
            self.handle_list_csv_files()
        else:
            super().do_GET()
    
    def handle_list_csv_files(self):
        """List CSV files in the reports directory"""
        try:
            reports_dir = Path('reports')
            if reports_dir.exists():
                # Find CSV files with date-hash pattern
                csv_files = []
                pattern = re.compile(r'^\d{2}-\d{2}-\d{4}-[a-f0-9]+\.csv$')
                
                for file_path in reports_dir.glob('*.csv'):
                    if pattern.match(file_path.name):
                        csv_files.append(file_path.name)
                
                # Sort by filename (which includes date)
                csv_files.sort(reverse=True)
                
                self.send_response(200)
                self.send_header('Content-type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(csv_files).encode())
            else:
                self.send_response(404)
                self.end_headers()
                self.wfile.write(b'Reports directory not found')
        except Exception as e:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(f'Error listing files: {str(e)}'.encode())

def main():
    # Change to the project root directory (not web-ui)
    project_root = Path(__file__).parent
    os.chdir(project_root)
    
    port = 8000
    if len(sys.argv) > 1:
        port = int(sys.argv[1])
    
    print(f"Serving web UI at http://localhost:{port}")
    print(f"Directory: {project_root}")
    print("Press Ctrl+C to stop the server")
    
    with socketserver.TCPServer(("", port), CORSRequestHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nServer stopped.")

if __name__ == '__main__':
    main()
