#!/usr/bin/env python3
"""Isolated fixed-byte tunnel diagnostic. Never connects to a provider or database.
Bind loopback only; lifetime <=30min, 20-stream concurrency and 300-request budget.
Use a separate temporary tunnel. Do not add this handler to the production edge.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

PAGE = b'''<!doctype html><html><head><meta charset="utf-8"><title>Isolated tunnel diagnostic</title></head><body><h1>Isolated tunnel diagnostic</h1><p>Fixed test bytes only. No provider, accounts, credentials, or production data.</p></body></html>'''
class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64
    def __init__(self, address):
        super().__init__(address, Handler)
        self.lock = threading.Lock()
        self.active = 0
        self.peak = 0
        self.accepted = 0
        self.completed = 0
        self.disconnected = 0
        self.rejected = 0

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_): pass
    def reply(self, status, payload, content_type='application/json'):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(payload)
    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/': self.reply(200, PAGE, 'text/html; charset=utf-8')
        elif path == '/favicon.ico': self.reply(200, b'', 'image/x-icon')
        elif path == '/stats':
            with self.server.lock:
                data={key:getattr(self.server,key) for key in ('active','peak','accepted','completed','disconnected','rejected')}
            self.reply(200, json.dumps(data).encode())
        else: self.reply(404, b'{}')
    def do_POST(self):
        parsed = urlsplit(self.path)
        if parsed.path != '/stream': self.close_connection=True; self.reply(404,b'{}'); return
        try: length=int(self.headers.get('Content-Length','0'))
        except ValueError: length=-1
        if length < 0 or length > 1024 or self.headers.get('Transfer-Encoding'):
            self.close_connection=True; self.reply(413,b'{}'); return
        self.connection.settimeout(10)
        if len(self.rfile.read(length))!=length: self.close_connection=True; return
        mode=parse_qs(parsed.query).get('mode',['sse'])[0]
        if mode not in ('sse','ndjson'): self.reply(400,b'{}'); return
        with self.server.lock:
            rejected=self.server.active>=20 or self.server.accepted>=300
            if rejected: self.server.rejected+=1
            else:
                self.server.active+=1; self.server.accepted+=1
                self.server.peak=max(self.server.peak,self.server.active)
        if rejected: self.reply(429,b'{}'); return
        try:
            self.send_response(200)
            self.send_header('Content-Type','text/event-stream' if mode=='sse' else 'application/x-ndjson')
            self.send_header('Cache-Control','no-store, no-transform')
            self.send_header('X-Accel-Buffering','no')
            self.send_header('Transfer-Encoding','chunked')
            self.end_headers()
            for seq in range(16):
                body=json.dumps({'seq':seq},separators=(',',':')).encode()
                body=(b'data: '+body+b'\n\n') if mode=='sse' else body+b'\n'
                self.wfile.write(('%x\r\n'%len(body)).encode()+body+b'\r\n')
                self.wfile.flush()
                time.sleep(.2)
            self.wfile.write(b'0\r\n\r\n'); self.wfile.flush()
            with self.server.lock:self.server.completed+=1
        except (OSError, TimeoutError):
            with self.server.lock:self.server.disconnected+=1
        finally:
            with self.server.lock:self.server.active-=1

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=8186)
    parser.add_argument('--seconds',type=int,default=1200)
    args=parser.parse_args()
    if not 1024<=args.port<=65535 or not 10<=args.seconds<=1800:parser.error('Invalid port or lifetime')
    server=Server(('127.0.0.1',args.port))
    stop=threading.Timer(args.seconds,server.shutdown);stop.daemon=True;stop.start()
    try:server.serve_forever()
    finally:server.server_close();stop.cancel()
