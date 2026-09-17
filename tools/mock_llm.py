"""Mock OpenAI 兼容 /chat/completions 服务，用于模拟器内验证App的大模型链路"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        auth = self.headers.get("Authorization", "")
        print(f"[mock] POST {self.path} auth={auth} model={body.get('model')}", flush=True)
        last_user = ""
        for m in body.get("messages", []):
            if m.get("role") == "user":
                last_user = m.get("content", "")
        reply = f"（小乐模拟回答）您刚才说的是：{last_user}。今天天气不错，记得多喝水。"
        resp = json.dumps({
            "id": "chatcmpl-mock", "object": "chat.completion", "created": 0,
            "model": body.get("model", "mock"),
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": reply}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def log_message(self, *a):
        pass

if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8000), H).serve_forever()
