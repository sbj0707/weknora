"""Supplement for scripts/smoke.py: call run(database, request, key) BEFORE viewer leaves.
Uses the original process smoke's real SQLite database, request function and machine key.
All assertions call the packaged server. No Mockito or Java in-process test shortcuts.
"""
import hashlib
import json
import sqlite3
from pathlib import Path
from smoke_content_read import run as check_content_reads


def run(database, request, key):
    kb = "shared-read-smoke-kb"
    agent = "shared-read-smoke-agent"
    path = "/api/v1/knowledge-bases/" + kb
    store = "5dd497ae-6835-4cb9-b9d7-2968a22425bf"
    with sqlite3.connect(database) as sql:
        sql.execute("INSERT INTO tenants(id,name,business) VALUES(2,'Shared read owner','isolated')")
        sql.execute("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('read-org','Read org',2,'unused')")
        sql.execute("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('read-source','read-org',2,'admin'),('read-target','read-org',1,'editor')")
        sql.execute("INSERT INTO knowledge_bases(id,name,tenant_id,creator_id,type,embedding_model_id,summary_model_id,vector_store_id,cos_config,vlm_config) VALUES(?,'Shared read',2,'foreign-creator','document','','',?,?,?)",
                    (kb, store, json.dumps({'secret_id': 'foreign-key-id', 'secret_key': 'foreign-secret'}), json.dumps({'api_key': 'foreign-vlm-secret'})))
        sql.execute("INSERT INTO vector_stores(id,name,engine_type,tenant_id) VALUES(?,'Foreign private store','qdrant',2)", (store,))
        sql.execute("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('read-share',?,'read-org',2,'editor','unused')", (kb,))
        for suffix, status in (('done', 'completed'), ('pending', 'pending'), ('processing', 'processing'), ('deleting', 'deleting'), ('deleted', 'pending')):
            doc = 'read-doc-' + suffix
            sql.execute("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status) VALUES(?,2,?,'file','Read document','test',?)", (doc, kb, status))
            if suffix == 'deleted':
                sql.execute("UPDATE knowledges SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", (doc,))
        sql.execute("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status) VALUES('read-wrong-tenant',1,?,'file','Wrong tenant','test','pending')", (kb,))

    def shared(response, permission='viewer'):
        data = response['data']
        assert data['tenant_id'] == 2 and data['my_permission'] == permission, data
        assert (data['knowledge_count'], data['processing_count'], data['is_processing']) == (3, 2, True), data
        assert data['vector_store_source'] == 'shared' and data['vector_store_status'] == 'available', data
        assert all(field not in data for field in ('vector_store_id', 'vector_store_name', 'vector_store_engine_type')), data
        assert data['storage_config']['secret_key'] == '' and data['storage_config']['secret_id'] == '', data
        assert data['vlm_config']['api_key'] == '', data

    shared(request('GET', path, 200, user='viewer'))
    shared(request('GET', path + '?agent_id=missing&agent_source_tenant_id=invalid', 200, user='viewer'))
    assert request('GET', '/api/v1/knowledge-bases/smoke-kb?agent_id=missing&agent_source_tenant_id=invalid', 200)['data']['tenant_id'] == 1
    shared(request('GET', path, 200, user=None, api_key=True), 'editor')
    check_content_reads(database, request, key, Path(__file__).resolve().parents[2])
    assert request('GET', '/api/v1/knowledge-bases/shared-read-no-such-kb', 404, user='viewer')['error']['code'] == 1003

    with sqlite3.connect(database) as sql:
        saved = sql.execute("SELECT full_access,capabilities,knowledge_base_ids FROM tenant_api_keys WHERE key_hash=?", (hashlib.sha256(key.encode()).hexdigest(),)).fetchone()
        assert saved is not None
        sql.execute("UPDATE tenant_api_keys SET full_access=false,capabilities='[\"retrieve\"]',knowledge_base_ids='[]' WHERE key_hash=?", (hashlib.sha256(key.encode()).hexdigest(),))
    try:
        # Scoped keys carry Go's legacy Viewer role; full-access keys above carry Owner.
        shared(request('GET', path, 200, user=None, api_key=True), 'viewer')
        with sqlite3.connect(database) as sql:
            sql.execute("UPDATE tenant_api_keys SET capabilities='[\"ingest\"]' WHERE key_hash=?", (hashlib.sha256(key.encode()).hexdigest(),))
        assert request('GET', path, 403, user=None, api_key=True) == {'error': 'Forbidden: API key scope does not allow this operation'}
        with sqlite3.connect(database) as sql:
            sql.execute("UPDATE tenant_api_keys SET capabilities='[\"retrieve\"]',knowledge_base_ids='[\"other\"]' WHERE key_hash=?", (hashlib.sha256(key.encode()).hexdigest(),))
        assert request('GET', path, 403, user=None, api_key=True) == {'success': False, 'error': {'code': 1002, 'message': 'API key scope does not allow one or more knowledge bases', 'details': None}}
    finally:
        with sqlite3.connect(database) as sql:
            sql.execute("UPDATE tenant_api_keys SET full_access=?,capabilities=?,knowledge_base_ids=? WHERE key_hash=?", (*saved, hashlib.sha256(key.encode()).hexdigest()))

    with sqlite3.connect(database) as sql:
        sql.execute("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='read-share'")
        sql.execute("INSERT INTO custom_agents(id,tenant_id,name,is_builtin,config) VALUES(?,2,'Shared agent',false,?)", (agent, json.dumps({'kb_selection_mode': 'all'}).encode()))
        assert sql.execute("SELECT typeof(config) FROM custom_agents WHERE id=?", (agent,)).fetchone() == ('blob',)
        sql.execute("INSERT INTO agent_shares(id,agent_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('read-agent-share',?,'read-org',2,'viewer','unused')", (agent,))
    shared(request('GET', path, 200, user='viewer'))  # implicit agent fallback
    shared(request('GET', path + '?agent_id=' + agent + '&agent_source_tenant_id=2', 200, user='viewer'))
    assert request('GET', path + '?agent_id=' + agent + '&agent_source_tenant_id=invalid', 400, user='viewer')['error']['code'] == 1000
    assert request('GET', path + '?agent_id=' + agent + '&agent_source_tenant_id=1', 403, user='viewer')['error']['code'] == 1002
    assert request('GET', path + '?agent_id=missing&agent_source_tenant_id=2', 403, user='viewer')['error']['code'] == 1002
    with sqlite3.connect(database) as sql:
        sql.execute("INSERT INTO tenant_disabled_shared_agents(tenant_id,agent_id,source_tenant_id) VALUES(1,?,2)", (agent,))
    shared(request('GET', path + '?agent_id=' + agent, 200, user='viewer'))
    with sqlite3.connect(database) as sql:
        sql.execute("UPDATE agent_shares SET deleted_at=CURRENT_TIMESTAMP WHERE id='read-agent-share'")
    assert request('GET', path, 403, user='viewer')['error']['code'] == 1002
