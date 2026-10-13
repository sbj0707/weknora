"""Parser configuration KV operations against the packaged server and real Go tables."""
import json
import sqlite3


def run(database, request, key):
    path = '/api/v1/tenants/kv/parser-engine-config'
    with sqlite3.connect(database) as sql:
        saved = sql.execute('SELECT parser_engine_config,updated_at FROM tenants WHERE id=1').fetchone()
        foreign = sql.execute('SELECT parser_engine_config,updated_at FROM tenants WHERE id<>1 ORDER BY id').fetchall()
        saved_key = sql.execute('SELECT full_access,capabilities FROM tenant_api_keys WHERE api_key=?', (key,)).fetchone()
    try:
        denied = request('GET', path, 403, user='viewer')
        assert denied['error']['code'] == 1002
        assert request('PUT', path, 403, b'{', user='viewer')['error'] == 'Forbidden: insufficient workspace role'
        request('GET', path, 200)
        data = request('PUT', path, 200, {
            'mineru_api_key': 'isolated-cloud-secret',
            'mineru_server_api_key': 'isolated-server-secret',
            'paddleocr_vl_cloud_token': 'isolated-paddle-secret',
            'mineru_enable_table': False,
            'chat_parser_engine_rules': [{'file_types': ['pdf'], 'engine': 'mineru'}],
        })['data']
        for field in ('mineru_api_key', 'mineru_server_api_key', 'paddleocr_vl_cloud_token'):
            assert data[field] == '***'
        assert data['mineru_enable_table'] is False
        assert request('GET', path, 200)['data'] == data
        with sqlite3.connect(database) as sql:
            raw, kind = sql.execute('SELECT parser_engine_config,typeof(parser_engine_config) FROM tenants WHERE id=1').fetchone()
            assert kind == 'blob' and json.loads(raw)['mineru_api_key'] == 'isolated-cloud-secret'
            assert sql.execute('SELECT parser_engine_config,updated_at FROM tenants WHERE id<>1 ORDER BY id').fetchall() == foreign
        preserved = request('PUT', path, 200, {'mineru_api_key': '***'})['data']
        assert preserved['chat_parser_engine_rules'] == data['chat_parser_engine_rules']
        cleared = request('PUT', path, 200, {'chat_parser_engine_rules': []})['data']
        assert cleared.get('chat_parser_engine_rules', []) == []
        with sqlite3.connect(database) as sql:
            before = sql.execute('SELECT parser_engine_config,updated_at FROM tenants WHERE id=1').fetchone()
        invalid = request('PUT', path, 400, b'{"mineru_api_key":42}')
        assert invalid['error']['code'] == 1010 and 'cannot unmarshal number' in invalid['error']['details']
        assert request('PUT', path, 400, {'mineru_endpoint': 'file:///tmp/parser'})['error']['code'] == 1010
        with sqlite3.connect(database) as sql:
            assert sql.execute('SELECT parser_engine_config,updated_at FROM tenants WHERE id=1').fetchone() == before
            sql.execute('UPDATE tenant_api_keys SET full_access=false,capabilities=? WHERE api_key=?',
                        (json.dumps(['manage_tenant_settings']), key))
        request('GET', path, 200, user=None, api_key=True)
        request('PUT', path, 200, {'mineru_enable_table': False}, user=None, api_key=True)
        with sqlite3.connect(database) as sql:
            sql.execute('UPDATE tenant_api_keys SET capabilities=? WHERE api_key=?',
                        (json.dumps(['manage_vector_stores']), key))
        for method, body in (('GET', None), ('PUT', b'{')):
            assert request(method, path, 403, body, user=None, api_key=True)['error'] == 'Forbidden: API key scope does not allow this operation'
    finally:
        with sqlite3.connect(database) as sql:
            sql.execute('UPDATE tenants SET parser_engine_config=?,updated_at=? WHERE id=1', saved)
            sql.execute('UPDATE tenant_api_keys SET full_access=?,capabilities=? WHERE api_key=?', (*saved_key, key))
