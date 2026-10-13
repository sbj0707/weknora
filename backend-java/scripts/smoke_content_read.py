"""Supplement for packaged smoke: run(database, request, key, root) inside kb-read supplement while read-share is active.
root is the repository directory; request is scripts/smoke.py's real HTTP helper.
All rows use production Go SQLite schemas, and all assertions hit the running jar.
"""
import hashlib
import json
from pathlib import Path
import sqlite3


def run(database, request, key, root):
    root = Path(root)
    kb = 'shared-read-smoke-kb'
    document = 'read-doc-done'
    chunk = 'content-read-chunk'
    path = '/api/v1/knowledge-bases/' + kb + '/knowledge'
    with sqlite3.connect(database) as sql:
        columns = lambda table: {row[1] for row in sql.execute('PRAGMA table_info(' + table + ')')}
        tables = {row[0] for row in sql.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for required, table, script in (
                ('folder_path', 'knowledges', '000002_knowledge_folder_path'),
                ('pending_subtasks_count', 'knowledges', '000008_processing_spans_and_pending_subtasks'),
                ('source_locators', 'chunks', '000033_chunk_source_locators')):
            if required not in columns(table):
                sql.executescript((root / 'migrations/sqlite' / (script + '.up.sql')).read_text())
        if 'knowledge_tag_relations' not in tables:
            sql.executescript((root / 'migrations/sqlite/000010_knowledge_multi_tags.up.sql').read_text())
        assert sql.execute("SELECT tenant_id FROM knowledge_bases WHERE id=?",(kb,)).fetchone()==(2,)
        assert sql.execute("SELECT deleted_at FROM kb_shares WHERE id='read-share'").fetchone()==(None,)
        docs = [('done', 'completed', 'docs', 'alpha.pdf', 'Alpha 100%'),
                ('pending', 'pending', 'docs/spec', 'beta.md', 'Beta_under'),
                ('processing', 'processing', '', '', 'Manual'),
                ('deleting', 'deleting', 'docs', 'gone.pdf', 'Gone'),
                ('deleted', 'pending', '', 'deleted.pdf', 'Deleted')]
        for index, (suffix, status, folder, filename, title) in enumerate(docs):
            identifier = 'read-doc-' + suffix
            created = '2026-10-0' + str(index + 1) + ' 00:00:00+00:00'
            sql.execute("UPDATE knowledges SET title=?,file_name=?,file_type='pdf',folder_path=?,parse_status=?,created_at=?,updated_at=? WHERE id=? AND tenant_id=2 AND knowledge_base_id=?",(title,filename,folder,status,created,created,identifier,kb))
            if suffix == 'deleted':
                sql.execute('UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id=?', (identifier,))
        sql.execute("UPDATE knowledges SET profile=? WHERE id=?", (json.dumps({'gist': 'Summary', 'unknown': 'PRIVATE'}), document))
        sql.execute("INSERT INTO knowledge_tags(id,seq_id,tenant_id,knowledge_base_id,name,color,sort_order) VALUES('content-read-tag',101,2,?,'Source tag','blue',0)", (kb,))
        sql.execute("INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES(?,'content-read-tag')", (document,))
        for index, (identifier, kind, status, enabled) in enumerate((
                (chunk, 'text', 0, True), ('content-read-chunk-indexed', 'text', 2, True),
                ('content-read-chunk-disabled', 'text', 0, False),
                ('content-read-chunk-image', 'image_caption', 0, True),
                ('content-read-chunk-pending', 'text', 1, True))):
            sql.execute("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,source_content,context_header,chunk_type,chunk_index,status,is_enabled,start_at,end_at) VALUES(?,2,?,?,?,'PRIVATE source','PRIVATE header',?,?,?,?,0,4)",
                        (identifier, kb, document, 'Text ' + identifier, kind, index, status, enabled))
        sql.execute('UPDATE chunks SET source_locators=? WHERE id=?', (json.dumps([{'type': 'pdf', 'page': 2, 'quote': 'Text', 'unknown': 'PRIVATE'}]), chunk))
        sql.execute("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_type,chunk_index,start_at,end_at) VALUES('content-read-foreign-chunk',1,?,?,'Wrong owner','text',8,0,4)", (kb, document))

    result = request('GET', path, 200, user='viewer')
    assert result['total'] == 3 and result['page_size'] == 20, result
    assert all(row['tenant_id'] == 2 for row in result['data']), result
    assert [row['id'] for row in result['data']] == ['read-doc-processing', 'read-doc-pending', document], result
    assert request('GET', path + '?page=2&page_size=2', 200, user='viewer')['data'][0]['id'] == document
    assert request('GET', path + '?keyword=ALPHA', 200, user='viewer')['total'] == 1
    assert request('GET', path + '?keyword=%25', 200, user='viewer')['total'] == 1
    assert request('GET', path + '?tag_ids=content-read-tag', 200, user='viewer')['data'][0]['tags'][0]['name'] == 'Source tag'
    assert request('GET', path + '?folder_path=', 200, user='viewer')['total'] == 1
    assert request('GET', path + '?folder_path=docs&folder_recursive=true', 200, user='viewer')['total'] == 2
    assert request('GET', path + '?parse_status=deleting', 200, user='viewer')['total'] == 1
    assert request('GET', path + '?sort_by=id%20desc', 400, user='viewer')['error']['code'] == 1000
    assert request('GET', path + '?page=-1', 400, user='viewer')['error']['code'] == 1000
    detail = request('GET', '/api/v1/knowledge/' + document, 200, user='viewer')['data']
    assert detail['tenant_id'] == 2 and detail['profile'] == {'gist': 'Summary'}, detail
    assert detail['tags'][0]['id'] == 'content-read-tag', detail
    chunks = request('GET', '/api/v1/chunks/' + document, 200, user='viewer')
    assert chunks['total'] == 3 and chunks['page_size'] == 10, chunks
    assert chunks['data'][2]['is_enabled'] is False, chunks
    assert all(row['tenant_id'] == 2 and 'source_content' not in row and 'context_header' not in row for row in chunks['data']), chunks
    one = request('GET', '/api/v1/chunks/by-id/' + chunk, 200, user='viewer')['data']
    assert one['source_locators'] == [{'type': 'pdf', 'page': 2, 'quote': 'Text'}], one
    assert one['start_at'] == 0 and one['end_at'] == 4 and 'source_content' not in one, one
    assert request('GET', '/api/v1/chunks/' + document + '?chunk_type=image_caption', 200, user='viewer')['total'] == 1
    assert request('GET', '/api/v1/chunks/' + document + '?chunk_type=text&chunk_type=image_caption', 200, user='viewer')['total'] == 4
    assert request('GET', '/api/v1/chunks/' + document + '?page_size=101', 200, user='viewer')['page_size'] == 100
    assert request('GET', '/api/v1/knowledge/content-read-missing', 404, user='viewer')['error']['code'] == 1003
    assert request('GET', '/api/v1/chunks/by-id/content-read-missing', 404, user='viewer')['error']['code'] == 1003
    # The shared grant and deterministic document dates are still active.
    check_read_edges(database, request)

    with sqlite3.connect(database) as sql:
        saved = sql.execute('SELECT full_access,capabilities,knowledge_base_ids FROM tenant_api_keys WHERE key_hash=?', (hashlib.sha256(key.encode()).hexdigest(),)).fetchone()
        sql.execute("UPDATE tenant_api_keys SET full_access=false,capabilities='[\"retrieve\"]',knowledge_base_ids='[]' WHERE key_hash=?", (hashlib.sha256(key.encode()).hexdigest(),))
    try:
        assert request('GET', path, 200, user=None, api_key=True)['total'] == 3
        with sqlite3.connect(database) as sql:
            sql.execute("UPDATE tenant_api_keys SET capabilities='[\"ingest\"]' WHERE key_hash=?", (hashlib.sha256(key.encode()).hexdigest(),))
        assert request('GET', '/api/v1/knowledge/' + document, 403, user=None, api_key=True) == {'error': 'Forbidden: API key scope does not allow this operation'}
        with sqlite3.connect(database) as sql:
            sql.execute("UPDATE tenant_api_keys SET capabilities='[\"retrieve\"]',knowledge_base_ids='[\"other\"]' WHERE key_hash=?", (hashlib.sha256(key.encode()).hexdigest(),))
        assert request('GET', '/api/v1/chunks/by-id/' + chunk, 403, user=None, api_key=True)['error']['code'] == 1002
    finally:
        with sqlite3.connect(database) as sql:
            sql.execute('UPDATE tenant_api_keys SET full_access=?,capabilities=?,knowledge_base_ids=? WHERE key_hash=?', (*saved, hashlib.sha256(key.encode()).hexdigest()))
    with sqlite3.connect(database) as sql:
        sql.execute("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='read-share'")
    assert request('GET', path, 403, user='viewer')['error']['code'] == 1002
    with sqlite3.connect(database) as sql:
        sql.execute("INSERT INTO custom_agents(id,tenant_id,name,config) VALUES('content-read-agent',2,'Read agent',?)", (json.dumps({'kb_selection_mode': 'selected', 'knowledge_bases': [kb]}),))
        sql.execute("INSERT INTO agent_shares(id,agent_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('content-read-agent-share','content-read-agent','read-org',2,'viewer','unused')")
    assert request('GET', '/api/v1/knowledge/' + document + '?agent_id=content-read-agent&agent_source_tenant_id=2', 200, user='viewer')['data']['tenant_id'] == 2
    assert request('GET', '/api/v1/chunks/' + document + '?agent_id=content-read-agent&agent_source_tenant_id=invalid', 400, user='viewer')['error']['code'] == 1000
    assert request('GET', '/api/v1/chunks/by-id/' + chunk + '?agent_id=missing&agent_source_tenant_id=2', 403, user='viewer')['error']['code'] == 1002

    # Restore the original KB-read smoke state and clean supplemental chunks for its final zero-count assertion.
    with sqlite3.connect(database) as sql:
        sql.execute("UPDATE kb_shares SET deleted_at=NULL WHERE id='read-share'")
        sql.execute("DELETE FROM agent_shares WHERE id='content-read-agent-share'")
        sql.execute("DELETE FROM custom_agents WHERE id='content-read-agent'")
        sql.execute("DELETE FROM chunks WHERE id LIKE 'content-read-%'")
        sql.execute("DELETE FROM knowledge_tag_relations WHERE tag_id='content-read-tag'")
        sql.execute("DELETE FROM knowledge_tags WHERE id='content-read-tag'")


def check_read_edges(database, request):
    path = '/api/v1/knowledge-bases/shared-read-smoke-kb/knowledge'
    detail = '/api/v1/knowledge/read-doc-done'
    assert request('GET', path + '?start_time=2026-10-02T0%3A00%3A00Z', 200, user='viewer')['total'] == 2
    assert request('GET', path + '?start_time=2026-10-02T00%3A00%3A00%2C123456789987Z', 200, user='viewer')['total'] == 1
    assert request('GET', path + '?start_time=2026-10-02T00%3A00%3A00%2B24%3A60', 200, user='viewer')['total'] == 3
    assert request('GET', path + '?start_time=2026-10-02+00%3A00%3A00', 200, user='viewer')['total'] == 2
    for invalid in ['2026-10-02t00%3A00%3A00z','2026-10-02T00%3A00Z','2026-10-02T00%3A00%3A00.Z']:
        assert request('GET', path + '?start_time=' + invalid, 400, user='viewer')['error']['code'] == 1000
    with sqlite3.connect(database) as sql:
        saved = sql.execute("SELECT metadata FROM knowledges WHERE id='read-doc-done'").fetchone()[0]
        sql.execute("UPDATE knowledges SET metadata=? WHERE id='read-doc-done'", ('{"counter":9007199254740993}',))
    try:
        assert request('GET', detail, 200, user='viewer')['data']['metadata']['counter'] == 9007199254740993
        with sqlite3.connect(database) as sql:
            sql.execute("UPDATE knowledges SET metadata='{} {}' WHERE id='read-doc-done'")
        assert request('GET', path, 500, user='viewer')['error']['code'] == 1007
        denied = request('GET', detail, 500, user='viewer')
        assert denied == {'success': False, 'error': {'code': 1007, 'message': 'Internal server error'}}, denied
        assert request('GET', '/api/v1/chunks/read-doc-done', 500, user='viewer') == denied
    finally:
        with sqlite3.connect(database) as sql:
            sql.execute("UPDATE knowledges SET metadata=? WHERE id='read-doc-done'", (saved,))
