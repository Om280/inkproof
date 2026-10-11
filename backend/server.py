"""Explicitly invoked Gemini proxy; never ships credentials to the Android client."""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def answer(action, body, key=None, mock=False):
    if action not in ('check', 'solve'):
        return 404, {'status': 'error', 'message': 'Unknown action'}
    question = body.get('question', '').strip()
    if not question:
        return 400, {'status': 'error', 'message': 'Empty question'}
    if action == 'check' and not body.get('solution'):
        return 400, {'status': 'error', 'message': 'Empty solution'}
    if mock:
        return 200, {'status': 'mock', 'message': 'MOCK MODE: no mathematics was checked. Disable INKPROOF_MOCK for real results.'}
    if not key:
        return 503, {'status': 'not_configured', 'message': 'AI NOT CONFIGURED: set GEMINI_API_KEY on the backend.'}
    model = os.getenv('GEMINI_MODEL', 'gemini-2.5-flash')
    # Vector coordinates are not mathematically legible to a text LLM. Never pretend otherwise.
    if action == 'check':
        return 422, {'status': 'unclear', 'message': 'Handwritten solution recognition is not yet available. No assessment was made.'}
    prompt = 'Solve this math question step by step. State uncertainty explicitly. Question: ' + question
    payload = json.dumps({'contents': [{'parts': [{'text': prompt}]}]}).encode()
    req = Request('https://generativelanguage.googleapis.com/v1beta/models/' + model + ':generateContent',
                  data=payload, headers={'Content-Type': 'application/json', 'x-goog-api-key': key})
    try:
        with urlopen(req, timeout=30) as response:
            data = json.load(response)
        text = '\n'.join(p['text'] for candidate in data['candidates'] for p in candidate['content']['parts'] if 'text' in p)
        if not text:
            raise ValueError('Empty model response')
        return 200, {'status': 'generated', 'message': text}
    except HTTPError as exc:
        return 502, {'status': 'provider_error', 'message': 'Gemini request failed (HTTP %s). Check model, quota and key.' % exc.code}
    except (URLError, ValueError, KeyError) as exc:
        return 502, {'status': 'provider_error', 'message': 'Gemini unavailable or returned an invalid response.'}


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, data):
        raw = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path != '/health':
            return self.reply(404, {'status': 'error'})
        self.reply(200, {'status': 'mock' if os.getenv('INKPROOF_MOCK') == '1' else 'configured' if os.getenv('GEMINI_API_KEY') else 'not_configured'})

    def do_POST(self):
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if size > 2_000_000:
                return self.reply(413, {'status': 'error', 'message': 'Request too large'})
            body = json.loads(self.rfile.read(size))
            if not isinstance(body, dict):
                raise ValueError('Expected object')
            status, result = answer(self.path.lstrip('/'), body, os.getenv('GEMINI_API_KEY'), os.getenv('INKPROOF_MOCK') == '1')
            self.reply(status, result)
        except (ValueError, TypeError):
            self.reply(400, {'status': 'error', 'message': 'Invalid JSON request'})


if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
