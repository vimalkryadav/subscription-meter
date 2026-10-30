"""Local protocol fixture. Never contacts Anthropic."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import time

USAGE = dict(input_tokens=120, output_tokens=17, cache_creation_input_tokens=30, cache_read_input_tokens=80)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"data":[{"id":"mock-model","type":"model","display_name":"Mock model"}]}')

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        self.send_response(200)
        self.send_header("request-id", "mock-request")
        if self.path.startswith("/v1/messages/count_tokens"):
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"input_tokens":120}')
        elif data.get("stream"):
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            events = [
                {"type": "message_start", "message": {"id": "msg_mock", "type": "message", "role": "assistant", "model": "mock-model", "content": [], "usage": {**USAGE, "output_tokens": 1}}},
                {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}},
                {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "Mock response. No subscription was used."}},
                {"type": "content_block_stop", "index": 0},
                {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": None}, "usage": {"output_tokens": 17}},
                {"type": "message_stop"},
            ]
            try:
                for event in events:
                    self.wfile.write(("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n").encode())
                    self.wfile.flush()
                    time.sleep(0.05)
            except (BrokenPipeError, ConnectionResetError):
                pass
        else:
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"id": "msg_mock", "type": "message", "role": "assistant", "model": "mock-model", "content": [{"type": "text", "text": "Mock response"}], "stop_reason": "end_turn", "usage": USAGE}).encode())


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8081), Handler).serve_forever()
