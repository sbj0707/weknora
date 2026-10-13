#!/usr/bin/env python3
"""Verify native OIDC in an actual packaged process with Go schema and local providers."""
import argparse
import base64
import contextlib
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, target):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error('executable jar does not exist')
    root = Path(__file__).resolve().parents[2]
    folder = Path(tempfile.mkdtemp(prefix='weknora-java-oidc-smoke-'))
    database = folder / 'test.db'
    secret = secrets.token_urlsafe(32)
    client_secret = secrets.token_urlsafe(24)
    checks, provider_calls, bridge_calls = [], [], []
    identity = {'name': 'Existing', 'email': 'existing@test.invalid'}
    opener = urllib.request.build_opener(NoRedirect())

    class Provider(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def answer(self, code, payload):
            data = json.dumps(payload).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
            provider_calls.append(('POST', self.path))
            if self.path != '/token':
                self.answer(404, {})
                return
            fields = urllib.parse.parse_qs(raw.decode())
            assert fields['client_id'] == ['smoke-client']
            assert fields['client_secret'] == [client_secret]
            assert fields['grant_type'] == ['authorization_code']
            assert fields['code'] == ['smoke-code']
            self.answer(200, {'access_token': 'provider-only-token', 'token_type': 'Bearer'})

        def do_GET(self):
            provider_calls.append(('GET', self.path))
            if self.path == '/userinfo':
                assert self.headers.get('Authorization') == 'Bearer provider-only-token'
                self.answer(200, identity.copy())
            else:
                self.answer(404, {})

    class Bridge(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_POST(self):
            bridge_calls.append(('POST', self.path))
            self.rfile.read(int(self.headers.get('Content-Length', 0)))
            data = b'{"go_transport_probe":true}'
            self.send_response(418)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    provider = ThreadingHTTPServer(('127.0.0.1', 0), Provider)
    bridge = ThreadingHTTPServer(('127.0.0.1', 0), Bridge)
    for server in (provider, bridge):
        threading.Thread(target=server.serve_forever, daemon=True).start()
    origin = 'http://127.0.0.1:' + str(provider.server_port)
    with sqlite3.connect(database) as sql:
        for name in ('000000_init', '000007_system_admin_and_settings', '000011_principal_model', '000004_memory'):
            sql.executescript((root / 'migrations/sqlite' / (name + '.up.sql')).read_text())
        sql.execute("INSERT INTO tenants(id,name,business,retriever_engines) VALUES(1,'Existing','isolated',?)",
                    (json.dumps({'engines': []}).encode(),))
        sql.execute("INSERT INTO users(id,username,email,password_hash,tenant_id,preferences) VALUES('existing','Existing','existing@test.invalid','unused',1,?)",
                    (json.dumps({'last_active_tenant_id': 1}).encode(),))
        sql.execute("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('existing',1,'owner','active')")

    @contextlib.contextmanager
    def start(name, enabled, use_bridge=False):
        config = folder / (name + '.yaml')
        config.write_text(json.dumps({'oidc_auth': {
            'enable': enabled, 'provider_display_name': 'Smoke OIDC',
            'client_id': 'smoke-client', 'client_secret': client_secret,
            'authorization_endpoint': origin + '/authorize', 'token_endpoint': origin + '/token',
            'user_info_endpoint': origin + '/userinfo', 'scopes': ['openid', 'email'],
            'user_info_mapping': {'username': 'name', 'email': 'email'},
        }}))
        env = os.environ.copy()
        # Isolate this fixture from the user's provider and registration settings.
        for key in list(env):
            if key.startswith(('OIDC_AUTH_', 'OIDC_USER_INFO_MAPPING_')):
                del env[key]
        env.update(JAVA_GO_CONFIG=str(config), JAVA_DB_URL='jdbc:sqlite:' + str(database),
                   JWT_SECRET=secret, JAVA_DB_POOL_SIZE='1', WEKNORA_TASKS_ENABLED='false',
                   JAVA_GO_BRIDGE_ENABLED=str(use_bridge).lower(), JAVA_NATIVE_ROUTES_ENABLED='true',
                   JAVA_GO_BRIDGE_URL='http://127.0.0.1:' + str(bridge.server_port),
                   DOCREADER_ADDR='', DOCREADER_TRANSPORT='http',
                   SSRF_WHITELIST='127.0.0.1', SSRF_DNS_WHITELIST_ONLY='true',
                   STORAGE_TYPE='local', LOCAL_STORAGE_BASE_DIR=str(folder / 'files'),
                   WEKNORA_AUTH_DEFAULT_TENANT_MODE='create_personal')
        log = folder / (name + '.log')
        with log.open('wb') as output:
            process = subprocess.Popen([
                'java', '-jar', str(jar), '--server.address=127.0.0.1', '--server.port=0',
                '--spring.datasource.driver-class-name=org.sqlite.JDBC', '--debug=false',
                '--logging.level.root=WARN',
                '--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO',
            ], env=env, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 45
                while True:
                    match = re.search(r'Tomcat started on port (\d+)', log.read_text(errors='replace'))
                    if match:
                        yield 'http://127.0.0.1:' + match.group(1)
                        break
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise AssertionError('OIDC process startup failed; see ' + str(log))
                    time.sleep(0.1)
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)

    def request(base, path, status, method='GET', cookie=None, token=None):
        headers = {}
        if cookie:
            headers['Cookie'] = cookie
        if token:
            headers['Authorization'] = 'Bearer ' + token
        req = urllib.request.Request(base + path, headers=headers, method=method)
        try:
            with opener.open(req, timeout=15) as response:
                code, response_headers, data = response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            code, response_headers, data = error.code, error.headers, error.read()
        assert code == status, (method, path.split('?')[0], status, code, data)
        checks.append((method, path.split('?')[0], code))
        return response_headers, json.loads(data) if response_headers.get_content_type() == 'application/json' else data

    def callback(base, state, cookie):
        path = '/api/v1/auth/oidc/callback?' + urllib.parse.urlencode({'state': state, 'code': 'smoke-code'})
        headers, _ = request(base, path, 302, cookie=cookie)
        assert 'Max-Age=0' in headers['Set-Cookie']
        location = headers['Location']
        assert location.startswith('/#oidc_result='), location
        encoded = urllib.parse.unquote(location.split('=', 1)[1])
        result = json.loads(base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)))
        assert result['success'] and len(result['memberships']) == 1
        for field, kind in (('token', 'access_token'), ('refresh_token', 'refresh_token')):
            token = result[field]
            parts = token.split('.')
            expected = base64.urlsafe_b64encode(hmac.new(secret.encode(), (parts[0] + '.' + parts[1]).encode(), hashlib.sha256).digest()).rstrip(b'=').decode()
            assert hmac.compare_digest(expected, parts[2])
            with sqlite3.connect(database) as sql:
                assert sql.execute('SELECT token_type,is_revoked FROM auth_tokens WHERE token=?', (token,)).fetchone() == (kind, 0)
        _, me = request(base, '/api/v1/auth/me', 200, token=result['token'])
        assert me['data']['user']['id'] == result['user']['id']
        return result

    try:
        with start('disabled', False) as base:
            _, body = request(base, '/api/v1/auth/oidc/config', 200)
            assert body == {'success': True, 'enabled': False, 'provider_display_name': 'Smoke OIDC'}
            _, body = request(base, '/api/v1/auth/oidc/url', 400)
            assert body['error']['message'] == 'redirect_uri is required'
            _, body = request(base, '/api/v1/auth/oidc/url?redirect_uri=https%3A%2F%2Fapp.invalid%2Fcallback', 403)
            assert body['error']['details'] == 'OIDC login is disabled'
            request(base, '/api/v1/auth/oidc/start', 403)
            headers, _ = request(base, '/api/v1/auth/oidc/callback', 302)
            assert headers['Location'] == '/#oidc_error=invalid_state'
            for route in ('config', 'url', 'start', 'callback'):
                request(base, '/api/v1/auth/oidc/' + route, 401, method='POST')
            assert not provider_calls

        with start('enabled', True) as base:
            assert request(base, '/api/v1/auth/oidc/config', 200)[1]['enabled']
            headers, _ = request(base, '/api/v1/auth/oidc/start', 302)
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(headers['Location']).query)
            assert query['redirect_uri'] == [base + '/api/v1/auth/oidc/callback']
            assert 'HttpOnly' in headers['Set-Cookie'] and 'SameSite=Lax' in headers['Set-Cookie']
            for fresh in (False, True):
                if fresh:
                    identity.update(name='New OIDC', email='new-oidc@test.invalid')
                redirect = 'https://app.invalid/callback?x=<>&language=中文'
                headers, auth = request(base, '/api/v1/auth/oidc/url?' + urllib.parse.urlencode({'redirect_uri': redirect}), 200)
                params = urllib.parse.parse_qs(urllib.parse.urlsplit(auth['authorization_url']).query)
                assert params['redirect_uri'] == [redirect] and params['scope'] == ['openid email']
                assert params['state'] == [auth['state']]
                cookie = headers['Set-Cookie'].split(';', 1)[0]
                before = len(provider_calls)
                result = callback(base, auth['state'], cookie)
                assert bool(result.get('is_new_user')) == fresh
                assert result['user']['email'] == identity['email']
                assert provider_calls[before:] == [('POST', '/token'), ('GET', '/userinfo')]
                if fresh:
                    tenant = result['tenant']['id']
                    with sqlite3.connect(database) as sql:
                        assert sql.execute('SELECT role,status FROM tenant_members WHERE user_id=? AND tenant_id=?', (result['user']['id'], tenant)).fetchone() == ('owner', 'active')
                        assert sql.execute('SELECT count(*) FROM storage_backends WHERE tenant_id=? AND provider=? AND deleted_at IS NULL', (tenant, 'local')).fetchone() == (1,)
                        preferences = sql.execute('SELECT preferences FROM users WHERE id=?', (result['user']['id'],)).fetchone()[0]
                        assert json.loads(preferences)['oidc_only_login'] is True
                before = len(provider_calls)
                headers, _ = request(base, '/api/v1/auth/oidc/callback?' + urllib.parse.urlencode({'state': auth['state'], 'code': 'smoke-code'}), 302)
                assert headers['Location'] == '/#oidc_error=invalid_state' and len(provider_calls) == before
                # Actual Go accepts manual replay with the captured valid cookie/state.
                replay = callback(base, auth['state'], cookie)
                assert replay['user']['id'] == result['user']['id'] and replay['token'] != result['token']
            headers, _ = request(base, '/api/v1/auth/oidc/callback?error=denied&error_description=a%26b', 302)
            assert headers['Location'] == '/#oidc_error=denied&oidc_error_description=a%26b'

        with start('bridge', False, True) as base:
            assert request(base, '/api/v1/auth/oidc/config', 200)[1]['enabled'] is False
            assert not bridge_calls
            headers, body = request(base, '/api/v1/auth/oidc/config', 418, method='POST')
            assert body == {'go_transport_probe': True}
            assert headers['X-WeKnora-Implementation'] == 'go-compatibility-bridge'
            assert bridge_calls == [('POST', '/api/v1/auth/oidc/config')]
        print(json.dumps({'checks': len(checks), 'provider_http': len(provider_calls), 'bridge_http': len(bridge_calls),
                          'status': 'passed', 'database': str(database), 'logs': str(folder)}, indent=2))
    finally:
        for server in (provider, bridge):
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    main()
