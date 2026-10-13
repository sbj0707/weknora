#!/usr/bin/env python3
"""Exercise opt-in production upload/embedding/index/postprocess beans with a one-connection pool."""
import argparse
import base64
import hashlib
import hmac
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
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    parser.add_argument('vec', type=Path)
    parser.add_argument('--topology', choices=('single', 'go'), default='go')
    parser.add_argument('--office', action='store_true', help='Also upload a Go custom-format XLSX through a controlled HTTP reader')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root, jar, vec = args.root.resolve(), args.jar.resolve(), args.vec.resolve()
    if not jar.is_file() or not vec.is_file():
        parser.error('jar and explicitly compiled sqlite-vec v0.1.6 library must exist')
    folder = Path(tempfile.mkdtemp(prefix='weknora-java-processing-smoke-'))
    database, log = folder/'test.db', folder/'server.log'
    secret = secrets.token_urlsafe(32)
    tokens, calls, reader_calls = {}, [], []
    office = next((f for f in json.loads((root/'backend-java/src/test/resources/go-office-attachment.json').read_text())
                  if f['name']=='xlsx-custom-format' and not f['provided']), None) if args.office else None

    class EmbeddingHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if self.path == '/read' and office:
                assert request['file_type'] == 'xlsx' and base64.b64decode(request['file_content']) == base64.b64decode(office['data']), request
                reader_calls.append(request)
                result = {'markdown_content': office['parsed'], 'image_refs': [], 'source_blocks': [], 'metadata': {}, 'error': ''}
            else:
                assert self.path == '/v1/embeddings', self.path
                calls.append(request)
                result = {'data': [{'index': i, 'embedding': [1, 0, 0]} for i in reversed(range(len(request['input'])))]}
            data = json.dumps(result).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        def log_message(self, *_):
            pass

    model = ThreadingHTTPServer(('127.0.0.1', 0), EmbeddingHandler)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    with sqlite3.connect(database) as sql:
        for name in ('000000_init', '000002_knowledge_folder_path',
                     '000003_knowledge_base_auto_tag_config',
                     '000005_messages_attachments_and_invitation_fields',
                     '000007_system_admin_and_settings',
                     '000008_processing_spans_and_pending_subtasks', '000010_knowledge_multi_tags',
                     '000011_principal_model', '000021_knowledge_profiles',
                     '000031_model_catalog_config', '000033_chunk_source_locators'):
            sql.executescript((root/'migrations/sqlite'/(name+'.up.sql')).read_text())
        sql.executescript((root/'backend-java/migration/sql/java-tasks.sql').read_text())
        sql.execute("INSERT INTO tenants(id,name,business,storage_engine_config) VALUES(1,'Owner','isolated','{}'),(2,'Foreign','isolated','{}')")
        for user, tenant, role in (('owner', 1, 'owner'), ('foreign', 2, 'contributor')):
            sql.execute("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)", (user,user,user+'@test.invalid',tenant))
            sql.execute("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')", (user,tenant,role))
            encode = lambda value: base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=')
            payload = encode({'alg':'HS256','typ':'JWT'})+b'.'+encode({'user_id':user,'tenant_id':tenant,'type':'access','exp':int(time.time())+3600})
            token = (payload+b'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),payload,hashlib.sha256).digest()).rstrip(b'=')).decode()
            tokens[user] = token
            sql.execute("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)", (str(uuid.uuid4()),user,token))
        strategy = json.dumps({'vector_enabled':True,'keyword_enabled':True,'graph_enabled':False,'wiki_enabled':False})
        chunks = json.dumps({'chunk_size':64,'chunk_overlap':10,'separators':['\n\n','\n'],'enable_parent_child':False})
        sql.execute("INSERT INTO knowledge_bases(id,tenant_id,name,type,creator_id,embedding_model_id,summary_model_id,indexing_strategy,chunking_config) VALUES('kb',1,'Smoke','document','owner','embedding','summary',?,?)", (strategy,chunks))
        params = json.dumps({'provider':'generic','base_url':f'http://127.0.0.1:{model.server_port}/v1','embedding_parameters':{'dimension':3}})
        sql.execute("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES('embedding',1,'unknown','Embedding','remote',?,false,'active')", (params.encode(),))
        assert sql.execute("SELECT typeof(parameters) FROM models WHERE id='embedding'").fetchone() == ('blob',)
        sql.execute("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'owner')")
        sql.execute("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('caller','org',2,'editor')")
        sql.execute("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,'editor','owner')")
    env = os.environ.copy()
    env.update(JAVA_DB_URL='jdbc:sqlite:'+str(database),JWT_SECRET=secret,
               JAVA_DB_POOL_SIZE='1',JAVA_GO_BRIDGE_ENABLED='false',RETRIEVE_DRIVER='sqlite',
               LOCAL_STORAGE_BASE_DIR=str(folder/'files'),MAX_FILE_SIZE_MB='1',
               SSRF_WHITELIST='127.0.0.1',SSRF_DNS_WHITELIST_ONLY='true',
               WEKNORA_SQLITE_VEC_EXTENSION=str(vec))
    if office:
        env.update(DOCREADER_TRANSPORT='http', DOCREADER_ADDR=f'http://127.0.0.1:{model.server_port}')
    for pool in ('CORE', 'POSTPROCESS', 'ENRICHMENT', 'MAINTENANCE', 'SHARED'):
        env['WEKNORA_ASYNQ_'+pool+'_CONCURRENCY'] = '1'
    env['WEKNORA_WIKI_ASYNQ_CONCURRENCY'] = '1'
    process = None
    try:
        with log.open('wb') as output:
            process = subprocess.Popen(['java','-jar',str(jar),'--server.address=127.0.0.1','--server.port=0',
                '--spring.datasource.driver-class-name=org.sqlite.JDBC',
                '--spring.datasource.hikari.connection-timeout=2000',
                '--weknora.tasks.enabled=true','--weknora.tasks.topology='+args.topology,'--weknora.knowledge.processing.enabled=true',
                '--weknora.knowledge.upload.enabled=true','--weknora.tasks.concurrency=1',
                '--weknora.tasks.poll-millis=100','--weknora.tasks.lease-millis=5000',
                '--weknora.tasks.queues=default=1,postprocess=1','--logging.level.root=WARN','--debug=false',
                '--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO'],env=env,stdout=output,stderr=subprocess.STDOUT)
        deadline = time.monotonic()+45
        while True:
            match = re.search(r'Tomcat started on port (\d+)',log.read_text(errors='replace'))
            if match:
                base = 'http://127.0.0.1:'+match.group(1)
                break
            if process.poll() is not None or time.monotonic()>deadline:
                raise AssertionError('production bean startup failed: '+str(log))
            time.sleep(.1)
        # Tomcat can bind before the lazily initialized database pool is ready.
        # Probe authenticated reads before any mutation; never retry upload POSTs.
        ready_deadline = time.monotonic()+30
        readiness_retries = 0
        while True:
            ready = urllib.request.Request(base+'/api/v1/auth/me',
                     headers={'Authorization':'Bearer '+tokens['owner']})
            try:
                with urllib.request.urlopen(ready,timeout=5) as response:
                    identity=json.load(response)
                    assert response.status==200 and identity['data']['user']['id']=='owner',identity
                    break
            except urllib.error.HTTPError as error:
                if error.code!=503 or time.monotonic()>ready_deadline:
                    raise AssertionError(('authenticated database readiness',error.code,error.read().decode(),str(log))) from error
                error.close()
                readiness_retries+=1
                time.sleep(.1)
        ids = []
        uploads = [('owner', 'txt', b'First paragraph\r\n\r\nSecond paragraph'), ('foreign', 'txt', b'Foreign caller body')]
        if office:
            uploads.append(('foreign', 'xlsx', base64.b64decode(office['data'])))
        expected_uploads = len(uploads)
        expected_jobs = 2*expected_uploads + int(bool(office))
        for actor, suffix, content in uploads:
            body = ('--boundary\r\nContent-Disposition: form-data; name="process_config"\r\n\r\n{"summary_enabled":false}\r\n'
                    '--boundary\r\nContent-Disposition: form-data; name="fileName"\r\n\r\nfolder/'+actor+'.'+suffix+'\r\n'
                    '--boundary\r\nContent-Disposition: form-data; name="file"; filename="'+actor+'.'+suffix+'"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + content + b'\r\n--boundary--\r\n'
            request = urllib.request.Request(base+'/api/v1/knowledge-bases/kb/knowledge/file',data=body,
                      headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'multipart/form-data; boundary=boundary','Accept-Language':'en-US'},method='POST')
            try:
                with urllib.request.urlopen(request,timeout=10) as response:
                    result=json.load(response)
                    assert response.status==200 and result['success'],result
            except urllib.error.HTTPError as error:
                raise AssertionError((actor,error.code,error.read().decode(),str(log))) from error
            ids.append((actor,result['data']['id'],suffix))
        deadline=time.monotonic()+20
        while True:
            with sqlite3.connect(database,timeout=2) as sql:
                status=sql.execute('SELECT id,parse_status,error_message FROM knowledges ORDER BY id').fetchall()
                jobs=sql.execute('SELECT type,status,last_error FROM java_task_jobs ORDER BY created_at').fetchall()
            if len(status)==expected_uploads and all(row[1]=='completed' for row in status) and len(jobs)==expected_jobs and all(row[1]=='succeeded' for row in jobs):
                break
            if any(row[1]=='failed' for row in status) or any(row[1]=='dead' for row in jobs) or time.monotonic()>deadline:
                raise AssertionError({'knowledges':status,'jobs':jobs,'log':str(log)})
            time.sleep(.1)
        with sqlite3.connect(database,timeout=2) as sql:
            sql.enable_load_extension(True)
            sql.load_extension(str(vec))
            sql.enable_load_extension(False)
            assert sql.execute('SELECT vec_version()').fetchone()==('v0.1.6',)
            for actor,id,suffix in ids:
                row=sql.execute('SELECT tenant_id,enable_status,folder_path,file_path,storage_size FROM knowledges WHERE id=?',(id,)).fetchone()
                assert row[0:3]==(1,'enabled','folder'),row
                assert row[3].startswith('resource://'),row
                assert row[4]>0,row
                chunks=sql.execute("SELECT content,source_locators FROM chunks WHERE knowledge_id=? AND chunk_type NOT IN ('table_summary','table_column')",(id,)).fetchall()
                assert chunks and all(json.loads(chunk[1])[0]['source_hash'] for chunk in chunks),chunks
                if suffix == 'xlsx':
                    assert len(chunks)==1 and chunks[0][0]==office['final'], chunks
                    expected = json.loads(json.dumps(office['locators']))
                    source_hash = sql.execute('SELECT file_hash FROM knowledges WHERE id=?',(id,)).fetchone()[0]
                    for locator in expected:
                        locator['source_hash'] = source_hash
                    assert json.loads(chunks[0][1])==expected, chunks
                assert sql.execute('SELECT COUNT(*) FROM lite_embeddings WHERE knowledge_id=?',(id,)).fetchone()[0]>0
                audit=sql.execute("SELECT tenant_id,actor_user_id,actor_role,outcome,details FROM audit_logs WHERE action='knowledge.created' AND target_id=?",(id,)).fetchone()
                assert audit[:2]==(1,actor) and audit[3]=='accepted',audit
                if actor=='foreign':
                    assert audit[2]=='contributor',audit
                detail=json.loads(audit[4]);assert detail['task_id'] and detail['processing_status']=='pending'
                assert sql.execute('SELECT tenant_id FROM java_task_jobs WHERE id=?',(detail['task_id'],)).fetchone()==(1,)
            assert sql.execute('SELECT COUNT(*) FROM vec_embeddings_3').fetchone()[0]>=expected_uploads
            assert sql.execute("SELECT COUNT(*) FROM lite_embeddings_fts WHERE lite_embeddings_fts MATCH '\"paragraph\"'").fetchone()[0]>0
            assert sql.execute('SELECT COUNT(*) FROM resources WHERE tenant_id=1').fetchone()==(expected_uploads,)
            assert sql.execute('SELECT COUNT(*) FROM resource_bindings WHERE tenant_id=1').fetchone()==(expected_uploads,)
            assert sql.execute('SELECT COUNT(*) FROM java_task_jobs WHERE tenant_id=2').fetchone()==(0,)
            assert sql.execute("SELECT COUNT(*) FROM java_task_jobs WHERE type='knowledge:post_process' AND queue='postprocess' AND timeout_millis=1800000 AND max_attempts=4").fetchone()==(expected_uploads,)
            assert sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()[0]==sql.execute('SELECT SUM(storage_size) FROM knowledges WHERE tenant_id=1').fetchone()[0]
        assert len(calls)==expected_uploads,calls
        assert len(reader_calls)==int(bool(office)),reader_calls
        print(json.dumps({'checks':'production multipart → local resource → worker → actual model HTTP → vec/FTS → durable postprocess completion; real shared write + owner tenant/original actor audit; Hikari pool1','topology':args.topology,'readiness_retries':readiness_retries,'uploads':expected_uploads,'jobs':expected_jobs,'embedding_calls':len(calls),'office_custom_format':bool(office),'reader_calls':len(reader_calls),'log':str(log),'database':str(database)},ensure_ascii=False))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill();process.wait(timeout=5)
        model.shutdown();model.server_close()


if __name__=='__main__':
    main()
