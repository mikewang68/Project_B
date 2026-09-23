"""Local navigation preview with real TRUST responses from the existing gateway."""
import argparse
import http.client
import http.server
import json
import pathlib
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'deploy'))
from deployment import load
from gateway_portal import site_files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18280)
    parser.add_argument('--audience', choices=('public', 'internal'), default='internal')
    parser.add_argument('--trust-origin', help='Optional local TRUST origin, for example http://127.0.0.1:18181')
    args = parser.parse_args()
    config = load()
    files = site_files(config, args.audience)
    if not files:
        raise ValueError('Configure httpAccess.portal before previewing')
    if args.audience == 'public':
        trust_host = trust_port = trust_path = None
    elif args.trust_origin:
        origin = urllib.parse.urlsplit(args.trust_origin)
        if origin.scheme != 'http' or not origin.hostname or origin.path not in ('', '/'):
            raise ValueError('--trust-origin must be an HTTP origin without a path')
        trust_host, trust_port, trust_path = origin.hostname, origin.port or 80, '/'
    else:
        trust_host = config['httpAccess']['gateway']['accessAddress']
        trust_port = config['httpAccess']['gateway']['ports']['trust']
        probe = http.client.HTTPConnection(trust_host, trust_port, timeout=10)
        try:
            probe.request('GET', '/')
            response = probe.getresponse()
            if response.status != 200:
                raise RuntimeError('Existing gateway is unavailable')
            response.read()
            trust_path = '/'
        finally:
            probe.close()
    if args.audience == 'internal':
        catalogue = json.loads(files['portal-assets/services.json'])
        for service in catalogue['services']:
            if service['id'] == 'trust':
                service['url'] = '/trust/'
        files['portal-assets/services.json'] = json.dumps(catalogue, ensure_ascii=False, indent=2).encode('utf-8')

    class Handler(http.server.BaseHTTPRequestHandler):
        def handle_request(self):
            path = urllib.parse.urlsplit(self.path).path
            name = 'index.html' if path == '/' else path.lstrip('/')
            if self.command in ('GET', 'HEAD') and name in files:
                data = files[name]
                kind = {'.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript', '.json': 'application/json'}[pathlib.Path(name).suffix]
                self.send_response(200)
                self.send_header('Content-Type', kind + '; charset=utf-8')
                self.send_header('Content-Length', str(len(data)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                if self.command != 'HEAD': self.wfile.write(data)
                return
            if args.audience == 'internal' and path == '/trust':
                self.send_response(302); self.send_header('Location', '/trust/'); self.end_headers()
                return
            if args.audience != 'internal' or (path != '/trust/' and not path.startswith(('/assets/', '/api/v1/', '/actuator/', '/__dev/quick-login'))):
                self.send_error(404)
                return
            connection = http.client.HTTPConnection(trust_host, trust_port, timeout=30)
            try:
                body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                excluded = {'host', 'connection', 'transfer-encoding', 'accept-encoding'}
                headers = {k: v for k, v in self.headers.items() if k.lower() not in excluded}
                connection.request(self.command, trust_path if path == '/trust/' else self.path, body, headers)
                response = connection.getresponse()
                data = response.read()
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in {'connection', 'transfer-encoding', 'content-length'}:
                        self.send_header(key, value)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                if self.command != 'HEAD': self.wfile.write(data)
            finally:
                connection.close()

        do_GET = do_POST = do_HEAD = do_DELETE = handle_request

        def log_message(self, *_):
            pass

    print(args.audience.capitalize() + ' navigation preview on http://127.0.0.1:' + str(args.port), flush=True)
    with http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
