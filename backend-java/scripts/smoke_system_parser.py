"""Three system parser routes against the running jar and a self-cleaning HTTP reader.

Call before viewer leaves. smoke.py sets HTTP transport, an empty initial reader
address, and an explicit loopback SSRF whitelist for this isolated process.
"""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import sqlite3
import threading


def run(database, request, key):
    listing = '/api/v1/system/parser-engines'
    checking = listing + '/check'
    reconnect = '/api/v1/system/docreader/reconnect'
    key_hash = hashlib.sha256(key.encode()).hexdigest()
    calls = []
    mode = {'engines': None}

    class Reader(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def reply(self, status, value):
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
            calls.append(('POST', self.path, body))
            if self.path == '/list-engines':
                self.reply(200, {'engines': mode['engines']})
            else:
                self.reply(404, {})

        def do_GET(self):
            calls.append(('GET', self.path, self.headers.get('Authorization')))
            if self.path == '/v1/health':
                self.reply(200, {'status': 'ok'})
            elif self.path == '/v1/parse/jobs?limit=1':
                self.reply(200 if self.headers.get('Authorization') == 'Bearer saved-private' else 401, {})
            else:
                self.reply(404, {})

    with sqlite3.connect(database) as sql:
        saved_tenant = sql.execute('SELECT parser_engine_config,credentials,updated_at FROM tenants WHERE id=1').fetchone()
        saved_key = sql.execute('SELECT full_access,capabilities,knowledge_base_ids FROM tenant_api_keys WHERE key_hash=?', (key_hash,)).fetchone()
        saved_roles = dict(sql.execute("SELECT user_id,role FROM tenant_members WHERE tenant_id=1 AND user_id IN ('owner','viewer')"))
        # Go's scanner accepts []byte; SQLite TEXT and BLOB remain distinct.
        config = json.dumps({'mineru_server_api_key': 'saved-private', 'paddleocr_vl_cloud_token': 'saved-cloud'}).encode()
        sql.execute('UPDATE tenants SET parser_engine_config=? WHERE id=1', (config,))
        before = sql.execute('SELECT parser_engine_config,credentials,updated_at FROM tenants WHERE id=1').fetchone()

    def engine(response, name):
        return next(item for item in response['data'] if item['Name'] == name)

    def role(user, value):
        with sqlite3.connect(database) as sql:
            sql.execute('UPDATE tenant_members SET role=? WHERE tenant_id=1 AND user_id=?', (value, user))

    def scoped(capabilities):
        with sqlite3.connect(database) as sql:
            sql.execute("UPDATE tenant_api_keys SET full_access=false,capabilities=?,knowledge_base_ids='[]' WHERE key_hash=?", (json.dumps(capabilities), key_hash))

    def unchanged():
        with sqlite3.connect(database) as sql:
            assert sql.execute('SELECT parser_engine_config,credentials,updated_at FROM tenants WHERE id=1').fetchone() == before

    server = ThreadingHTTPServer(('127.0.0.1', 0), Reader)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    address = 'http://127.0.0.1:' + str(server.server_port)
    try:
        request('GET', listing, 401, user=None)  # 1
        initial = request('GET', listing, 200, user='viewer')  # 2
        assert initial['code'] == 0 and initial['connected'] is False, initial
        assert initial['docreader_addr'] == '' and initial['docreader_transport'] == 'http', initial
        assert len(initial['data']) == 8 and engine(initial, 'simple')['Available'] is True, initial
        assert engine(initial, 'builtin')['Available'] is False, initial
        forbidden = {'error': 'Forbidden: insufficient workspace role'}
        assert request('POST', checking, 403, b'{', user='viewer') == forbidden  # 3
        assert request('POST', reconnect, 403, b'{', user='viewer') == forbidden  # 4
        role('viewer', 'future-role')
        below_viewer = request('GET', listing, 403, user='viewer')  # 5
        # Existing Java identity validation rejects unknown roles before the
        # native route guard. Go forwards an active member's unknown role to
        # RequireRole, so this check records the known boundary difference.
        assert below_viewer == {'code': 'FORBIDDEN', 'error': 'Forbidden: not an active member of the target workspace'}, below_viewer
        role('viewer', 'contributor')
        assert request('POST', checking, 403, b'{', user='viewer') == forbidden  # 6
        assert request('POST', reconnect, 403, b'{', user='viewer') == forbidden  # 7
        role('viewer', saved_roles['viewer'])
        role('owner', 'admin')
        assert request('POST', checking, 200, {})['code'] == 0  # 8: native Admin floor
        role('owner', saved_roles['owner'])
        assert request('POST', checking, 400, b'{') == {'code': 1, 'msg': '请求体格式错误'}  # 9
        assert request('POST', reconnect, 400, {}) == {'code': 1, 'msg': '请提供 addr 参数'}  # 10
        assert request('POST', reconnect, 400, {'addr': '  '}) == {'code': 1, 'msg': 'addr 不能为空'}  # 11
        for target in ('file:///etc/passwd', 'http://169.254.169.254/latest/meta-data'):  # 12,13
            denied = request('POST', reconnect, 400, {'addr': target})
            assert denied['code'] == 1 and 'DocReader 地址 未通过安全校验' in denied['msg'], denied
        assert request('GET', listing, 200, user=None, api_key=True)['connected'] is False  # 14
        assert request('POST', checking, 200, {}, user=None, api_key=True)['code'] == 0  # 15
        connected = request('POST', reconnect, 200, {'addr': address}, user=None, api_key=True)  # 16: remote null
        assert connected['connected'] is True and connected['docreader_addr'] == address, connected
        assert connected['msg'] == '连接成功' and len(connected['data']) == 8, connected
        assert engine(connected, 'builtin')['Available'] is True, connected
        assert calls[-1] == ('POST', '/list-engines', {'config_overrides': {'mineru_server_api_key': 'saved-private', 'paddleocr_vl_cloud_token': 'saved-cloud'}}), calls
        mode['engines'] = []
        empty = request('GET', listing, 200, user='viewer')  # 17: remote []
        assert empty['connected'] is True and empty['docreader_addr'] == '' and len(empty['data']) == 8, empty
        mode['engines'] = [
            {'name': 'builtin', 'description': 'first', 'file_types': ['first'], 'available': False},
            {'name': 'builtin', 'description': 'last', 'file_types': ['remote-pdf'], 'available': False},
            {'name': 'simple', 'description': 'remote-simple', 'file_types': [], 'available': False},
            {'name': 'custom', 'description': 'nil-types', 'file_types': None, 'available': True},
            {'name': 'custom', 'description': 'empty-types', 'file_types': [], 'available': False}]
        duplicate = request('GET', listing, 200, user='viewer')  # 18
        builtin = engine(duplicate, 'builtin')
        assert builtin['Description'] == 'last' and builtin['FileTypes'] == ['remote-pdf'] and builtin['Available'] is True, builtin
        simple = engine(duplicate, 'simple')
        assert simple['Description'] == 'remote-simple' and 'txt' in simple['FileTypes'] and simple['Available'] is True, simple
        assert [(item['Description'], item['FileTypes'], item['Available']) for item in duplicate['data'] if item['Name'] == 'custom'] == [('nil-types', None, True), ('empty-types', [], False)], duplicate
        checked = request('POST', checking, 200, {'mineru_endpoint': address, 'mineru_server_api_key': '***', 'paddleocr_vl_cloud_token': 'unsaved-cloud'})  # 19
        assert engine(checked, 'mineru')['Available'] is True and engine(checked, 'paddleocr_vl_cloud')['Available'] is True, checked
        assert ('GET', '/v1/parse/jobs?limit=1', 'Bearer saved-private') in calls, calls
        assert any(item[0:2] == ('POST', '/list-engines') and item[2].get('config_overrides', {}).get('paddleocr_vl_cloud_token') == 'unsaved-cloud' for item in calls), calls
        unchanged()
        listed = request('GET', listing, 200)  # 20: check did not persist endpoint or token
        assert engine(listed, 'mineru')['Available'] is False, listed
        assert calls[-1][2]['config_overrides']['paddleocr_vl_cloud_token'] == 'saved-cloud', calls
        scoped(['retrieve'])
        scope_denied = {'error': 'Forbidden: API key scope does not allow this operation'}
        assert request('GET', listing, 403, user=None, api_key=True) == scope_denied  # 21
        assert request('POST', checking, 403, b'{', user=None, api_key=True) == scope_denied  # 22
        assert request('POST', reconnect, 403, b'{', user=None, api_key=True) == scope_denied  # 23
        scoped(['manage_vector_stores'])
        assert request('GET', listing, 200, user=None, api_key=True)['connected'] is True  # 24
        assert request('POST', checking, 200, {}, user=None, api_key=True)['code'] == 0  # 25
        assert request('POST', reconnect, 200, {'addr': address}, user=None, api_key=True)['connected'] is True  # 26
        unchanged()
        assert len(calls) == 10, calls  # Eight real reader discovery calls and two MinerU probes.
        with sqlite3.connect(database) as sql:
            audits = sql.execute("SELECT request_path,details FROM audit_logs WHERE actor_user_id='viewer' AND action='rbac.access_denied' AND request_path LIKE '/api/v1/system/%'").fetchall()
            assert {path: json.loads(details)['required_role'] for path, details in audits} == {checking: 'admin', reconnect: 'admin'}, audits
            assert len(audits) == 2, audits  # Same user/path denials deduplicate across role changes.
    finally:
        try:
            with sqlite3.connect(database) as sql:
                sql.execute('UPDATE tenants SET parser_engine_config=?,credentials=?,updated_at=? WHERE id=1', saved_tenant)
                sql.execute('UPDATE tenant_api_keys SET full_access=?,capabilities=?,knowledge_base_ids=? WHERE key_hash=?', (*saved_key, key_hash))
                for user, value in saved_roles.items():
                    sql.execute('UPDATE tenant_members SET role=? WHERE tenant_id=1 AND user_id=?', (value, user))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
            assert not thread.is_alive(), 'isolated HTTP reader did not stop'
