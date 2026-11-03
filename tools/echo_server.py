"""回显服务器：把收到的请求原样打印出来，用于对比 Java / Python 的线上字节。

用法: python tools/echo_server.py [port]
"""
from __future__ import annotations

import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8799


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def _dump(self, method):
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n) if n else b''
        print('==== %s %s' % (method, self.path), flush=True)
        for k, v in self.headers.items():
            print('  %s: %s' % (k, v), flush=True)
        print('  --- body (%d bytes) ---' % len(body), flush=True)
        try:
            print('  ' + body.decode('utf-8'), flush=True)
        except UnicodeDecodeError:
            print('  <%d binary bytes>' % len(body), flush=True)
        print(flush=True)
        payload = b'{"result":"0","message":"SUCCESS","data":{"videoList":[]}}'
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        self._dump('POST')

    def do_GET(self):
        self._dump('GET')

    def log_message(self, *a):
        pass


def main():
    srv = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    print('echo server on 127.0.0.1:%d' % PORT, flush=True)
    srv.serve_forever()


if __name__ == '__main__':
    main()
