#!/usr/bin/env python3
"""
Simple HTTP stub to simulate an Account Service for testing the eventGateway.
Usage:
  python stub_account_service.py --port 8081

Endpoints:
  GET /accounts/{id}           -> 200 OK, JSON {"accountId":"{id}"}
  GET /accounts/{id}?delay=5   -> sleeps 5 seconds before responding (useful to trigger timeouts)
  GET /accounts/{id}?fail=1    -> responds 500 Internal Server Error

This script is intentionally minimal and has no dependencies beyond the Python standard library.
"""

import argparse
import json
import time
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs


class StubHandler(BaseHTTPRequestHandler):
    def _set_json(self, code=200):
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)

        parts = path.strip('/').split('/')
        if len(parts) >= 2 and parts[0] == 'accounts':
            account_id = parts[1]
            # simulate behaviors
            if 'fail' in qs and qs.get('fail', ['0'])[0] == '1':
                self._set_json(500)
                self.wfile.write(json.dumps({'error': 'simulated-failure'}).encode())
                return
            if 'delay' in qs:
                try:
                    d = float(qs.get('delay',[0])[0])
                    time.sleep(d)
                except Exception:
                    pass
            self._set_json(200)
            self.wfile.write(json.dumps({'accountId': account_id}).encode())
            return

        # Default: Not Found
        self._set_json(404)
        self.wfile.write(json.dumps({'error': 'not_found'}).encode())

    def log_message(self, format, *args):
        # simple logging to stdout
        print("[stub_account_service] %s - - %s" % (self.address_string(), format%args))


def run(port):
    server = HTTPServer(('0.0.0.0', port), StubHandler)
    print(f"Stub Account Service listening on http://0.0.0.0:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\nShutting down')
        server.server_close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8081)
    args = parser.parse_args()
    run(args.port)

