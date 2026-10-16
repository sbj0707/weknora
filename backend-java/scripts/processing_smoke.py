#!/usr/bin/env python3
"""Exercise opt-in production upload/embedding/index/postprocess beans with a one-connection pool."""
import argparse
import base64
import hashlib
import hmac
import io
import zipfile
import json
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    parser.add_argument('vec', type=Path)
    parser.add_argument('--topology', choices=('single', 'go'), default='go')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--kb-delete', action='store_true', help='Delete a KB through authenticated public HTTP and the actual native worker; compare physical resources, Wiki, queue cancellation and peer isolation')
    modes.add_argument('--duplicate', action='store_true', help='Duplicate KB settings through actual JWT/API-key HTTP; retain source documents, physical indexes, resources, quota and jobs')
    modes.add_argument('--move', action='store_true', help='Run public reuse/reparse moves through actual Redis progress, both SQL worker layouts, retained files/quota and physical indexes')
    modes.add_argument('--url-import', action='store_true', help='Import own/shared web and file URLs through public admission; verify downloads, reader, workers, vec/FTS, quota and audit')
    modes.add_argument('--file-response', action='store_true', help='Read original resources through native download/preview with Range and role/share boundaries')
    modes.add_argument('--reparse', action='store_true', help='Rebuild own/shared documents through single and batch reparse; verify durable workers, attempts, indexes, files and audit')
    modes.add_argument('--kb-profile', action='store_true', help='Run summary, questions, tags and debounced KB profile, then synchronous own/shared regeneration')
    modes.add_argument('--index-delete', action='store_true', help='Exercise the packaged cleanup worker using isolated durable queue fixtures after own/shared uploads')
    modes.add_argument('--regenerate-summary', action='store_true', help='Verify own/shared synchronous first summary and durable refresh through the public endpoint, including queue failure and physical index retention')
    modes.add_argument('--update-knowledge', action='store_true', help='Exercise own creator-Viewer/shared Editor partial updates, durable summary refresh and retained source indexes/quota')
    modes.add_argument('--chunk-questions', action='store_true', help='Verify own/shared synchronous question upsert/delete aliases with body revisions and source retention')
    modes.add_argument('--chunk-regenerate', action='store_true', help='Verify own/shared synchronous Doc2Query regeneration, hashed aliases and old alias replacement without refresh tasks')
    modes.add_argument('--chunk-edit', action='store_true', help='Exercise own creator-Viewer/shared Contributor chunk edits/reverts, immutable revisions, real vec/FTS replacement and summary refresh')
    modes.add_argument('--folders', action='store_true', help='Exercise own creator-Viewer folder tree/rename and shared Contributor/Editor bulk refile without rebuilding sources or indexes')
    modes.add_argument('--chunk-lifecycle', action='store_true', help='Compare own single/shared bulk chunk tombstones and revision reads while retaining source/quota/physical vec/FTS')
    modes.add_argument('--public-delete', action='store_true', help='Exercise public own single/shared batch deletion and empty-KB clear through native workers')
    modes.add_argument('--document-delete', action='store_true', help='Delete own/shared uploaded documents through the public tag producer and native worker')
    modes.add_argument('--summary', action='store_true', help='Enable native document summary generation and verify its persisted results')
    modes.add_argument('--auto-tag', action='store_true', help='Enable summary, questions and automatic tagging together and verify independent tag counters')
    modes.add_argument('--question', action='store_true', help='Enable summary and questions together and verify atomic fanout and physical question indexes')
    modes.add_argument('--cancel', action='store_true', help='Cancel native parsing during blocked summary HTTP and verify retained content and queue settlement')
    modes.add_argument('--office', action='store_true', help='Also upload a Go custom-format XLSX through a controlled HTTP reader')
    parser.add_argument('--image-update', action='store_true', help='Update own/shared image OCR/captions through native HTTP, vec/FTS and chained document hashes without quota or task mutation')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    tagging = args.auto_tag or args.kb_profile
    summary = args.summary or args.question or tagging or args.cancel or args.update_knowledge or args.chunk_edit or args.chunk_regenerate or args.regenerate_summary
    question = args.question or tagging
    releases, cancel_releases = [], []
    refresh_started, refresh_release = threading.Event(), threading.Event()
    public_summary_async = threading.Event()
    releases.append(refresh_release)
    root, jar, vec = args.root.resolve(), args.jar.resolve(), args.vec.resolve()
    if not jar.is_file() or not vec.is_file():
        parser.error('jar and explicitly compiled sqlite-vec v0.1.6 library must exist')
    folder = Path(tempfile.mkdtemp(prefix='weknora-java-processing-smoke-'))
    database, log = folder/'test.db', folder/'server.log'
    secret = secrets.token_urlsafe(32)
    tokens, calls, reader_calls, chat_calls = {}, [], [], []
    office = next((f for f in json.loads((root/'backend-java/src/test/resources/go-office-attachment.json').read_text())
                  if f['name']=='xlsx-custom-format' and not f['provided']), None) if args.office else None

    class EmbeddingHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            if self.path == '/read' and args.url_import:
                assert request['url'] == f'http://127.0.0.1:{self.server.server_port}/url-smoke-page', request
                reader_calls.append(request)
                result = {'markdown_content': 'Remote web evidence is published through public native admission.', 'metadata': {'title': 'Remote evidence title'}}
            elif self.path == '/read' and office:
                assert request['file_type'] == 'xlsx' and base64.b64decode(request['file_content']) == base64.b64decode(office['data']), request
                reader_calls.append(request)
                result = {'markdown_content': office['parsed'], 'image_refs': [], 'source_blocks': [], 'metadata': {}, 'error': ''}
            elif self.path == '/v1/chat/completions' and tagging and request['messages'][0]['content'].startswith('You classify one document'):
                chat_calls.append(request)
                assert request['temperature'] == .1 and request['max_tokens'] == 1024, request
                assert request['chat_template_kwargs']['enable_thinking'] is False, request
                assert '1. Migration' in request['messages'][1]['content'], request
                result = {'choices': [{'message': {'role': 'assistant', 'content': '{"matches":[{"index":1}]}'}, 'finish_reason': 'stop'}]}
            elif self.path == '/v1/chat/completions' and (question or args.chunk_regenerate) and request['messages'][0]['role'] == 'user':
                chat_calls.append(request)
                assert request['temperature'] == .7 and request['max_tokens'] == 512, request
                assert request['chat_template_kwargs']['enable_thinking'] is False, request
                assert '<main_content>' in request['messages'][0]['content'], request
                result = {'choices': [{'message': {'role': 'assistant', 'content': 'What does the document describe?\nHow does the document explain migration?\nWhy does the document describe processing?'}, 'finish_reason': 'stop'}]}
            elif self.path == '/v1/chat/completions' and args.kb_profile and request.get('max_tokens') == 800:
                chat_calls.append(request)
                assert request['temperature'] == .2 and request['chat_template_kwargs']['enable_thinking'] is False, request
                assert 'Documents: 2 (with document profile: 2)' in request['messages'][1]['content'], request
                assert 'Document profile' in request['messages'][1]['content'] and 'Migration (2)' in request['messages'][1]['content'], request
                content = json.dumps({'gist': 'Knowledge base release collection 中文', 'topics': ['migration'], 'typical_questions': ['How is migration verified?']})
                result = {'choices': [{'message': {'role': 'assistant', 'content': content}, 'finish_reason': 'stop'}]}
            elif self.path == '/v1/chat/completions' and summary:
                chat_calls.append(request)
                if args.cancel:
                    release = threading.Event()
                    releases.append(release)
                    cancel_releases.append(release)
                    assert release.wait(20), 'Cancellation test did not release summary model HTTP'
                if (args.update_knowledge and 'SMOKE REFRESH' in json.dumps(request['messages'],ensure_ascii=False)) or (args.regenerate_summary and public_summary_async.is_set()):
                    refresh_started.set()
                    assert refresh_release.wait(20), 'Refresh was not released by the acceptance runner'
                assert request['temperature'] == .3 and request['max_tokens'] == 1024, request
                assert request['chat_template_kwargs']['enable_thinking'] is False, request
                content = json.dumps({'summary': 'Document summary 中文', 'gist': 'Document profile',
                                      'doc_type': 'note', 'topics': ['migration'],
                                      'typical_question': 'What does the document describe?'})
                result = {'choices': [{'message': {'role': 'assistant', 'content': content}, 'finish_reason': 'stop'}]}
            elif self.path == '/v1/chat/completions' and office:
                chat_calls.append(request)
                assert request['temperature'] == .3
                assert request['max_tokens'] == (512 if len(chat_calls) == 1 else 2048)
                assert request['chat_template_kwargs']['enable_thinking'] is False
                assert 'Table name: dataset' in request['messages'][0]['content']
                content = 'Table metadata 中文' if len(chat_calls) == 1 else 'Column metadata 中文'
                result = {'choices': [{'message': {'role': 'assistant', 'content': content}, 'finish_reason': 'stop'}]}
            else:
                assert self.path == '/v1/embeddings', self.path
                calls.append(request)
                result = {'data': [{'index': i, 'embedding': [1, 0, 0]} for i in reversed(range(len(request['input'])))]}
            data = json.dumps(result).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                if not args.cancel:
                    raise
        def do_GET(self):
            assert args.url_import and self.path == '/url-smoke.txt', self.path
            data = b'Remote file evidence is downloaded and indexed through native URL admission.'
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
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
                     '000006_task_queue_and_dead_letters',
                     '000007_system_admin_and_settings',
                     '000008_processing_spans_and_pending_subtasks', '000010_knowledge_multi_tags',
                     '000011_principal_model', '000021_knowledge_profiles',
                     '000031_model_catalog_config', '000032_chunk_images', '000033_chunk_source_locators'):
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
        if args.kb_profile:
            sql.execute("UPDATE knowledge_bases SET profile_config=? WHERE id='kb'", (json.dumps({'enabled': True}).encode(),))
        if question:
            sql.execute("UPDATE knowledge_bases SET question_generation_config=? WHERE id='kb'", (json.dumps({'enabled': True, 'question_count': 3}),))
        if tagging:
            sql.execute("UPDATE knowledge_bases SET auto_tag_config=? WHERE id='kb'", (json.dumps({'enabled': True}).encode(),))
            sql.execute("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,sort_order,seq_id,created_at) VALUES('smoke-tag',1,'kb','Migration',0,1,'2026-01-01')")
        params = json.dumps({'provider':'generic','base_url':f'http://127.0.0.1:{model.server_port}/v1','embedding_parameters':{'dimension':3}})
        sql.execute("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES('embedding',1,'unknown','Embedding','remote',?,false,'active')", (params.encode(),))
        if office or summary:
            sql.execute("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES('summary',1,'unknown','KnowledgeQA','remote',?,false,'active')", (params.encode(),))
        assert sql.execute("SELECT typeof(parameters) FROM models WHERE id='embedding'").fetchone() == ('blob',)
        sql.execute("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'owner')")
        sql.execute("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('caller','org',2,'editor')")
        sql.execute("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,'editor','owner')")
    env = os.environ.copy()
    env.update(NEO4J_ENABLE='false',JAVA_DB_URL='jdbc:sqlite:'+str(database),JWT_SECRET=secret,
               JAVA_DB_POOL_SIZE='1',JAVA_GO_BRIDGE_ENABLED='false',RETRIEVE_DRIVER='sqlite',
               LOCAL_STORAGE_BASE_DIR=str(folder/'files'),MAX_FILE_SIZE_MB='1',
               SSRF_WHITELIST='127.0.0.1',SSRF_DNS_WHITELIST_ONLY='true',
               WEKNORA_SQLITE_VEC_EXTENSION=str(vec))
    if office or args.url_import:
        env.update(DOCREADER_TRANSPORT='http', DOCREADER_ADDR=f'http://127.0.0.1:{model.server_port}')
    for pool in ('CORE', 'POSTPROCESS', 'ENRICHMENT', 'MAINTENANCE', 'SHARED'):
        env['WEKNORA_ASYNQ_'+pool+'_CONCURRENCY'] = '1'
    env['WEKNORA_WIKI_ASYNQ_CONCURRENCY'] = '1'
    process = None
    redis_process = None
    try:
        if args.move:
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                redis_port = reservation.getsockname()[1]
            with (folder/'redis.log').open('wb') as redis_log:
                redis_process = subprocess.Popen([os.environ.get('WEKNORA_REDIS_EXECUTABLE', 'redis-server'),
                    '--bind', '127.0.0.1', '--port', str(redis_port), '--save', '', '--appendonly', 'no',
                    '--dir', str(folder)], stdout=redis_log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+8
            while True:
                try:
                    with socket.create_connection(('127.0.0.1', redis_port), timeout=.3) as connection:
                        connection.sendall(b'PING\r\n')
                        assert connection.recv(64).startswith(b'+PONG')
                        break
                except OSError:
                    assert redis_process.poll() is None and time.monotonic()<deadline, str(folder/'redis.log')
                    time.sleep(.02)
            env.update(REDIS_ADDR='127.0.0.1:'+str(redis_port), REDIS_DB='0',
                       REDIS_PASSWORD='', REDIS_USERNAME='', REDIS_USE_TLS='false')
        with log.open('wb') as output:
            process = subprocess.Popen(['java','-jar',str(jar),'--server.address=127.0.0.1','--server.port=0',
                '--spring.datasource.driver-class-name=org.sqlite.JDBC',
                '--spring.datasource.hikari.connection-timeout=2000',
                '--weknora.tasks.enabled=true','--weknora.tasks.topology='+args.topology,'--weknora.knowledge.processing.enabled=true',
                '--weknora.knowledge.upload.enabled=true','--weknora.tasks.concurrency=1',
                '--weknora.tasks.poll-millis=100','--weknora.tasks.lease-millis=5000',
                '--weknora.tasks.queues=default=1,postprocess=1,summary=1,question=1'+(',low=1' if args.kb_delete or args.move or args.index_delete or args.document_delete or args.public_delete or args.reparse else ''),'--logging.level.root=WARN','--debug=false',
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
        expected_jobs = (5 if tagging else 4 if question else 3 if summary else 2)*expected_uploads + int(bool(office))
        for actor, suffix, content in uploads:
            config = json.dumps({'summary_enabled': summary, 'question_generation_config': {'enabled': question, 'question_count': 3}})
            body = ('--boundary\r\nContent-Disposition: form-data; name="process_config"\r\n\r\n'+config+'\r\n'
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
            if args.cancel:
                cancel_deadline = time.monotonic()+15
                while len(cancel_releases) < len(ids):
                    assert process.poll() is None and time.monotonic()<cancel_deadline, ('summary did not reach controlled HTTP', str(log))
                    time.sleep(.05)
                document_id = ids[-1][1]
                with sqlite3.connect(database) as sql:
                    assert sql.execute("SELECT parse_status FROM knowledges WHERE id=?", (document_id,)).fetchone() == ('finalizing',)
                    assert sql.execute("SELECT COUNT(*) FROM chunks WHERE knowledge_id=?", (document_id,)).fetchone()[0] > 0
                cancel_request = urllib.request.Request(base+'/api/v1/knowledge/'+document_id+'/cancel-parse', data=b'',
                    headers={'Authorization': 'Bearer '+tokens[actor]}, method='POST')
                with urllib.request.urlopen(cancel_request, timeout=5) as response:
                    cancelled=json.load(response)
                    assert response.status==200 and cancelled['data']['parse_status']=='cancelled', cancelled
                cancel_releases[len(ids)-1].set()
        deadline=time.monotonic()+(80 if args.kb_profile else 20)
        profile_schedules = {}
        while True:
            with sqlite3.connect(database,timeout=2) as sql:
                status=sql.execute('SELECT id,parse_status,error_message FROM knowledges ORDER BY id').fetchall()
                jobs=sql.execute('SELECT type,status,last_error FROM java_task_jobs ORDER BY created_at').fetchall()
                profile_jobs=[row for row in jobs if row[0]=='kb:profile']
                if args.kb_profile:
                    for job_id, delay in sql.execute("SELECT id,available_at-created_at FROM java_task_jobs WHERE type='kb:profile' AND status='pending'"):
                        assert delay == 30000, (job_id, delay)
                        profile_schedules[job_id] = delay
            if len(status)==expected_uploads and all(row[1]==('cancelled' if args.cancel else 'completed') for row in status) and len(jobs)==expected_jobs+len(profile_jobs) and (not args.kb_profile or len(profile_jobs) in (1,2)) and all(row[1]==('canceled' if args.cancel and row[0]=='summary:generation' else 'succeeded') for row in jobs):
                break
            if process.poll() is not None or any(row[1]=='failed' for row in status) or any(row[1]=='dead' for row in jobs) or time.monotonic()>deadline:
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
                chunks=sql.execute("SELECT content,source_locators FROM chunks WHERE knowledge_id=? AND chunk_type='text'",(id,)).fetchall()
                assert chunks and all(json.loads(chunk[1])[0]['source_hash'] for chunk in chunks),chunks
                if summary and not args.cancel:
                    summary_row = sql.execute("SELECT description,profile,summary_status,pending_subtasks_count FROM knowledges WHERE id=?", (id,)).fetchone()
                    assert summary_row[0] == 'Document summary 中文' and summary_row[2:] == ('completed', 0), summary_row
                    assert json.loads(summary_row[1]) == {'gist': 'Document profile', 'doc_type': 'note', 'topics': ['migration'], 'typical_question': 'What does the document describe?'}, summary_row
                    stored = sql.execute("SELECT id,content FROM chunks WHERE knowledge_id=? AND chunk_type='summary' AND deleted_at IS NULL", (id,)).fetchall()
                    assert len(stored) == 1 and stored[0][1] == '# Summary\nDocument profile\n\nDocument summary 中文', stored
                    assert sql.execute("SELECT COUNT(*) FROM lite_embeddings WHERE source_id=?", (stored[0][0],)).fetchone() == (1,)
                    assert sql.execute("SELECT COUNT(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND name='postprocess.summary' AND status='done'", (id,)).fetchone() == (1,)
                if question:
                    rows = sql.execute("SELECT id,metadata,content_revision FROM chunks WHERE knowledge_id=? AND chunk_type='text'", (id,)).fetchall()
                    for chunk_id, metadata, revision in rows:
                        questions = json.loads(metadata)['generated_questions']
                        assert len(questions) == 3 and all(q['content_revision']==revision for q in questions), questions
                        for q in questions:
                            source = chunk_id+'-'+q['id']
                            if len(source.encode())>64:
                                source = chunk_id+'-q'+hashlib.sha256(q['id'].encode()).hexdigest()[:24]
                            index = sql.execute("SELECT chunk_id,content FROM lite_embeddings WHERE source_id=? AND knowledge_id=?", (source,id)).fetchone()
                            assert index and index[0]==chunk_id and index[1].endswith(q['question']), index
                    assert sql.execute("SELECT COUNT(*) FROM java_question_task_drains WHERE tenant_id=1 AND knowledge_id=?", (id,)).fetchone() == (1,)
                    assert sql.execute("SELECT COUNT(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND name='postprocess.question' AND status='done'", (id,)).fetchone() == (1,)
                if tagging:
                    assert sql.execute("SELECT tag_id FROM knowledge_tag_relations WHERE knowledge_id=?", (id,)).fetchall() == [('smoke-tag',)]
                    assert sql.execute("SELECT COUNT(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND name='postprocess.auto_tag' AND status='done'", (id,)).fetchone() == (1,)
                    assert sql.execute("SELECT queue,max_attempts,timeout_millis FROM java_task_jobs WHERE type='knowledge:auto_tag' AND json_extract(payload,'$.knowledge_id')=?", (id,)).fetchall() == [('summary',3,120000)]
                if args.cancel:
                    cancelled = sql.execute("SELECT parse_status,summary_status,pending_subtasks_count,description FROM knowledges WHERE id=?", (id,)).fetchone()
                    assert cancelled == ('cancelled','none',0,''), cancelled
                    assert sql.execute("SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND chunk_type='summary'", (id,)).fetchone() == (0,)
                    assert sql.execute("SELECT COUNT(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND kind='root' AND status='done'", (id,)).fetchone() == (1,)
                    assert sql.execute("SELECT COUNT(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND status IN ('pending','running')", (id,)).fetchone() == (0,)
                    assert sql.execute("SELECT COUNT(*) FROM audit_logs WHERE target_id=? AND action='knowledge.parse_canceled'", (id,)).fetchone() == (1,)
                if suffix == 'xlsx':
                    assert len(chunks)==1 and chunks[0][0]==office['final'], chunks
                    expected = json.loads(json.dumps(office['locators']))
                    source_hash = sql.execute('SELECT file_hash FROM knowledges WHERE id=?',(id,)).fetchone()[0]
                    for locator in expected:
                        locator['source_hash'] = source_hash
                    assert json.loads(chunks[0][1])==expected, chunks
                    summaries = sql.execute("SELECT id,chunk_type,content,parent_chunk_id,pre_chunk_id,next_chunk_id,status FROM chunks WHERE knowledge_id=? AND chunk_type IN ('table_summary','table_column') AND deleted_at IS NULL ORDER BY chunk_index", (id,)).fetchall()
                    assert len(summaries) == 2 and all(row[6] == 2 for row in summaries), summaries
                    assert summaries[0][1] == 'table_summary' and summaries[1][1] == 'table_column', summaries
                    assert summaries[0][5] == summaries[1][0] and summaries[1][3:5] == (summaries[0][0], summaries[0][0]), summaries
                    assert all('Table name: dataset' in row[2] for row in summaries), summaries
                    assert sql.execute("SELECT COUNT(*) FROM lite_embeddings WHERE source_id IN (?,?)", (summaries[0][0], summaries[1][0])).fetchone() == (2,)
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
        assert len(calls)==expected_uploads*(3 if question else 1 if args.cancel else 2 if summary else 1) + int(bool(office)),calls
        profile_calls=[request for request in chat_calls if request.get('max_tokens')==800]
        assert len(chat_calls)-len(profile_calls)==(expected_uploads*(3 if tagging else 2 if question else 1) if summary else 2*int(bool(office))),chat_calls
        if args.kb_profile:
            assert len(profile_calls)==1, profile_calls
            with sqlite3.connect(database) as sql:
                stored=json.loads(sql.execute("SELECT generated_profile FROM knowledge_bases WHERE id='kb'").fetchone()[0])
                assert stored['status']=='ready' and stored['stats']['document_count']==2 and stored['stats']['profiled_count']==2, stored
                queued=sql.execute("SELECT j.id,j.tenant_id,j.queue,j.max_attempts,j.timeout_millis,a.started_at-j.created_at FROM java_task_jobs j JOIN java_task_attempts a ON a.task_id=j.id AND a.attempt=1 WHERE j.type='kb:profile'").fetchall()
                assert queued and all(row[1:5]==(1,'summary',3,300000) and row[5]>=30000 and row[0] in profile_schedules for row in queued), queued
                count=sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()[0]
            for actor in ('owner','foreign'):
                request=urllib.request.Request(base+'/api/v1/knowledge-bases/kb/profile/generate',data=b'',method='POST',headers={'Authorization':'Bearer '+tokens[actor],'Accept-Language':'en-US'})
                with urllib.request.urlopen(request,timeout=10) as response:
                    result=json.load(response)
                    assert response.status==200 and result['data']['gist']=='Knowledge base release collection 中文', result
            assert len([request for request in chat_calls if request.get('max_tokens')==800])==3
            with sqlite3.connect(database) as sql:
                assert sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()[0]==count
                assert sql.execute('SELECT SUM(pending_subtasks_count) FROM knowledges').fetchone()==(0,)
        assert len(reader_calls)==int(bool(office)),reader_calls
        cleanup_jobs = []
        if args.kb_delete:
            def delete_http(actor='owner', key=None, expected=200):
                headers={'X-API-Key':key} if key else {'Authorization':'Bearer '+tokens[actor]}
                request=urllib.request.Request(base+'/api/v1/knowledge-bases/kb',method='DELETE',headers=headers)
                try:
                    with urllib.request.urlopen(request,timeout=15) as response:
                        assert response.status==expected
                        return json.load(response)
                except urllib.error.HTTPError as error:
                    raw=error.read();assert error.code==expected,(error.code,raw.decode(errors='replace'),str(log))
                    return json.loads(raw)
            model_before=(len(calls),len(chat_calls),len(reader_calls))
            with sqlite3.connect(database) as sql:
                references=sql.execute("SELECT r.physical_path FROM resources r WHERE r.tenant_id=1").fetchall()
                assert len(references)==expected_uploads
                original_paths=[folder/'files'/row[0].removeprefix('local://') for row in references]
                assert all(path.is_file() for path in original_paths)
                for capability,full,scope in [('retrieve',False,'kb'),('ingest',False,'kb'),('manage_kbs',True,'peer')]:
                    key='kb-delete-'+secrets.token_hex(16)
                    sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Denied delete',?,?,?,?,?)",(hashlib.sha256(key.encode()).hexdigest(),key,full,json.dumps([capability]),json.dumps([scope])))
                    sql.commit();delete_http(key=key,expected=403)
                    assert sql.execute("SELECT deleted_at FROM knowledge_bases WHERE id='kb'").fetchone()==(None,)
                delete_http(actor='foreign',expected=403)
                sql.execute("INSERT INTO knowledge_bases(id,tenant_id,name,type,creator_id,embedding_model_id,summary_model_id) VALUES('peer',2,'Protected','document','foreign','embedding','')")
                sql.execute("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,storage_size,file_path) VALUES('protected',2,'peer','file','Protected','manual','completed',77,'local://2/protected.txt')")
                sql.execute("INSERT INTO chunks(id,tenant_id,knowledge_id,knowledge_base_id,content,chunk_index,start_at,end_at) VALUES('protected',2,'protected','peer','peerprotectedmarker',0,0,19)")
                sql.execute("UPDATE tenants SET storage_used=77 WHERE id=2")
                sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                index=sql.execute("INSERT INTO lite_embeddings(source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,content,dimension,is_enabled) VALUES('protected',0,'protected','protected','peer','peerprotectedmarker',3,true) RETURNING id").fetchone()[0]
                import struct
                vector=struct.pack('<fff',.25,.5,.75)
                sql.execute('INSERT INTO vec_embeddings_3(rowid,embedding) VALUES(?,?)',(index,vector))
                sql.execute("INSERT INTO lite_embeddings_fts(rowid,content,source_id,chunk_id,knowledge_id,knowledge_base_id) VALUES(?,'peerprotectedmarker','protected','protected','protected','peer')",(index,))
                for tenant,kb in ((1,'kb'),(2,'peer')):
                    sql.execute("INSERT INTO wiki_pages(id,tenant_id,knowledge_base_id,slug) VALUES(?,?,?,'cleanup')",(kb,tenant,kb))
                    sql.execute("INSERT INTO wiki_folders(id,tenant_id,knowledge_base_id,name) VALUES(?,?,?,'cleanup')",(kb,tenant,kb))
                    sql.execute("INSERT INTO wiki_page_issues(id,tenant_id,knowledge_base_id,slug,issue_type,description,reported_by) VALUES(?,?,?,'cleanup','test','test','test')",(kb,tenant,kb))
                    sql.execute("INSERT INTO wiki_page_revisions(id,tenant_id,knowledge_base_id,page_id,slug,version) VALUES(?,?,?,?,'cleanup',1)",(kb,tenant,kb,kb))
                    sql.execute("INSERT INTO task_pending_ops(tenant_id,task_type,scope,scope_id,op,payload) VALUES(?,'wiki:ingest','knowledge_base',?,'ingest','{}')",(tenant,kb))
                    sql.execute("INSERT INTO data_sources(id,tenant_id,knowledge_base_id,name,type,sync_schedule) VALUES(?,?,?,'Source','notion','0 0 * * *')",(kb,tenant,kb))
                    for status in ('pending','running','success'):
                        sql.execute("INSERT INTO sync_logs(id,tenant_id,data_source_id,status) VALUES(?,?,?,?)",(kb+'-'+status,tenant,kb,status))
                # A retained message claim must protect the shared image after knowledge claims go.
                shared_handle='IIIIIIIIIIIIIIIIIIIIII'
                sql.execute("INSERT INTO resources(id,handle,tenant_id,provider,physical_path,location_hash,kind) VALUES('shared-image',?,1,'local','local://1/shared-image.png','shared-image','image')",(shared_handle,))
                sql.execute("INSERT INTO resource_bindings(id,resource_id,tenant_id,owner_type,owner_id) VALUES('message','shared-image',1,'message','retained')")
                for _,document,_ in ids:
                    sql.execute("INSERT INTO resource_bindings(id,resource_id,tenant_id,owner_type,owner_id) VALUES(?,'shared-image',1,'knowledge',?)",(document,document))
                sql.execute("UPDATE chunks SET image_info=? WHERE knowledge_id=?",(json.dumps([{'url':'local://1/exclusive-image.png'},{'url':'resource://'+shared_handle}]).encode(),ids[0][1]))
                now=int(time.time()*1000)
                obsolete=[]
                for job_type,payload,queue in [('document:process',{'knowledge_base_id':'kb'},'default'),('datasource:sync',{'data_source_id':'kb'},'sync')]:
                    task=str(uuid.uuid4());obsolete.append(task)
                    sql.execute("INSERT INTO java_task_jobs(id,tenant_id,type,idempotency_key,queue,payload,status,attempts,max_attempts,available_at,created_at,updated_at) VALUES(?,1,?,?,?,?,'pending',0,4,?,?,?)",(task,job_type,task,queue,json.dumps(payload),now+3600000,now,now))
                accepted_key='kb-delete-'+secrets.token_hex(16)
                sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Accepted delete',?,?,false,'[\"manage_kbs\"]','[\"kb\"]')",(hashlib.sha256(accepted_key.encode()).hexdigest(),accepted_key))
            for relative in ('1/exclusive-image.png','1/shared-image.png','2/protected.txt'):
                path=folder/'files'/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(relative)
            assert delete_http(key=accepted_key)=={'success':True,'message':'Knowledge base deleted successfully'}
            deadline=time.monotonic()+20
            while True:
                with sqlite3.connect(database,timeout=2) as sql:
                    jobs=sql.execute("SELECT id,status,last_error FROM java_task_jobs WHERE type='kb:delete'").fetchall()
                if len(jobs)==1 and jobs[0][1]=='succeeded':break
                assert process.poll() is None and time.monotonic()<deadline,(jobs,str(log))
                assert not any(row[1]=='dead' for row in jobs),(jobs,str(log))
                time.sleep(.05)
            with sqlite3.connect(database) as sql:
                sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                assert sql.execute("SELECT COUNT(*) FROM knowledge_bases WHERE id='kb' AND deleted_at IS NOT NULL").fetchone()==(1,)
                for table in ('knowledges','chunks','wiki_pages','wiki_folders','wiki_page_issues'):
                    assert sql.execute('SELECT COUNT(*) FROM '+table+" WHERE tenant_id=1 AND deleted_at IS NULL").fetchone()==(0,),(table,'own data remains')
                    assert sql.execute('SELECT COUNT(*) FROM '+table+" WHERE tenant_id=2 AND deleted_at IS NULL").fetchone()==(1,),(table,'protected data changed')
                assert sql.execute('SELECT tenant_id FROM wiki_page_revisions').fetchall()==[(2,)]
                assert sql.execute('SELECT scope_id FROM task_pending_ops').fetchall()==[('peer',)]
                assert sql.execute("SELECT COUNT(*) FROM kb_shares WHERE knowledge_base_id='kb' AND deleted_at IS NOT NULL").fetchone()==(1,)
                assert sql.execute("SELECT COUNT(*) FROM data_sources WHERE id='kb' AND deleted_at IS NOT NULL").fetchone()==(1,)
                assert sql.execute("SELECT id,status,error_message FROM sync_logs WHERE data_source_id='kb' ORDER BY id").fetchall()==[('kb-pending','canceled','data source deleted'),('kb-running','canceled','data source deleted'),('kb-success','success',None)]
                assert sql.execute("SELECT status FROM data_sources WHERE id='peer' AND deleted_at IS NULL").fetchone()==('active',)
                assert sql.execute("SELECT queue,max_attempts,timeout_millis FROM java_task_jobs WHERE type='kb:delete'").fetchone()==('low',4,7200000)
                assert all(sql.execute('SELECT status FROM java_task_jobs WHERE id=?',(task,)).fetchone()==('canceled',) for task in obsolete)
                assert sql.execute('SELECT id,storage_used FROM tenants ORDER BY id').fetchall()==[(1,0),(2,77)]
                assert sql.execute('SELECT id,knowledge_id FROM lite_embeddings').fetchall()==[(index,'protected')]
                assert sql.execute('SELECT rowid,embedding FROM vec_embeddings_3').fetchall()==[(index,vector)]
                assert sql.execute("SELECT rowid FROM lite_embeddings_fts WHERE lite_embeddings_fts MATCH 'peerprotectedmarker'").fetchall()==[(index,)]
                assert sql.execute("SELECT COUNT(*) FROM lite_embeddings_fts WHERE lite_embeddings_fts MATCH 'paragraph'").fetchone()==(0,)
                assert sql.execute("SELECT owner_type,owner_id FROM resource_bindings WHERE resource_id='shared-image'").fetchall()==[('message','retained')]
                assert sql.execute("SELECT state FROM resources WHERE id='shared-image'").fetchone()==('active',)
                audit=sql.execute("SELECT actor_user_id,actor_role,outcome,details FROM audit_logs WHERE action='kb.deleted'").fetchall()
                assert len(audit)==1 and audit[0][:3]==('owner','viewer','success'),audit
                details=json.loads(audit[0][3]);assert details['name']=='Smoke' and details['api_key_name']=='Accepted delete' and details['api_key_id']>0,details
            assert all(not path.exists() for path in original_paths)
            assert not (folder/'files/1/exclusive-image.png').exists()
            assert (folder/'files/1/shared-image.png').is_file() and (folder/'files/2/protected.txt').is_file()
            delete_http(key=accepted_key,expected=404)
            assert (len(calls),len(chat_calls),len(reader_calls))==model_before
            print(json.dumps({'kb_delete':True,'topology':args.topology,'uploads':expected_uploads,'delete_jobs':1,'physical_vec_FTS_files_claims_Wiki_quota_and_peer_isolation':True,'shares_datasources_sync_logs_pending_queue_and_audit':True,'JWT_shared_and_scoped_key_denials':True,'embedding_calls':len(calls),'chat_calls':len(chat_calls),'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'database':str(database),'log':str(log)},ensure_ascii=False))
            return
        if args.duplicate:
            def duplicate_http(actor='owner', key=None, locale='en-US', expected=201):
                headers={'Accept-Language':locale}
                headers.update({'X-API-Key':key} if key else {'Authorization':'Bearer '+tokens[actor]})
                request=urllib.request.Request(base+'/api/v1/knowledge-bases/kb/duplicate',data=b'',method='POST',headers=headers)
                try:
                    with urllib.request.urlopen(request,timeout=10) as response:
                        assert response.status==expected
                        return json.load(response)
                except urllib.error.HTTPError as error:
                    raw=error.read()
                    assert error.code==expected,(error.code,raw.decode(errors='replace'),str(log))
                    return json.loads(raw)
            with sqlite3.connect(database) as sql:
                sql.execute("UPDATE knowledge_bases SET chunking_config=CAST(chunking_config AS BLOB),indexing_strategy=CAST(indexing_strategy AS BLOB),vlm_config=?,cos_config=?,storage_provider_config=?,generated_profile=? WHERE id='kb'", (
                    json.dumps({'enabled':True,'model_id':'vision','api_key':'hidden-runtime-vlm'}).encode(),
                    json.dumps({'provider':'cos','secret_id':'hidden-runtime-id','secret_key':'hidden-runtime-key','bucket_name':'bucket'}).encode(),
                    json.dumps({'provider':'cos'}).encode(),json.dumps({'gist':'Do not duplicate generated content','status':'ready'}).encode()))
                sql.execute("INSERT INTO user_kb_pins(tenant_id,user_id,kb_id,pinned_at) VALUES(1,'owner','kb','2026-01-01 00:00:00+00:00')")
                original=sql.execute("SELECT * FROM knowledge_bases WHERE id='kb'").fetchall()
                retained={table:sql.execute('SELECT * FROM '+table+' ORDER BY 1,2').fetchall() for table in ('knowledges','chunks','tenants','user_kb_pins','resources','resource_bindings','java_task_jobs','lite_embeddings')}
                references=sql.execute('SELECT handle,physical_path FROM resources ORDER BY handle').fetchall()
                files={handle:(folder/'files'/path.removeprefix('local://')).read_bytes() for handle,path in references}
            def physical_duplicate():
                with sqlite3.connect(database) as sql:
                    sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                    return (sql.execute('SELECT rowid,hex(embedding) FROM vec_embeddings_3 ORDER BY rowid').fetchall(),
                        sql.execute('SELECT id,hex(block) FROM lite_embeddings_fts_data ORDER BY id').fetchall())
            physical_before=physical_duplicate();model_before=(len(calls),len(chat_calls),len(reader_calls))
            accepted=[]
            def verify_duplicate(result,name,api_key_id=None):
                assert result['success'],result
                data=result['data'];target=data['knowledge_base'];target_id=data['target_id']
                assert uuid.UUID(target_id).version==4 and data['source_id']=='kb' and target['id']==target_id,data
                assert target['name']==name and target['creator_id']=='owner' and target['tenant_id']==1,target
                assert target['knowledge_count']==0 and target['chunk_count']==0 and not target['is_processing'] and target['processing_count']==0,target
                assert not target['is_pinned'] and target['pinned_at'] is None and not target['is_temporary'],target
                assert not target.get('generated_profile') and target['created_at']==target['updated_at'],target
                with sqlite3.connect(database) as sql:
                    raw=sql.execute('SELECT name,creator_id,generated_profile,typeof(chunking_config),typeof(indexing_strategy),cos_config,vlm_config FROM knowledge_bases WHERE id=?',(target_id,)).fetchone()
                    assert raw[:5]==(name,'owner',None,'blob','blob'),raw
                    assert not any(secret.encode() in raw[5]+raw[6] for secret in ('hidden-runtime-vlm','hidden-runtime-id','hidden-runtime-key')),raw
                    assert sql.execute('SELECT COUNT(*) FROM knowledges WHERE knowledge_base_id=?',(target_id,)).fetchone()==(0,)
                    assert sql.execute('SELECT COUNT(*) FROM chunks WHERE knowledge_base_id=?',(target_id,)).fetchone()==(0,)
                    audit=sql.execute("SELECT actor_user_id,outcome,details FROM audit_logs WHERE action='kb.duplicated' AND target_id=?",(target_id,)).fetchall()
                    assert len(audit)==1 and audit[0][:2]==('owner','success'),audit
                    details=json.loads(audit[0][2]);assert details['source_kb_id']=='kb' and details['name']==name,details
                    if api_key_id is not None:assert details['api_key_id']==api_key_id and details['api_key_name']=='Duplicate acceptance',details
                accepted.append(target_id)
            verify_duplicate(duplicate_http(),'Smoke Copy')
            verify_duplicate(duplicate_http(),'Smoke Copy 2')
            denied=duplicate_http('foreign',expected=403)
            assert denied['error']['message']=='No permission to duplicate this knowledge base',denied
            with sqlite3.connect(database) as sql:sql.execute("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=1")
            duplicate_http(expected=403)
            with sqlite3.connect(database) as sql:sql.execute("UPDATE tenant_members SET role='owner' WHERE user_id='owner' AND tenant_id=1")
            for capability,full,scope,expected in [('retrieve',False,'kb',403),('ingest',False,'kb',403),('manage_kbs',True,'wrong-kb',403),('manage_kbs',False,'kb',201),('retrieve',True,'kb',201)]:
                key='duplicate-'+secrets.token_hex(16)
                with sqlite3.connect(database) as sql:
                    cursor=sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Duplicate acceptance',?,?,?,?,?)",(hashlib.sha256(key.encode()).hexdigest(),key,full,json.dumps([capability]),json.dumps([scope])))
                    key_id=cursor.lastrowid
                result=duplicate_http(key=key,locale='ko-KR',expected=expected)
                if expected==201:verify_duplicate(result,'Smoke 사본'+(' 2' if len(accepted)==3 else ''),key_id)
                with sqlite3.connect(database) as sql:sql.execute('DELETE FROM tenant_api_keys WHERE id=?',(key_id,))
            with sqlite3.connect(database) as sql:
                assert sql.execute("SELECT * FROM knowledge_bases WHERE id='kb'").fetchall()==original
                for table,before in retained.items():assert sql.execute('SELECT * FROM '+table+' ORDER BY 1,2').fetchall()==before,table
                assert sql.execute('SELECT COUNT(*) FROM knowledge_bases').fetchone()==(1+len(accepted),)
            assert physical_duplicate()==physical_before and (len(calls),len(chat_calls),len(reader_calls))==model_before
            assert all((folder/'files'/path.removeprefix('local://')).read_bytes()==files[handle] for handle,path in references)
            print(json.dumps({'duplicate':True,'copies':len(accepted),'shared_viewer_api_key_denials':True,'config_credentials_redacted':True,'source_documents_chunks_indexes_files_quota_pins_jobs_retained':True,'topology':args.topology}))
        if args.move:
            def move_http(method, path, actor='owner', body=None, status=200):
                request=urllib.request.Request(base+path, data=None if body is None else json.dumps(body).encode(),
                    method=method, headers={'Authorization':'Bearer '+tokens[actor], 'Content-Type':'application/json'})
                try:
                    with urllib.request.urlopen(request, timeout=10) as response:
                        assert response.status==status
                        raw=response.read()
                        return json.loads(raw) if response.headers.get_content_type()=='application/json' else raw
                except urllib.error.HTTPError as error:
                    raw=error.read()
                    assert error.code==status, (error.code,raw.decode(errors='replace'),str(log))
                    return json.loads(raw)
            documents=[document for _,document,_ in ids]
            with sqlite3.connect(database) as sql:
                sql.execute("INSERT INTO knowledge_bases(id,name,tenant_id,creator_id,type,embedding_model_id,summary_model_id,indexing_strategy,chunking_config,profile_config) SELECT 'move-target','Move target',tenant_id,creator_id,type,embedding_model_id,summary_model_id,indexing_strategy,chunking_config,'{}' FROM knowledge_bases WHERE id='kb'")
                used=sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()[0]
                source_rows=sql.execute('SELECT id,tenant_id,file_path,file_hash,file_name,file_type,storage_size FROM knowledges ORDER BY id').fetchall()
                source_refs=sql.execute('SELECT handle,physical_path FROM resources ORDER BY handle').fetchall()
                files={handle:(folder/'files'/path.removeprefix('local://')).read_bytes() for handle,path in source_refs}
            targets=move_http('GET','/api/v1/knowledge-bases/kb/move-targets')['data']
            assert [target['id'] for target in targets]==['move-target'], targets
            assert 'creator_name' not in targets[0] and 'vector_store_name' not in targets[0], targets
            move_http('GET','/api/v1/knowledge-bases/kb/move-targets','foreign',status=403)
            def physical():
                with sqlite3.connect(database) as sql:
                    sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                    return (sql.execute('SELECT id,source_id,source_type,chunk_id,knowledge_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY id').fetchall(),
                        sql.execute('SELECT rowid,hex(embedding) FROM vec_embeddings_3 ORDER BY rowid').fetchall(),
                        sql.execute('SELECT id,hex(block) FROM lite_embeddings_fts_data ORDER BY id').fetchall())
            before=physical();before_calls=len(calls)
            body={'source_kb_id':'kb','target_kb_id':'move-target','mode':'reuse_vectors','knowledge_ids':documents}
            move_http('POST','/api/v1/knowledge/move','foreign',body,403)
            task=move_http('POST','/api/v1/knowledge/move',body=body)['data']['task_id']
            def settled(task, kb, count):
                deadline=time.monotonic()+30
                while True:
                    data=move_http('GET','/api/v1/knowledge/move/progress/'+task)['data']
                    with sqlite3.connect(database) as sql:
                        jobs=sql.execute('SELECT type,status,last_error FROM java_task_jobs').fetchall()
                        states=sql.execute('SELECT knowledge_base_id,parse_status,pending_subtasks_count FROM knowledges').fetchall()
                    if data['status']=='completed' and len(jobs)==count and all(row[1]=='succeeded' for row in jobs) and all(row==(kb,'completed',0) for row in states):
                        assert data['progress']==100 and data['total']==len(documents) and data['processed']==len(documents) and data['failed']==0,data
                        return
                    assert process.poll() is None and time.monotonic()<deadline,(data,jobs,states,str(log));time.sleep(.05)
            expected_jobs+=1;settled(task,'move-target',expected_jobs)
            assert physical()==before and len(calls)==before_calls
            move_http('GET','/api/v1/knowledge/move/progress/'+task,'foreign',status=404)
            with socket.create_connection(('127.0.0.1',redis_port),timeout=1) as connection:
                connection.sendall(('TTL knowledge_move_progress:'+task+'\r\n').encode())
                ttl=int(connection.makefile('rb').readline().decode().strip().removeprefix(':'))
                assert 86300<ttl<=86400,ttl
            for actor,document,_ in ids:
                content=move_http('GET','/api/v1/knowledge/'+document+'/download')
                assert content==next(data for source_actor,_,data in uploads if source_actor==actor)
            with sqlite3.connect(database) as sql:
                assert sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()==(used,)
                assert all(row==('move-target','') for row in sql.execute('SELECT knowledge_base_id,tag_id FROM chunks WHERE deleted_at IS NULL'))
            body.update(source_kb_id='move-target',target_kb_id='kb',mode='reparse')
            reparse=move_http('POST','/api/v1/knowledge/move',body=body)['data']['task_id']
            expected_jobs+=1+2*len(documents);settled(reparse,'kb',expected_jobs)
            assert len(calls)==before_calls+len(documents) and not chat_calls and not reader_calls
            with sqlite3.connect(database) as sql:
                assert sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()==(used,)
                assert sql.execute('SELECT id,tenant_id,file_path,file_hash,file_name,file_type,storage_size FROM knowledges ORDER BY id').fetchall()==source_rows
                assert sql.execute('SELECT handle,physical_path FROM resources ORDER BY handle').fetchall()==source_refs
                assert all(row==('kb','') for row in sql.execute('SELECT knowledge_base_id,tag_id FROM chunks WHERE deleted_at IS NULL'))
                assert sql.execute("SELECT COUNT(*) FROM java_task_jobs WHERE type='knowledge:move' AND queue='low' AND max_attempts=4 AND timeout_millis=7200000 AND status='succeeded'").fetchone()==(2,)
                assert sql.execute("SELECT COUNT(*) FROM java_task_named_keys WHERE business_id LIKE 'move-reparse:%' AND queue='default' AND retention_millis=604800000").fetchone()==(len(documents),)
                audit=sql.execute("SELECT actor_user_id,actor_role,scope_id,action FROM audit_logs WHERE target_id IN (?,?)",(task,reparse)).fetchall()
                assert len(audit)==8 and all(row[0:2]==('owner','owner') for row in audit),audit
                assert {row[2] for row in audit}=={'kb','move-target'}
                chunks=sql.execute('SELECT COUNT(*) FROM chunks WHERE deleted_at IS NULL').fetchone()[0]
                assert sql.execute('SELECT COUNT(*) FROM lite_embeddings').fetchone()==(chunks,)
                assert sql.execute("SELECT COUNT(*) FROM lite_embeddings WHERE knowledge_base_id<>'kb'").fetchone()==(0,)
            for handle,path in source_refs:
                assert (folder/'files'/path.removeprefix('local://')).read_bytes()==files[handle]
            assert physical()[1]
            print(json.dumps({'move':True,'real_Redis_progress':True,'public_reuse_and_reparse':True,
                'physical_ID_vector_FTS_retention_verified':True,'files_quota_and_actor_verified':True,
                'source_shared_caller_denied':True,'move_tasks':[task,reparse],'topology':args.topology}))
        if args.index_delete:
            with sqlite3.connect(database) as sql:
                storage=sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()[0]
                before=sql.execute('SELECT COUNT(*) FROM lite_embeddings').fetchone()[0]
                sql.execute("UPDATE models SET type='KnowledgeQA' WHERE id='embedding'")

            def enqueue_cleanup(kb, chunk_ids, document=None):
                # Seed the owned queue to exercise wrong-KB and tombstone worker checkpoints;
                # --document-delete separately covers the actual public document-tag producer.
                task_id=str(uuid.uuid4()); now=int(time.time()*1000)
                payload={'tenant_id':1,'knowledge_base_id':kb,'embedding_model_id':'embedding','kb_type':'document','chunk_ids':chunk_ids,
                         'effective_engines':[{'retriever_engine_type':'sqlite','retriever_type':'vector'},{'retriever_engine_type':'sqlite','retriever_type':'keywords'}]}
                with sqlite3.connect(database) as sql:
                    if document:
                        sql.execute('UPDATE chunks SET deleted_at=CURRENT_TIMESTAMP WHERE tenant_id=1 AND knowledge_id=?',(document,))
                    sql.execute("INSERT INTO java_task_jobs(id,tenant_id,type,idempotency_key,payload,status,max_attempts,available_at,created_at,updated_at,queue,timeout_millis) VALUES(?,1,'index:delete',?,?,'pending',11,?,?,?,'low',3600000)",(task_id,task_id,json.dumps(payload),now,now,now))
                cleanup_jobs.append(task_id)
                deadline=time.monotonic()+20
                while True:
                    with sqlite3.connect(database) as sql:
                        row=sql.execute('SELECT status,last_error FROM java_task_jobs WHERE id=?',(task_id,)).fetchone()
                    if row[0]=='succeeded':break
                    assert process.poll() is None and row[0]!='dead' and time.monotonic()<deadline,(row,str(log))
                    time.sleep(.05)

            with sqlite3.connect(database) as sql:
                first=sql.execute('SELECT id FROM chunks WHERE tenant_id=1 AND knowledge_id=?',(ids[0][1],)).fetchall()
            enqueue_cleanup('ungranted-kb',[row[0] for row in first])
            with sqlite3.connect(database) as sql:
                assert sql.execute('SELECT COUNT(*) FROM lite_embeddings').fetchone()==(before,)
            for actor,document,_ in ids:
                with sqlite3.connect(database) as sql:
                    chunk_ids=[row[0] for row in sql.execute('SELECT id FROM chunks WHERE tenant_id=1 AND knowledge_id=? ORDER BY chunk_index',(document,))]
                enqueue_cleanup('kb',chunk_ids,document)
            with sqlite3.connect(database) as sql:
                sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                for table in ('lite_embeddings','vec_embeddings_3','lite_embeddings_fts'):
                    assert sql.execute('SELECT COUNT(*) FROM '+table).fetchone()==(0,),table
                assert sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()==(storage,)
                assert sql.execute('SELECT SUM(pending_subtasks_count) FROM knowledges').fetchone()==(0,)
                assert sql.execute("SELECT COUNT(*) FROM java_task_jobs WHERE type='index:delete' AND queue='low' AND max_attempts=11 AND timeout_millis=3600000 AND status='succeeded'").fetchone()==(3,)
            assert len(calls)==expected_uploads
        if args.document_delete or args.public_delete:
            for actor, document, _ in ids:
                headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json'}
                tag=None
                if args.document_delete:
                    create=urllib.request.Request(base+'/api/v1/knowledge-bases/kb/tags',data=json.dumps({'name':'Delete '+str(uuid.uuid4())}).encode(),headers=headers,method='POST')
                    with urllib.request.urlopen(create,timeout=10) as response:
                        tag=json.load(response)['data']['id']
                with sqlite3.connect(database) as sql:
                    # Only this isolated fixture admits the persisted document/tag relationship.
                    if args.document_delete:sql.execute('INSERT INTO knowledge_tag_relations(knowledge_id,tag_id) VALUES(?,?)',(document,tag))
                    reference,size=sql.execute('SELECT file_path,storage_size FROM knowledges WHERE id=?',(document,)).fetchone()
                    handle=reference.removeprefix('resource://')
                    physical=sql.execute('SELECT physical_path FROM resources WHERE handle=?',(handle,)).fetchone()[0]
                    source_file=folder/'files'/physical.removeprefix('local://')
                    assert source_file.is_file(),physical
                    storage=sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()[0]
                retained=actor=='foreign'
                if args.public_delete:
                    target=base+'/api/v1/knowledge/'+document if actor=='owner' else base+'/api/v1/knowledge/batch-delete'
                    body=b'' if actor=='owner' else json.dumps({'kb_id':'kb','ids':[' '+document+' ',document]}).encode()
                    delete=urllib.request.Request(target,data=body,headers=headers,method='DELETE' if actor=='owner' else 'POST')
                else:
                    delete=urllib.request.Request(base+'/api/v1/knowledge-bases/kb/tags/'+tag+('?'+'content_only=true' if retained else '?force=true'),data=b'',headers=headers,method='DELETE')
                with urllib.request.urlopen(delete,timeout=10) as response:
                    admitted=json.load(response);assert response.status==200 and admitted['success']
                    public_task=admitted['data']['task_id'] if args.public_delete else None
                    if args.public_delete and actor!='owner':assert admitted['data']['deleted_count']==1
                deadline=time.monotonic()+20
                while True:
                    with sqlite3.connect(database) as sql:
                        task=sql.execute("SELECT id,status,last_error,payload,tenant_id,queue,max_attempts,timeout_millis FROM java_task_jobs WHERE type='knowledge:list_delete' AND json_extract(payload,'$.knowledge_ids[0]')=?",(document,)).fetchone()
                    if task and task[1]=='succeeded':break
                    assert process.poll() is None and (not task or task[1]!='dead') and time.monotonic()<deadline,(task,str(log))
                    time.sleep(.05)
                if args.public_delete:assert task[0]==public_task
                cleanup_jobs.append(task[0]);payload=json.loads(task[3]);assert task[4:]==(1,'low',4,7200000),task
                assert payload['knowledge_base_id']=='kb' and payload['initiator']['user_id']==actor,payload
                assert not source_file.exists(),physical
                with sqlite3.connect(database) as sql:
                    if args.document_delete:assert sql.execute('SELECT COUNT(*) FROM knowledge_tags WHERE id=?',(tag,)).fetchone()==(int(retained),)
                    assert sql.execute('SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL',(document,)).fetchone()==(0,)
                    assert sql.execute('SELECT COUNT(*) FROM lite_embeddings WHERE knowledge_id=?',(document,)).fetchone()==(0,)
                    assert sql.execute('SELECT COUNT(*) FROM knowledges WHERE id=? AND deleted_at IS NOT NULL',(document,)).fetchone()==(1,)
                    assert sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()==(max(0,storage-size),)
                    assert sql.execute("SELECT actor_user_id FROM audit_logs WHERE action='knowledge.batch_deleted' AND json_extract(details,'$.task_id')=?",(task[0],)).fetchone()==(actor,)
            with sqlite3.connect(database) as sql:
                sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                for table in ('lite_embeddings','vec_embeddings_3','lite_embeddings_fts'):
                    assert sql.execute('SELECT COUNT(*) FROM '+table).fetchone()==(0,),table
            if args.public_delete:
                clear=urllib.request.Request(base+'/api/v1/knowledge-bases/kb/knowledge',data=b'',headers={'Authorization':'Bearer '+tokens['owner']},method='DELETE')
                with urllib.request.urlopen(clear,timeout=10) as response:
                    result=json.load(response);assert response.status==200 and result=={'success':True,'message':'Knowledge base is already empty','data':{'deleted_count':0}},result
                with sqlite3.connect(database) as sql:assert sql.execute("SELECT COUNT(*) FROM java_task_jobs WHERE type='knowledge:list_delete'").fetchone()==(2,)
            assert len(calls)==expected_uploads and not chat_calls
        if args.chunk_lifecycle:
            def chunk_http(method, target, actor, expected=200):
                request = urllib.request.Request(base+target, headers={'Authorization':'Bearer '+tokens[actor]}, method=method)
                try:
                    with urllib.request.urlopen(request, timeout=10) as response:
                        data=json.load(response);assert response.status==expected,(response.status,data)
                        return data
                except urllib.error.HTTPError as error:
                    data=json.load(error);assert error.code==expected,(error.code,data,str(log))
                    return data
            def retained_snapshot():
                with sqlite3.connect(database) as sql:
                    sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                    stores=[sql.execute('SELECT COUNT(*) FROM '+table).fetchone()[0] for table in ('lite_embeddings','vec_embeddings_3','lite_embeddings_fts')]
                    assert all(stores),stores
                    quota=sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()[0]
                    jobs=sql.execute('SELECT id,type,status FROM java_task_jobs ORDER BY id').fetchall()
                    docs=sql.execute('SELECT id,parse_status,deleted_at,storage_size FROM knowledges ORDER BY id').fetchall()
                    refs=sql.execute('SELECT physical_path FROM resources ORDER BY handle').fetchall()
                sources=[(folder/'files'/ref[0].removeprefix('local://')).read_bytes() for ref in refs]
                assert all(sources),sources
                return stores,quota,jobs,docs,sources,len(calls),len(chat_calls)
            before=retained_snapshot()
            for actor,document,_ in ids:
                with sqlite3.connect(database) as sql:
                    chunk=sql.execute('SELECT id FROM chunks WHERE tenant_id=1 AND knowledge_id=? AND deleted_at IS NULL ORDER BY chunk_index LIMIT 1',(document,)).fetchone()[0]
                    for rev in (1,2):
                        sql.execute("INSERT INTO chunk_revisions(id,tenant_id,knowledge_base_id,knowledge_id,chunk_id,revision,content,is_enabled,editor_id,edit_source,edited_at,created_at) VALUES(?,1,'kb',?,?,?, ?,true,?,'manual','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')",(str(uuid.uuid4()),document,chunk,rev,'Original '+str(rev)+' 中文',actor))
                target='/api/v1/chunks/'+document+'/'+chunk
                result=chunk_http('GET',target+'/revisions',actor)
                assert [r['revision'] for r in result['data']]==[2,1],result
                assert result['data'][0]['content']=='Original 2 中文' and result['data'][0]['tenant_id']==1,result
                chunk_http('DELETE',target if actor=='owner' else '/api/v1/chunks/'+document,actor)
                chunk_http('GET',target+'/revisions',actor,404)
                with sqlite3.connect(database) as sql:
                    assert sql.execute('SELECT COUNT(*) FROM chunks WHERE id=? AND deleted_at IS NOT NULL',(chunk,)).fetchone()==(1,)
                    if actor!='owner':assert sql.execute('SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL',(document,)).fetchone()==(0,)
                    assert sql.execute('SELECT COUNT(*) FROM chunk_revisions WHERE chunk_id=?',(chunk,)).fetchone()==(2,)
                assert retained_snapshot()==before,'Direct chunk tombstones must retain source/quota/physical stores and add no cleanup jobs'
        if args.folders:
            def folder_http(method, target, actor, body=None, expected=200):
                request=urllib.request.Request(base+target,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json'},method=method)
                try:
                    with urllib.request.urlopen(request,timeout=10) as response:
                        data=json.load(response);assert response.status==expected,(response.status,data);return data
                except urllib.error.HTTPError as error:
                    data=json.load(error);assert error.code==expected,(error.code,data,str(log));return data
            def folder_snapshot():
                with sqlite3.connect(database) as sql:
                    sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                    index=sql.execute('SELECT * FROM lite_embeddings ORDER BY id').fetchall()
                    vectors=sql.execute('SELECT e.source_id,e.source_type,hex(v.embedding) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid ORDER BY e.id').fetchall()
                    fts=sql.execute("SELECT source_id FROM lite_embeddings_fts WHERE lite_embeddings_fts MATCH 'paragraph OR foreign' ORDER BY source_id").fetchall()
                    assert index and vectors and fts
                    chunks=sql.execute('SELECT * FROM chunks ORDER BY id').fetchall()
                    cursor=sql.execute('SELECT * FROM knowledges ORDER BY id');keep=[i for i,column in enumerate(cursor.description) if column[0] not in ('folder_path','updated_at')]
                    docs=[tuple(row[i] for i in keep) for row in cursor.fetchall()]
                    quota=sql.execute('SELECT id,storage_used FROM tenants ORDER BY id').fetchall()
                    jobs=sql.execute('SELECT * FROM java_task_jobs ORDER BY id').fetchall()
                    references=sql.execute('SELECT physical_path FROM resources ORDER BY handle').fetchall()
                sources=[(folder/'files'/ref[0].removeprefix('local://')).read_bytes() for ref in references];assert sources and all(sources)
                return index,vectors,fts,chunks,docs,quota,jobs,sources,len(calls),len(chat_calls)
            before=folder_snapshot();path='/api/v1/knowledge-bases/kb/knowledge/folders'
            with sqlite3.connect(database) as sql:sql.execute("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=1")
            tree=folder_http('GET',path,'owner');assert tree['data']=={'root_document_count':0,'total_document_count':2,'folders':[{'path':'folder','name':'folder','document_count':2,'total_count':2}]},tree
            renamed=folder_http('PUT',path,'owner',{'from':'folder','to':'renamed'});assert renamed=={'success':True,'data':{'moved_count':2,'folder_path':'renamed'}},renamed
            folder_http('POST','/api/v1/knowledge/folder','owner',{},403)
            with sqlite3.connect(database) as sql:sql.execute("UPDATE tenant_members SET role='contributor' WHERE user_id='foreign'")
            documents=[document for _,document,_ in ids]
            moved=folder_http('POST','/api/v1/knowledge/folder','foreign',{'kb_id':'kb','knowledge_ids':[' '+documents[0]+' ']+documents,'folder_path':' ./共享目录./子目录 '});assert moved=={'success':True,'data':{'moved_count':2,'folder_path':'共享目录/子目录'}},moved
            renamed=folder_http('PUT',path,'foreign',{'from':'共享目录','to':'最终目录'});assert renamed=={'success':True,'data':{'moved_count':2,'folder_path':'最终目录'}},renamed
            tree=folder_http('GET',path,'foreign');assert tree['data']=={'root_document_count':0,'total_document_count':2,'folders':[{'path':'最终目录','name':'最终目录','document_count':0,'total_count':2,'children':[{'path':'最终目录/子目录','name':'子目录','document_count':2,'total_count':2}]}]},tree
            with sqlite3.connect(database) as sql:assert sql.execute("SELECT tenant_id,folder_path FROM knowledges ORDER BY id").fetchall()==[(1,'最终目录/子目录')]*2
            assert folder_snapshot()==before,'Folder HTTP changes only folder_path/updated_at; all sources/index rows and vectors/FTS/chunks/quota/jobs/model calls retained'
        if args.chunk_questions or args.chunk_regenerate:
            def question_http(method,target,actor,payload=None,expected=200):
                request=urllib.request.Request(base+target,data=json.dumps(payload or {},ensure_ascii=False).encode(),headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json','Accept-Language':'en-US'},method=method)
                try:
                    with urllib.request.urlopen(request,timeout=15) as response:
                        result=json.load(response);assert response.status==expected,(response.status,result);return result
                except urllib.error.HTTPError as error:
                    result=json.load(error);assert error.code==expected,(error.code,result,str(log));return result
            def question_retained():
                with sqlite3.connect(database) as sql:
                    rows=sql.execute('SELECT id,content,content_revision,index_status,source_locators,is_enabled,last_editor_id FROM chunks ORDER BY id').fetchall()
                    docs=sql.execute('SELECT id,tenant_id,parse_status,pending_subtasks_count,file_path,file_hash,storage_size,summary_status,description FROM knowledges ORDER BY id').fetchall()
                    jobs=sql.execute('SELECT * FROM java_task_jobs ORDER BY id').fetchall()
                    quota=sql.execute('SELECT id,storage_used FROM tenants ORDER BY id').fetchall()
                    refs=sql.execute('SELECT physical_path FROM resources ORDER BY handle').fetchall()
                sources=[(folder/'files'/ref[0].removeprefix('local://')).read_bytes() for ref in refs];assert sources and all(sources)
                return rows,docs,jobs,quota,sources
            before=question_retained()
            for actor,document,_ in ids:
                with sqlite3.connect(database) as sql:
                    if actor=='owner':sql.execute("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=1")
                    chunk=sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='text' ORDER BY chunk_index LIMIT 1",(document,)).fetchone()[0]
                target='/api/v1/chunks/by-id/'+chunk+'/questions';embeddings_before,chats_before=len(calls),len(chat_calls)
                previous=set()
                for attempt in range(1,3):
                    if args.chunk_regenerate:
                        items=question_http('POST',target+'/regenerate',actor)['data'];assert len(items)==3,items
                    else:
                        payload={'question':'  questionaliassmokemarker'+actor+' first 中文  '} if attempt==1 else {'question_id':question_id,'question':'questionaliassmokemarker'+actor+' updated 中文'}
                        items=[question_http('PUT',target,actor,payload)['data']]
                    aliases=set()
                    for item in items:
                        question_id=item['id'];assert str(uuid.UUID(question_id))==question_id and item['content_revision']==0,item
                        alias=chunk+'-q'+hashlib.sha256(question_id.encode()).digest()[:12].hex();aliases.add(alias)
                        with sqlite3.connect(database) as sql:
                            sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                            row=sql.execute('SELECT source_id,chunk_id,knowledge_id,knowledge_base_id,content FROM lite_embeddings WHERE source_id=?',(alias,)).fetchone();assert row[:4]==(alias,chunk,document,'kb') and item['question'] in row[4],row
                            vector=sql.execute('SELECT hex(v.embedding) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.source_id=?',(alias,)).fetchone();assert vector==('0000803F0000000000000000',),vector
                    assert len(aliases)==len(items)
                    if args.chunk_regenerate:assert aliases.isdisjoint(previous)
                    with sqlite3.connect(database) as sql:
                        assert sql.execute('SELECT COUNT(*) FROM lite_embeddings WHERE chunk_id=?',(chunk,)).fetchone()==(len(items)+1,)
                        matches=sql.execute("SELECT e.source_id FROM lite_embeddings_fts JOIN lite_embeddings e ON e.id=lite_embeddings_fts.rowid WHERE lite_embeddings_fts MATCH ? AND e.chunk_id=? AND e.source_id<>?",('document' if args.chunk_regenerate else 'questionaliassmokemarker'+actor,chunk,chunk)).fetchall();assert set(row[0] for row in matches)==aliases,matches
                        for old in previous-aliases:assert sql.execute('SELECT COUNT(*) FROM lite_embeddings WHERE source_id=?',(old,)).fetchone()==(0,)
                        assert sql.execute('SELECT COUNT(*) FROM chunk_revisions WHERE chunk_id=?',(chunk,)).fetchone()==(0,)
                    assert question_retained()==before and len(calls)==embeddings_before+attempt and len(chat_calls)==chats_before+(attempt if args.chunk_regenerate else 0)
                    previous=aliases
                if args.chunk_questions:
                    with sqlite3.connect(database) as sql:physical=sql.execute('SELECT * FROM lite_embeddings WHERE source_id=?',(chunk,)).fetchall()
                    question_http('DELETE',target,actor,{'question_id':question_id})
                    with sqlite3.connect(database) as sql:
                        assert sql.execute('SELECT * FROM lite_embeddings WHERE source_id=?',(chunk,)).fetchall()==physical
                        assert sql.execute('SELECT COUNT(*) FROM lite_embeddings WHERE chunk_id=?',(chunk,)).fetchone()==(1,)
                        assert sql.execute("SELECT COUNT(*) FROM lite_embeddings_fts JOIN lite_embeddings e ON e.id=lite_embeddings_fts.rowid WHERE lite_embeddings_fts MATCH ? AND e.chunk_id=?",('questionaliassmokemarker'+actor,chunk)).fetchone()==(0,)
                        assert not json.loads(sql.execute('SELECT metadata FROM chunks WHERE id=?',(chunk,)).fetchone()[0]).get('generated_questions')
                    assert question_retained()==before and len(calls)==embeddings_before+2 and len(chat_calls)==chats_before
        if args.chunk_edit:
            def edit_http(method, target, actor, payload=None, expected=200):
                request=urllib.request.Request(base+target,data=None if payload is None else json.dumps(payload,ensure_ascii=False).encode(),headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json','Accept-Language':'en-US'},method=method)
                try:
                    with urllib.request.urlopen(request,timeout=15) as response:
                        result=json.load(response);assert response.status==expected,(response.status,result);return result
                except urllib.error.HTTPError as error:
                    result=json.load(error);assert error.code==expected,(error.code,result,str(log));return result
            def edit_source_snapshot():
                with sqlite3.connect(database) as sql:
                    quota=sql.execute('SELECT id,storage_used FROM tenants ORDER BY id').fetchall()
                    docs=sql.execute('SELECT id,tenant_id,parse_status,pending_subtasks_count,file_path,file_hash,storage_size FROM knowledges ORDER BY id').fetchall()
                    refs=sql.execute('SELECT physical_path FROM resources ORDER BY handle').fetchall()
                sources=[(folder/'files'/ref[0].removeprefix('local://')).read_bytes() for ref in refs]
                assert sources and all(sources)
                return quota,docs,sources
            before=edit_source_snapshot()
            for actor,document,_ in ids:
                with sqlite3.connect(database) as sql:
                    if actor=='owner':sql.execute("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=1")
                    chunk,original=sql.execute("SELECT id,content FROM chunks WHERE knowledge_id=? AND chunk_type='text' AND deleted_at IS NULL ORDER BY chunk_index LIMIT 1",(document,)).fetchone()
                    summary_id=sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",(document,)).fetchone()[0]
                target='/api/v1/chunks/'+document+'/'+chunk
                replacement='chunkeditmarker'+actor+' Updated evidence 中文'
                for revision,method,payload,wanted in ((0,'PUT',{'content':replacement,'expected_revision':0},replacement),(1,'POST',{'revision':0,'expected_revision':1},original)):
                    with sqlite3.connect(database) as sql:
                        jobs_before=sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()[0]
                    embeddings_before,chats_before=len(calls),len(chat_calls)
                    result=edit_http(method,target+('/revert' if method=='POST' else ''),actor,payload)
                    assert result['success'] and result['data']['content']==wanted and result['data']['content_revision']==revision+1 and result['data']['index_status']=='ready',result
                    deadline=time.monotonic()+20
                    while True:
                        with sqlite3.connect(database) as sql:
                            jobs=sql.execute('SELECT status FROM java_task_jobs').fetchall()
                        if len(jobs)==jobs_before+1 and all(row==('succeeded',) for row in jobs):break
                        assert process.poll() is None and time.monotonic()<deadline,(jobs,str(log));time.sleep(.05)
                    with sqlite3.connect(database) as sql:
                        sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                        current=sql.execute('SELECT tenant_id,content,source_content,source_locators,content_revision,last_editor_id,index_status FROM chunks WHERE id=?',(chunk,)).fetchone()
                        assert current==(1,wanted,original,None,revision+1,actor,'ready'),current
                        indexes=sql.execute('SELECT source_id,chunk_id,knowledge_id,knowledge_base_id,content FROM lite_embeddings WHERE chunk_id=?',(chunk,)).fetchall()
                        assert len(indexes)==1 and indexes[0][:4]==(chunk,chunk,document,'kb') and indexes[0][4].endswith(wanted),indexes
                        vectors=sql.execute('SELECT hex(v.embedding) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.chunk_id=?',(chunk,)).fetchall()
                        assert vectors==[('0000803F0000000000000000',)],vectors
                        matches=sql.execute("SELECT e.source_id FROM lite_embeddings_fts JOIN lite_embeddings e ON e.id=lite_embeddings_fts.rowid WHERE lite_embeddings_fts MATCH ?",('"chunkeditmarker'+actor+'"',)).fetchall()
                        assert (chunk,) in matches if revision==0 else (chunk,) not in matches,matches
                        assert sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",(document,)).fetchone()==(summary_id,)
                        assert sql.execute('SELECT summary_status,parse_status,pending_subtasks_count FROM knowledges WHERE id=?',(document,)).fetchone()==('completed','completed',0)
                        task=sql.execute("SELECT tenant_id,queue,max_attempts,timeout_millis,payload FROM java_task_jobs WHERE type='summary:generation' AND json_extract(payload,'$.knowledge_id')=? AND json_extract(payload,'$.refresh')=1 ORDER BY created_at DESC LIMIT 1",(document,)).fetchone()
                        assert task[:4]==(1,'summary',4,1800000) and json.loads(task[4])=={'tenant_id':1,'knowledge_base_id':'kb','knowledge_id':document,'language':'en-US','attempt':1,'refresh':True},task
                    assert len(calls)==embeddings_before+2 and len(chat_calls)==chats_before+1,(calls,chat_calls)
                    assert edit_source_snapshot()==before
                versions=edit_http('GET',target+'/revisions',actor)['data']
                assert [(row['revision'],row['content']) for row in versions]==[(1,replacement),(0,original)],versions
                assert versions[0]['editor_id']==actor and all(row['tenant_id']==1 and row['edit_source']=='user' for row in versions),versions
                with sqlite3.connect(database) as sql:jobs_before=sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()[0]
                counts_before=len(calls),len(chat_calls)
                edit_http('PUT',target,actor,{'content':'stale overwrite','expected_revision':1},409)
                noop=edit_http('PUT',target,actor,{'content':original,'expected_revision':2})
                assert noop['data']['content_revision']==2 and noop['data']['content']==original,noop
                with sqlite3.connect(database) as sql:
                    assert sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()==(jobs_before,)
                    assert sql.execute('SELECT COUNT(*) FROM chunk_revisions WHERE chunk_id=?',(chunk,)).fetchone()==(2,)
                assert (len(calls),len(chat_calls))==counts_before and edit_source_snapshot()==before
            expected_jobs+=2*len(ids)
        if args.reparse:
            def reparse_http(path, actor, body):
                request=urllib.request.Request(base+path,data=json.dumps(body).encode(),method='POST',headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json','Accept-Language':'en-US'})
                try:
                    with urllib.request.urlopen(request,timeout=10) as response:
                        result=json.load(response);assert response.status==200 and result['success'],result;return result['data']
                except urllib.error.HTTPError as error:
                    raise AssertionError((error.code,error.read().decode(),str(log))) from error
            def retained_sources():
                with sqlite3.connect(database) as sql:
                    docs=sql.execute('SELECT id,tenant_id,file_path,file_hash,file_name,file_type,storage_size FROM knowledges ORDER BY id').fetchall()
                    quota=sql.execute('SELECT id,storage_used FROM tenants ORDER BY id').fetchall()
                    refs=sql.execute('SELECT handle,physical_path FROM resources ORDER BY handle').fetchall()
                files=[(handle,(folder/'files'/path.removeprefix('local://')).read_bytes()) for handle,path in refs]
                return docs,quota,files
            before=retained_sources();attempts={document:1 for _,document,_ in ids}
            with sqlite3.connect(database) as sql:
                sql.execute("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=1")
            own=next(document for actor,document,_ in ids if actor=='owner')
            for actor,selection in [('owner',[own]),('foreign',[document for _,document,_ in ids])]:
                with sqlite3.connect(database) as sql:
                    old={document:{row[0] for row in sql.execute('SELECT id FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL',(document,))} for document in selection}
                batch=actor=='foreign'
                data=reparse_http('/api/v1/knowledge/batch-reparse' if batch else '/api/v1/knowledge/'+own+'/reparse',actor,
                                  {'kb_id':'kb','ids':selection,'process_config':{'summary_enabled':False}} if batch else {'process_config':{'summary_enabled':False}})
                if batch:assert data['reparse_count']==len(selection) and uuid.UUID(data['task_id']).version==4,data
                expected_jobs+=len(selection)*2+int(batch)
                for document in selection:attempts[document]+=1
                deadline=time.monotonic()+30
                while True:
                    with sqlite3.connect(database) as sql:
                        jobs=sql.execute('SELECT type,status,last_error FROM java_task_jobs').fetchall()
                        states=sql.execute('SELECT id,parse_status,pending_subtasks_count FROM knowledges').fetchall()
                    if len(jobs)==expected_jobs and all(row[1]=='succeeded' for row in jobs) and all(row[1:] == ('completed',0) for row in states):break
                    assert process.poll() is None and time.monotonic()<deadline,(jobs,states,str(log));time.sleep(.05)
                with sqlite3.connect(database) as sql:
                    sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                    for document in selection:
                        assert sql.execute('SELECT MAX(attempt) FROM knowledge_processing_spans WHERE knowledge_id=?',(document,)).fetchone()==(attempts[document],)
                        current={row[0] for row in sql.execute('SELECT id FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL',(document,))}
                        assert current and current.isdisjoint(old[document]),(current,old[document])
                        physical=sql.execute('SELECT e.chunk_id,hex(v.embedding) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.knowledge_id=?',(document,)).fetchall()
                        assert {row[0] for row in physical}==current and all(row[1]=='0000803F0000000000000000' for row in physical),physical
                        matches=sql.execute("SELECT e.chunk_id FROM lite_embeddings_fts JOIN lite_embeddings e ON e.id=lite_embeddings_fts.rowid WHERE lite_embeddings_fts MATCH 'paragraph OR caller' AND e.knowledge_id=?",(document,)).fetchall()
                        assert {row[0] for row in matches}==current,matches
                        audit=sql.execute("SELECT tenant_id,actor_user_id,actor_role,details FROM audit_logs WHERE action='knowledge.reparse_started' AND target_id=? ORDER BY id DESC LIMIT 1",(document,)).fetchone()
                        assert audit[:3]==(1,actor,'contributor' if batch else 'viewer'),audit
                        if batch:
                            details=json.loads(audit[3]);assert details['task_id']==data['task_id'] and details['trigger']=='user' and details['processing_status']=='pending',details
                    if batch:
                        wrapper=sql.execute('SELECT tenant_id,queue,max_attempts,timeout_millis,payload FROM java_task_jobs WHERE id=?',(data['task_id'],)).fetchone()
                        assert wrapper[:4]==(1,'low',4,3600000),wrapper
                        payload=json.loads(wrapper[4]);assert payload['initiator']['user_id']==actor and payload['initiator']['role']=='contributor' and payload['knowledge_ids']==selection,payload
                assert retained_sources()==before
            assert len(calls)==5 and not chat_calls,(len(calls),len(chat_calls))
            print(json.dumps({'reparse':True,'single_creator_role':'viewer','batch_shared_actor':'foreign','attempts':attempts,'jobs':expected_jobs,'physical_vec_fts_files_quota_audit_verified':True}))
        if args.update_knowledge or args.regenerate_summary:
            def source_snapshot():
                with sqlite3.connect(database) as sql:
                    sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                    text=sql.execute("SELECT c.* FROM chunks c WHERE c.chunk_type<>'summary' ORDER BY c.id").fetchall()
                    indexes=sql.execute("SELECT e.* FROM lite_embeddings e JOIN chunks c ON c.id=e.source_id WHERE c.chunk_type<>'summary' ORDER BY e.id").fetchall()
                    vectors=sql.execute("SELECT e.source_id,e.source_type,hex(v.embedding) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid JOIN chunks c ON c.id=e.source_id WHERE c.chunk_type<>'summary' ORDER BY e.id").fetchall()
                    fts=sql.execute("SELECT e.source_id,t.content FROM lite_embeddings_fts t JOIN lite_embeddings e ON e.id=t.rowid JOIN chunks c ON c.id=e.source_id WHERE c.chunk_type<>'summary' ORDER BY e.id").fetchall()
                    quota=sql.execute('SELECT id,storage_used FROM tenants ORDER BY id').fetchall()
                    doc=sql.execute('SELECT id,parse_status,pending_subtasks_count,folder_path,file_path,file_hash,storage_size FROM knowledges ORDER BY id').fetchall()
                    refs=sql.execute('SELECT physical_path FROM resources ORDER BY handle').fetchall()
                sources=[(folder/'files'/ref[0].removeprefix('local://')).read_bytes() for ref in refs];assert text and indexes and vectors and fts and sources
                return text,indexes,vectors,fts,quota,doc,sources
        if args.update_knowledge:
            def update_http(document, actor, payload, expected=200):
                request=urllib.request.Request(base+'/api/v1/knowledge/'+document,data=json.dumps(payload,ensure_ascii=False).encode(),headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json','Accept-Language':'en-US'},method='PUT')
                try:
                    with urllib.request.urlopen(request,timeout=10) as response:
                        result=json.load(response);assert response.status==expected,(response.status,result);return result
                except urllib.error.HTTPError as error:
                    result=json.load(error);assert error.code==expected,(error.code,result,str(log));return result
            before=source_snapshot()
            for actor,document,_ in ids:
                with sqlite3.connect(database) as sql:
                    if actor=='owner':sql.execute("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=1")
                    old_summary=sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",(document,)).fetchone()[0]
                    jobs_before=sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()[0]
                chats_before, embeddings_before=len(chat_calls),len(calls)
                first=update_http(document,actor,{'title':'Updated title '+actor});assert first['data']['title']=='Updated title '+actor and first['data']['description']=='Document summary 中文',first
                with sqlite3.connect(database) as sql:assert sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()==(jobs_before,)
                assert len(chat_calls)==chats_before and len(calls)==embeddings_before
                refresh_started.clear();refresh_release.clear()
                changed=update_http(document,actor,{'custom_metadata':{'reviewer':'SMOKE REFRESH '+actor}});assert changed['data']['tenant_id']==1 and changed['data']['summary_status']=='pending',changed
                assert refresh_started.wait(10),('Native refresh did not reach real model',str(log))
                with sqlite3.connect(database) as sql:
                    jobs=sql.execute("SELECT tenant_id,queue,max_attempts,timeout_millis,payload FROM java_task_jobs WHERE type='summary:generation' AND json_extract(payload,'$.knowledge_id')=? AND json_extract(payload,'$.refresh')=1",(document,)).fetchall()
                    assert len(jobs)==1 and jobs[0][:4]==(1,'summary',4,1800000),jobs
                    assert json.loads(jobs[0][4])=={'tenant_id':1,'knowledge_base_id':'kb','knowledge_id':document,'language':'en-US','attempt':1,'refresh':True},jobs
                assert source_snapshot()==before
                refresh_release.set();deadline=time.monotonic()+15
                while True:
                    with sqlite3.connect(database) as sql:
                        job=sql.execute("SELECT status FROM java_task_jobs WHERE type='summary:generation' AND json_extract(payload,'$.knowledge_id')=? AND json_extract(payload,'$.refresh')=1",(document,)).fetchone()
                    if job==('succeeded',):break
                    assert process.poll() is None and time.monotonic()<deadline,(job,str(log));time.sleep(.05)
                with sqlite3.connect(database) as sql:
                    assert sql.execute("SELECT summary_status,parse_status,pending_subtasks_count FROM knowledges WHERE id=?",(document,)).fetchone()==('completed','completed',0)
                    assert sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",(document,)).fetchone()==(old_summary,)
                    assert sql.execute('SELECT COUNT(*) FROM lite_embeddings WHERE source_id=?',(old_summary,)).fetchone()==(1,)
                assert source_snapshot()==before and len(chat_calls)==chats_before+1 and len(calls)==embeddings_before+1
            expected_jobs+=len(ids)
        if args.regenerate_summary:
            def regenerate_http(document,actor,expected=200):
                request=urllib.request.Request(base+'/api/v1/knowledge/'+document+'/regenerate-summary',data=b'ignored invalid JSON',headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json','Accept-Language':'en-US'},method='POST')
                try:
                    with urllib.request.urlopen(request,timeout=15) as response:
                        result=json.load(response);assert response.status==expected,(response.status,result);return result
                except urllib.error.HTTPError as error:
                    result=json.load(error);assert error.code==expected,(error.code,result,str(log));return result
            before=source_snapshot()
            for actor,document,_ in ids:
                with sqlite3.connect(database) as sql:
                    if actor=='owner':sql.execute("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=1")
                    old_summary=sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",(document,)).fetchone()[0]
                    jobs_before=sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()[0]
                    sql.execute("UPDATE knowledges SET summary_status='none' WHERE id=?",(document,))
                chats_before,embeddings_before=len(chat_calls),len(calls)
                result=regenerate_http(document,actor)
                assert result['data']['tenant_id']==1 and result['data']['summary_status']=='completed' and result['data']['description']=='Document summary 中文',result
                assert len(chat_calls)==chats_before+1 and len(calls)==embeddings_before+1
                with sqlite3.connect(database) as sql:
                    assert sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()==(jobs_before,)
                    assert sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",(document,)).fetchone()==(old_summary,)
                    assert sql.execute("SELECT content FROM lite_embeddings WHERE source_id=?",(old_summary,)).fetchone()[0].endswith('# Summary\nDocument profile\n\nDocument summary 中文')
                assert source_snapshot()==before
                refresh_started.clear();refresh_release.clear();public_summary_async.set()
                try:
                    result=regenerate_http(document,actor)
                    assert result['data']['tenant_id']==1 and result['data']['summary_status'] in ('pending','processing'),result
                    assert refresh_started.wait(10),('Native refresh did not reach real model',str(log))
                    with sqlite3.connect(database) as sql:
                        jobs=sql.execute("SELECT tenant_id,queue,max_attempts,timeout_millis,payload FROM java_task_jobs WHERE type='summary:generation' AND json_extract(payload,'$.knowledge_id')=? AND json_extract(payload,'$.refresh')=1",(document,)).fetchall()
                        assert len(jobs)==1 and jobs[0][:4]==(1,'summary',4,1800000),jobs
                        assert json.loads(jobs[0][4])=={'tenant_id':1,'knowledge_base_id':'kb','knowledge_id':document,'language':'en-US','attempt':1,'refresh':True},jobs
                    assert source_snapshot()==before
                finally:
                    refresh_release.set();public_summary_async.clear()
                deadline=time.monotonic()+15
                while True:
                    with sqlite3.connect(database) as sql:
                        job=sql.execute("SELECT status FROM java_task_jobs WHERE type='summary:generation' AND json_extract(payload,'$.knowledge_id')=? AND json_extract(payload,'$.refresh')=1",(document,)).fetchone()
                    if job==('succeeded',):break
                    assert process.poll() is None and time.monotonic()<deadline,(job,str(log));time.sleep(.05)
                with sqlite3.connect(database) as sql:
                    assert sql.execute("SELECT summary_status,parse_status,pending_subtasks_count FROM knowledges WHERE id=?",(document,)).fetchone()==('completed','completed',0)
                    assert sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='summary'",(document,)).fetchone()==(old_summary,)
                    sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                    assert sql.execute("SELECT COUNT(*) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.source_id=?",(old_summary,)).fetchone()==(1,)
                    assert sql.execute("SELECT hex(v.embedding) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.source_id=?",(old_summary,)).fetchone()==('0000803F0000000000000000',)
                    assert sql.execute("SELECT COUNT(*) FROM lite_embeddings_fts JOIN lite_embeddings e ON e.id=lite_embeddings_fts.rowid WHERE lite_embeddings_fts MATCH 'summary' AND e.source_id=?",(old_summary,)).fetchone()==(1,)
                    indexes_before=sql.execute('SELECT * FROM lite_embeddings ORDER BY id').fetchall()
                    sql.execute("CREATE TRIGGER reject_public_summary BEFORE INSERT ON java_task_jobs WHEN NEW.type='summary:generation' BEGIN SELECT RAISE(ABORT,'controlled public summary rejection');END")
                failure=regenerate_http(document,actor,400);assert failure['error']['code']==1000,failure
                with sqlite3.connect(database) as sql:
                    assert sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()==(jobs_before+1,)
                    assert sql.execute("SELECT summary_status FROM knowledges WHERE id=?",(document,)).fetchone()==('failed',)
                    assert sql.execute('SELECT * FROM lite_embeddings ORDER BY id').fetchall()==indexes_before
                    sql.execute('DROP TRIGGER reject_public_summary')
                assert source_snapshot()==before and len(chat_calls)==chats_before+2 and len(calls)==embeddings_before+2
            expected_jobs+=len(ids)
        if args.file_response:
            def original(actor, document, operation, extra=None):
                headers = {'Authorization': 'Bearer ' + tokens[actor]}
                headers.update(extra or {})
                request = urllib.request.Request(base + '/api/v1/knowledge/' + document + '/' + operation, headers=headers)
                try:
                    response = urllib.request.urlopen(request, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    return response.status, response.headers, response.read()

            def batch_original(actor, selection, extra=None, key=None):
                headers = {'Authorization': 'Bearer ' + tokens[actor], 'Content-Type': 'application/json'}
                if key:
                    headers = {'X-API-Key': key, 'Content-Type': 'application/json'}
                headers.update(extra or {})
                request = urllib.request.Request(base + '/api/v1/knowledge-bases/kb/knowledge/batch-download', data=json.dumps({'ids': selection}).encode(), headers=headers, method='POST')
                try:
                    response = urllib.request.urlopen(request, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    return response.status, response.headers, response.read()

            with sqlite3.connect(database) as sql:
                before_documents = sql.execute('SELECT * FROM knowledges ORDER BY id').fetchall()
                before_quota = sql.execute('SELECT id,storage_used FROM tenants ORDER BY id').fetchall()
                before_jobs = sql.execute('SELECT id,status,attempts FROM java_task_jobs ORDER BY id').fetchall()
                paths = sql.execute('SELECT file_path FROM knowledges ORDER BY id').fetchall()
                assert all(path[0].startswith('resource://') for path in paths), paths
            for source_actor, document, _ in ids:
                content = next(data for actor, _, data in uploads if actor == source_actor)
                for actor in ('owner', 'foreign'):
                    for operation in ('download', 'preview'):
                        status, headers, data = original(actor, document, operation)
                        assert status == 200 and data == content, (actor, operation, status, data)
                        assert headers['Accept-Ranges'] == 'bytes' and headers['Cache-Control'] == 'private, no-store'
                        assert headers['X-Content-Type-Options'] == 'nosniff'
                        assert headers['Content-Disposition'].startswith('attachment;' if operation == 'download' else 'inline;')
                status, headers, data = original('foreign', document, 'preview', {'Range': 'bytes=0-4'})
                assert status == 206 and data == content[:5] and headers['Content-Range'] == f'bytes 0-4/{len(content)}'
                status, headers, data = original('foreign', document, 'preview', {'Range': 'bytes=99999-'})
                assert status == 416 and data == b'invalid range: failed to overlap\n' and headers['Content-Range'] == f'bytes */{len(content)}'
                assert headers.get('Cache-Control') is None
                status, headers, data = original('owner', document, 'preview', {'If-None-Match': '*'})
                assert status == 304 and not data and headers.get('Content-Type') is None
            selection = [document for _, document, _ in reversed(ids)]
            expected_sources = [next(data for source_actor, _, data in uploads if source_actor == actor) for actor, _, _ in reversed(ids)]
            for actor in ('owner', 'foreign'):
                status, headers, data = batch_original(actor, selection + selection)
                assert status == 200 and headers['Content-Type'] == 'application/zip', (actor, status, data)
                assert headers['Accept-Ranges'] == 'bytes' and headers['Cache-Control'] == 'private, no-store'
                assert headers['X-Content-Type-Options'] == 'nosniff' and headers.get('Content-Description') is None
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    entries = archive.infolist()
                    assert len(entries) == 2 and all(entry.compress_type == zipfile.ZIP_STORED and entry.filename.startswith('folder/') for entry in entries)
                    assert [archive.read(entry) for entry in entries] == expected_sources
                partial = batch_original(actor, selection, {'Range': 'bytes=0-3', 'If-Range': '"unknown"'})
                assert partial[0] == 206 and partial[2] == b'PK\x03\x04'
                assert batch_original(actor, selection, {'If-None-Match': '*'})[0] == 412
                invalid = batch_original(actor, selection, {'Range': 'bytes=999999-'})
                assert invalid[0] == 416 and invalid[1].get('Cache-Control') is None
            assert batch_original('owner', selection + ['absent'])[0] == 404

            def collection(actor, endpoint, query, key=None):
                headers = {'Authorization': 'Bearer ' + tokens[actor]} if key is None else {'X-API-Key': key}
                request = urllib.request.Request(base + '/api/v1/knowledge/' + endpoint + '?' + urllib.parse.urlencode(query, doseq=True), headers=headers)
                try:
                    response = urllib.request.urlopen(request, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    return response.status, json.loads(response.read())

            selected_ids = set(selection)
            for actor in ('owner', 'foreign'):
                status, result = collection(actor, 'batch', {'ids': selection + selection + ['absent']})
                assert status == 200 and {row['id'] for row in result['data']} == selected_ids
                assert len(result['data']) == 2 and all(row['tenant_id'] == 1 for row in result['data'])
                status, result = collection(actor, 'batch', {'ids': selection, 'kb_id': 'kb'})
                assert status == 200 and {row['id'] for row in result['data']} == selected_ids
                status, first = collection(actor, 'search', {'recent': 'true', 'limit': 1})
                assert status == 200 and first['total'] == 2 and first['has_more'] and len(first['data']) == 1
                status, second = collection(actor, 'search', {'query': '.txt', 'offset': 1, 'limit': 1})
                assert status == 200 and second['total'] == 2 and not second['has_more'] and len(second['data']) == 1
                assert {first['data'][0]['id'], second['data'][0]['id']} == selected_ids
                assert collection(actor, 'search', {})[0] == 400
                assert collection(actor, 'batch', {})[0] == 400
            for full, capability, scope, count, code in ((False, 'ingest', 'kb', 0, 403), (False, 'retrieve', 'other', 0, 200), (True, 'retrieve', 'other', 0, 200), (False, 'retrieve', 'kb', 2, 200)):
                key = 'collection-read-' + secrets.token_hex(16)
                with sqlite3.connect(database) as sql:
                    sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Isolated collection reader',?,?,?,?,?)", (hashlib.sha256(key.encode()).hexdigest(), key, full, json.dumps([capability]), json.dumps([scope])))
                for endpoint, query in (('batch', {'ids': selection}), ('search', {'recent': 'true'})):
                    status, result = collection('owner', endpoint, query, key=key)
                    assert status == code, (endpoint, status, result)
                    if code == 200:
                        assert len(result['data']) == count, (endpoint, result)
                with sqlite3.connect(database) as sql:
                    sql.execute('DELETE FROM tenant_api_keys WHERE key_hash=?', (hashlib.sha256(key.encode()).hexdigest(),))
            def batch_tags(actor, payload, key=None):
                headers = {'Authorization': 'Bearer ' + tokens[actor], 'Content-Type': 'application/json'} if key is None else {'X-API-Key': key, 'Content-Type': 'application/json'}
                request = urllib.request.Request(base + '/api/v1/knowledge/tags', data=json.dumps(payload).encode(), headers=headers, method='PUT')
                try:
                    response = urllib.request.urlopen(request, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    return response.status, json.loads(response.read())

            with sqlite3.connect(database) as sql:
                relations_before = sql.execute('SELECT * FROM knowledge_tag_relations ORDER BY knowledge_id,tag_id').fetchall()
                sql.execute("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,seq_id) VALUES('batch-tags-control',1,'kb','Control',(SELECT COALESCE(MAX(seq_id),0)+1 FROM knowledge_tags))")
            tag_updates = {'kb_id': 'kb', 'updates': {document: ['batch-tags-control', 'batch-tags-control', ''] for document in selection}}
            assert batch_tags('owner', tag_updates) == (200, {'success': True})
            with sqlite3.connect(database) as sql:
                assert sql.execute("SELECT knowledge_id,tag_id FROM knowledge_tag_relations WHERE tag_id='batch-tags-control' ORDER BY knowledge_id").fetchall() == sorted((document, 'batch-tags-control') for document in selection)
            status, denied = batch_tags('foreign', {'updates': {selection[0]: None}})
            assert status == 404 and denied['error']['message'] == 'knowledge base not found', (status, denied)
            with sqlite3.connect(database) as sql:
                sql.execute("UPDATE tenant_members SET role='admin' WHERE user_id='foreign' AND tenant_id=2")
            assert batch_tags('foreign', {'updates': {selection[0]: None}}) == (200, {'success': True})
            for full, capability, scope, code in ((False, 'retrieve', 'kb', 403), (True, 'ingest', 'other', 403), (False, 'ingest', 'kb', 200)):
                key = 'batch-tags-' + secrets.token_hex(16)
                with sqlite3.connect(database) as sql:
                    sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Isolated batch tag writer',?,?,?,?,?)", (hashlib.sha256(key.encode()).hexdigest(), key, full, json.dumps([capability]), json.dumps([scope])))
                assert batch_tags('owner', tag_updates, key=key)[0] == code
                with sqlite3.connect(database) as sql:
                    sql.execute('DELETE FROM tenant_api_keys WHERE key_hash=?', (hashlib.sha256(key.encode()).hexdigest(),))
            status, invalid = batch_tags('owner', {'kb_id': 'kb', 'updates': {selection[0]: ['missing-tag'], selection[1]: []}})
            assert status == 400 and invalid['error']['message'] == '标签 missing-tag 不存在'
            with sqlite3.connect(database) as sql:
                assert sql.execute("SELECT COUNT(*) FROM knowledge_tag_relations WHERE tag_id='batch-tags-control'").fetchone()[0] == 2
            assert batch_tags('foreign', {'kb_id': 'kb', 'updates': {document: [] for document in selection}}) == (200, {'success': True})
            with sqlite3.connect(database) as sql:
                sql.execute("DELETE FROM knowledge_tags WHERE id='batch-tags-control'")
                assert sql.execute('SELECT * FROM knowledge_tag_relations ORDER BY knowledge_id,tag_id').fetchall() == relations_before
                sql.execute("UPDATE tenant_members SET role='contributor' WHERE user_id='foreign' AND tenant_id=2")
            for full, capability, scope, expected_status in ((False, 'ingest', 'kb', 403), (False, 'retrieve', 'other', 403), (True, 'retrieve', 'other', 403), (False, 'retrieve', 'kb', 200)):
                key = 'batch-file-' + secrets.token_hex(16)
                with sqlite3.connect(database) as sql:
                    sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Isolated batch reader',?,?,?,?,?)", (hashlib.sha256(key.encode()).hexdigest(), key, full, json.dumps([capability]), json.dumps([scope])))
                assert batch_original('owner', selection, key=key)[0] == expected_status
                with sqlite3.connect(database) as sql:
                    sql.execute('DELETE FROM tenant_api_keys WHERE key_hash=?', (hashlib.sha256(key.encode()).hexdigest(),))
            # Gallery reads use the same production jar, auth filter and live projection triggers.
            def gallery(actor, endpoint, query=None, key=None):
                headers = {'X-API-Key': key} if key else {'Authorization': 'Bearer ' + tokens[actor]}
                target = base + '/api/v1/knowledge-bases/kb/' + endpoint
                if query:
                    target += '?' + urllib.parse.urlencode(query)
                try:
                    response = urllib.request.urlopen(urllib.request.Request(target, headers=headers), timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    return response.status, json.load(response)

            with sqlite3.connect(database) as sql:
                gallery_chunks = sql.execute("SELECT id,image_info,updated_at FROM chunks WHERE knowledge_id IN (?,?) ORDER BY knowledge_id,id", tuple(selection)).fetchall()
                assert len(gallery_chunks) >= 2
                owner_prefs = sql.execute("SELECT preferences FROM users WHERE id='owner'").fetchone()[0]
                gallery_state = {table: sql.execute('SELECT * FROM ' + table + ' ORDER BY id').fetchall() for table in ('knowledges', 'tenants', 'java_task_jobs', 'lite_embeddings', 'resources', 'audit_logs')}
                for index, row in enumerate(gallery_chunks[:2]):
                    image_info = [{'url': 'gallery-control-same', 'original_url': 'source-image', 'caption': 'alpha' if index else 'old copy', 'ocr_text': 'OCR 中文', 'attrs': {'attrs': {'contain.text': 'block'}}}]
                    if index:
                        image_info.append({'url': 'gallery-control-other', 'caption': 'beta', 'attrs': {'attrs': {'contain.text': 'none'}}})
                    sql.execute('UPDATE chunks SET image_info=?,updated_at=? WHERE id=?', (json.dumps(image_info), '2026-01-0' + str(index + 1) + ' 00:00:00+00:00', row[0]))
                assert sql.execute('SELECT COUNT(*) FROM chunk_images').fetchone()[0] == 3
                sql.execute("UPDATE users SET preferences=? WHERE id='owner'", (json.dumps({'gallery': {'mode': 'custom', 'status': {'builtin:caption': 'off'}}}),))
            for actor in ('owner', 'foreign'):
                status, result = gallery(actor, 'images', {'sort_by': 'builtin:caption', 'sort_order': 'asc'})
                assert status == 200 and result['total'] == 2 and [image['caption'] for image in result['data']] == ['alpha', 'beta'], result
                assert result['data'][0]['chunk_id'] == gallery_chunks[1][0] and result['data'][0]['attrs']['contain.text'] == 'block'
                with sqlite3.connect(database) as sql:
                    assert result['data'][0]['source_name'] == sql.execute('SELECT title FROM knowledges WHERE id=?', (result['data'][0]['knowledge_id'],)).fetchone()[0]
                assert gallery(actor, 'images', {'keyword': 'OCR 中文'})[1]['total'] == 1
                assert gallery(actor, 'images', {'page': ' 2 ', 'page_size': ' 1 '})[1]['page'] == 2
                assert gallery(actor, 'images', {'attr_rules': json.dumps({'system:contain.text': {'block': 'off'}})})[1]['total'] == 1
            assert gallery('owner', 'gallery-config')[1]['data']['mode'] == 'custom'
            assert gallery('foreign', 'gallery-config')[1]['data']['mode'] == 'all'
            with sqlite3.connect(database) as sql:
                sql.execute("INSERT INTO system_settings(key,value,value_type,category) VALUES('gallery.policy',?,'string','gallery')", (json.dumps(json.dumps({'sources': {'system': False}})),))
            assert gallery('owner', 'gallery-config')[1]['data']['attribute_sources'] == ['builtin']
            with sqlite3.connect(database) as sql:
                sql.execute("UPDATE system_settings SET value=? WHERE key='gallery.policy'", (json.dumps(json.dumps({'sources': {'builtin': False, 'system': False}})),))
            assert gallery('owner', 'gallery-config')[1]['data']['attributes'] == []
            with sqlite3.connect(database) as sql:
                sql.execute("DELETE FROM system_settings WHERE key='gallery.policy'")
                sql.execute("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE knowledge_base_id='kb'")
            assert gallery('foreign', 'images')[0] == 403
            with sqlite3.connect(database) as sql:
                sql.execute("UPDATE kb_shares SET deleted_at=NULL WHERE knowledge_base_id='kb'")
            for full, capability, scope, expected_status in ((False, 'ingest', 'kb', 403), (False, 'retrieve', 'kb', 200), (True, 'retrieve', 'other', 403)):
                key = 'gallery-' + secrets.token_hex(16)
                with sqlite3.connect(database) as sql:
                    sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Isolated gallery reader',?,?,?,?,?)", (hashlib.sha256(key.encode()).hexdigest(), key, full, json.dumps([capability]), json.dumps([scope])))
                for endpoint in ('images', 'gallery-config'):
                    assert gallery('owner', endpoint, key=key)[0] == expected_status
                with sqlite3.connect(database) as sql:
                    sql.execute('DELETE FROM tenant_api_keys WHERE key_hash=?', (hashlib.sha256(key.encode()).hexdigest(),))
            with sqlite3.connect(database) as sql:
                for chunk_id, image_info, updated_at in gallery_chunks:
                    sql.execute('UPDATE chunks SET image_info=?,updated_at=? WHERE id=?', (image_info, updated_at, chunk_id))
                sql.execute("UPDATE users SET preferences=? WHERE id='owner'", (owner_prefs,))
                assert sql.execute('SELECT COUNT(*) FROM chunk_images').fetchone()[0] == 0
                for table, before in gallery_state.items():
                    assert sql.execute('SELECT * FROM ' + table + ' ORDER BY id').fetchall() == before, ('gallery must not mutate', table)
            print(json.dumps({'gallery_config_and_images_real_http_verified': True, 'projection_dedup_search_rules_pagination_runtime_policy_original_user_scope_verified': True, 'gallery_read_only_state_restored': True}))
            document = ids[0][1]
            with sqlite3.connect(database) as sql:
                sql.execute("UPDATE kb_shares SET permission='viewer' WHERE knowledge_base_id='kb'")
                sql.execute("UPDATE tenant_members SET role='viewer' WHERE user_id='owner'")
            for actor in ('owner', 'foreign'):
                assert original(actor, document, 'download')[0] == 403
                assert original(actor, document, 'preview')[0] == 200
                assert batch_original(actor, selection)[0] == 403
            raw_id = document + '\x01'
            with sqlite3.connect(database) as sql:
                sql.execute("UPDATE kb_shares SET permission='editor' WHERE knowledge_base_id='kb'")
                sql.execute("UPDATE tenant_members SET role='owner' WHERE user_id='owner'")
                sql.execute("INSERT INTO knowledge_bases(id,tenant_id,name,type,embedding_model_id,summary_model_id) VALUES('file-response-other',1,'Other','document','','')")
                sql.execute("INSERT INTO knowledges(id,tenant_id,knowledge_base_id,type,title,source,parse_status,file_path,file_name,file_type,metadata) SELECT ?,1,'file-response-other',type,title,source,parse_status,file_path,file_name,file_type,metadata FROM knowledges WHERE id=?", (raw_id, document))
            status, _, data = original('owner', urllib.parse.quote(raw_id, safe=''), 'preview')
            assert status == 200 and data == uploads[0][2], ('encoded control route', status, data)
            for full in (False, True):
                key = 'file-response-' + secrets.token_hex(16)
                with sqlite3.connect(database) as sql:
                    sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Isolated file reader',?,?,?,?,?)", (hashlib.sha256(key.encode()).hexdigest(), key, full, '["retrieve"]', '["file-response-other"]'))
                for operation in ('download', 'preview'):
                    request = urllib.request.Request(base + '/api/v1/knowledge/' + urllib.parse.quote(raw_id, safe='') + '/' + operation, headers={'X-API-Key': key})
                    try:
                        with urllib.request.urlopen(request, timeout=10) as response:
                            raise AssertionError(('sanitized ID escaped key KB allowlist', full, operation, response.status))
                    except urllib.error.HTTPError as error:
                        assert error.code == 403 and json.load(error)['error']['message'] == 'API key scope does not allow one or more knowledge bases'
                with sqlite3.connect(database) as sql:
                    sql.execute('DELETE FROM tenant_api_keys WHERE key_hash=?', (hashlib.sha256(key.encode()).hexdigest(),))
            with sqlite3.connect(database) as sql:
                sql.execute('DELETE FROM knowledges WHERE id=?', (raw_id,))
                sql.execute("DELETE FROM knowledge_bases WHERE id='file-response-other'")
                assert sql.execute('SELECT * FROM knowledges ORDER BY id').fetchall() == before_documents
                assert sql.execute('SELECT id,storage_used FROM tenants ORDER BY id').fetchall() == before_quota
                assert sql.execute('SELECT id,status,attempts FROM java_task_jobs ORDER BY id').fetchall() == before_jobs
            print(json.dumps({'file_response': True, 'documents': len(ids), 'owner_shared_original_bytes_ranges_conditionals_roles_verified': True, 'batch_zip_order_folders_stored_bytes_roles_api_keys_post_conditions_verified': True, 'no_document_quota_job_mutation': True, 'collection_batch_search_scopes_pagination_real_http_verified': True, 'batch_tags_replace_clear_validation_scope_real_http_verified': True}))
        if args.url_import:
            def url_request(actor, suffix, **extra):
                payload={'url':f'http://127.0.0.1:{model.server_port}/'+suffix,'process_config':{'summary_enabled':False},**extra}
                req=urllib.request.Request(base+'/api/v1/knowledge-bases/kb/knowledge/url',data=json.dumps(payload).encode(),
                    headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json','Accept-Language':'en-US'},method='POST')
                with urllib.request.urlopen(req,timeout=10) as response:
                    body=json.load(response);assert response.status==201 and body['success'],body
                    return body['data']
            with sqlite3.connect(database) as sql:
                quota_before=sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()[0]
                sql.execute("UPDATE tenant_members SET role='viewer' WHERE user_id='owner' AND tenant_id=1")
            imported=[]
            for actor,suffix,kind in [('owner','url-smoke-page','url'),('foreign','url-smoke.txt','file_url')]:
                row=url_request(actor,suffix);assert row['tenant_id']==1 and row['type']==kind,row
                imported.append((actor,row['id'],suffix,kind))
            expected_jobs+=4
            deadline=time.monotonic()+30
            while True:
                with sqlite3.connect(database) as sql:
                    jobs=sql.execute('SELECT type,status,last_error FROM java_task_jobs').fetchall()
                    states=[sql.execute('SELECT parse_status,pending_subtasks_count FROM knowledges WHERE id=?',(doc,)).fetchone() for _,doc,_,_ in imported]
                if len(jobs)==expected_jobs and all(row[1]=='succeeded' for row in jobs) and all(row==('completed',0) for row in states):break
                assert process.poll() is None and time.monotonic()<deadline,(jobs,states,str(log));time.sleep(.05)
            with sqlite3.connect(database) as sql:
                sql.enable_load_extension(True);sql.load_extension(str(vec));sql.enable_load_extension(False)
                for actor,doc,suffix,kind in imported:
                    stored=sql.execute('SELECT tenant_id,source,file_path,file_type,storage_size,title FROM knowledges WHERE id=?',(doc,)).fetchone()
                    assert stored[:3]==(1,f'http://127.0.0.1:{model.server_port}/'+suffix,''),stored
                    assert stored[3]==('html' if kind=='url' else 'txt') and stored[4]>0,stored
                    if kind=='url':assert stored[5]=='Remote evidence title',stored
                    chunks={row[0] for row in sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL AND chunk_type='text'",(doc,))};assert chunks
                    vectors=sql.execute('SELECT e.chunk_id,hex(v.embedding) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.knowledge_id=?',(doc,)).fetchall()
                    assert {row[0] for row in vectors}==chunks and all(row[1]=='0000803F0000000000000000' for row in vectors),vectors
                    matched=sql.execute("SELECT e.chunk_id FROM lite_embeddings_fts JOIN lite_embeddings e ON e.id=lite_embeddings_fts.rowid WHERE lite_embeddings_fts MATCH 'evidence' AND e.knowledge_id=?",(doc,)).fetchall()
                    assert {row[0] for row in matched}==chunks,matched
                    audit=sql.execute("SELECT tenant_id,actor_user_id,actor_role,outcome,details FROM audit_logs WHERE action='knowledge.created' AND target_id=?",(doc,)).fetchone()
                    assert audit[:4]==(1,actor,'viewer' if actor=='owner' else 'contributor','accepted'),audit
                    details=json.loads(audit[4]);assert details['source_type']==kind and details['trigger']=='user',details
                    task=sql.execute('SELECT tenant_id,type,queue,max_attempts,timeout_millis,payload,status FROM java_task_jobs WHERE id=?',(details['task_id'],)).fetchone()
                    assert task[:5]==(1,'document:process','default',4,7200000) and task[6]=='succeeded',task
                    payload=json.loads(task[5]);assert payload['request_id']=='' and payload['knowledge_id']==doc and payload['tenant_id']==1 and payload['language']=='en-US',payload
                    assert sql.execute('SELECT MAX(attempt) FROM knowledge_processing_spans WHERE knowledge_id=?',(doc,)).fetchone()==(1,)
                assert sql.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()[0]==quota_before+sum(sql.execute('SELECT storage_size FROM knowledges WHERE id=?',(doc,)).fetchone()[0] for _,doc,_,_ in imported)
                assert sql.execute('SELECT storage_used FROM tenants WHERE id=2').fetchone()==(0,)
                resource=sql.execute("SELECT physical_path FROM resources WHERE tenant_id=1 AND original_name='url-smoke.txt' AND lifecycle='temporary' AND state='active'").fetchall();assert len(resource)==1,resource
                assert (folder/'files'/resource[0][0].removeprefix('local://')).read_bytes()==b'Remote file evidence is downloaded and indexed through native URL admission.'
                sql.execute("CREATE TRIGGER reject_public_url BEFORE INSERT ON java_task_jobs WHEN NEW.type='document:process' BEGIN SELECT RAISE(ABORT,'controlled URL queue rejection');END")
            failed=url_request('foreign','url-queue-failure');assert failed['parse_status']=='failed' and failed['error_message']=='Failed to enqueue processing task',failed
            with sqlite3.connect(database) as sql:
                assert sql.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()==(expected_jobs,)
                assert sql.execute("SELECT outcome FROM audit_logs WHERE action='knowledge.created' AND target_id=?",(failed['id'],)).fetchone()==('failed',)
                sql.execute('DROP TRIGGER reject_public_url')
            assert len(reader_calls)==1 and len(calls)==4 and not chat_calls,(reader_calls,len(calls),chat_calls)
            print(json.dumps({'url_import':True,'imports':len(imported),'queue_failure_created_failed':True,'physical_vec_fts_download_reader_quota_audit_verified':True,'jobs':expected_jobs}))
        if args.image_update:
            def image_update(actor, document, chunk, image, key=None, expected=200, raw_body=None):
                headers = {'X-API-Key': key} if key else {'Authorization': 'Bearer ' + tokens[actor]}
                headers['Content-Type'] = 'application/json'
                data = raw_body.encode() if raw_body is not None else json.dumps({'image_info': image}).encode()
                request = urllib.request.Request(base + '/api/v1/knowledge/image/' + document + '/' + chunk, data=data, headers=headers, method='PUT')
                try:
                    with urllib.request.urlopen(request, timeout=10) as response:
                        status, result = response.status, json.load(response)
                except urllib.error.HTTPError as error:
                    status, result = error.code, json.load(error)
                assert status == expected, (actor, status, result)
                return result
            with sqlite3.connect(database) as sql:
                retained = {table: sql.execute('SELECT * FROM ' + table + ' ORDER BY id').fetchall() for table in ('tenants', 'java_task_jobs', 'resources', 'audit_logs', 'chunk_revisions')}
                documents_before = sql.execute('SELECT id,file_hash FROM knowledges ORDER BY id').fetchall()
            before_calls, before_chats = len(calls), len(chat_calls)
            for actor, document, _ in ids:
                with sqlite3.connect(database) as sql:
                    parent = sql.execute("SELECT id FROM chunks WHERE knowledge_id=? AND chunk_type='text' ORDER BY chunk_index LIMIT 1", (document,)).fetchone()[0]
                    source = sql.execute('SELECT content,source_content,source_locators,content_revision,index_status,last_editor_id FROM chunks WHERE id=?', (parent,)).fetchone()
                    previous_hash = sql.execute('SELECT file_hash FROM knowledges WHERE id=?', (document,)).fetchone()[0]
                children_before = None
                for step in (1, 2):
                    image = json.dumps([{'url': 'image-' + document, 'original_url': 'original-' + document, 'caption': 'Imagecaptionmarker ' + str(step) + ' 中文', 'ocr_text': 'Imageocrmarker ' + str(step) + ' 中文'}], ensure_ascii=False, separators=(',', ':'))
                    assert image_update(actor, document, parent, image) == {'success': True, 'message': 'Knowledge chunk image updated successfully'}
                    with sqlite3.connect(database) as sql:
                        sql.enable_load_extension(True);sql.load_extension(str(vec))
                        children = sql.execute("SELECT id,content,chunk_type,is_enabled,content_revision,index_status,last_editor_id,image_info FROM chunks WHERE parent_chunk_id=? AND chunk_type IN ('image_caption','image_ocr') ORDER BY chunk_type", (parent,)).fetchall()
                        assert len(children) == 2 and all(row[3:7] == (1, 0, 'ready', '') and row[7] == image for row in children), children
                        if children_before is not None:
                            assert [row[0] for row in children] == children_before
                        children_before = [row[0] for row in children]
                        for row in children:
                            assert sql.execute('SELECT content FROM lite_embeddings WHERE chunk_id=?', (row[0],)).fetchone()[0].endswith(row[1])
                            assert sql.execute('SELECT COUNT(*) FROM vec_embeddings_3 v JOIN lite_embeddings e ON e.id=v.rowid WHERE e.chunk_id=?', (row[0],)).fetchone() == (1,)
                            assert sql.execute("SELECT COUNT(*) FROM lite_embeddings_fts JOIN lite_embeddings e ON e.id=lite_embeddings_fts.rowid WHERE lite_embeddings_fts MATCH ? AND e.chunk_id=?", ('imagecaptionmarker' if row[2] == 'image_caption' else 'imageocrmarker', row[0])).fetchone() == (1,)
                        previous_hash = hashlib.md5((document + previous_hash + image).encode()).hexdigest()
                        assert sql.execute('SELECT file_hash FROM knowledges WHERE id=?', (document,)).fetchone() == (previous_hash,)
                        assert sql.execute('SELECT content,source_content,source_locators,content_revision,index_status,last_editor_id FROM chunks WHERE id=?', (parent,)).fetchone() == source
                for capability, full, scope, expected in [('retrieve', False, 'kb', 403), ('ingest', True, 'wrong-kb', 403), ('ingest', False, 'kb', 400)]:
                    key = 'image-' + secrets.token_hex(16)
                    with sqlite3.connect(database) as sql:
                        sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,'Isolated image editor',?,?,?,?,?)", (hashlib.sha256(key.encode()).hexdigest(), key, full, json.dumps([capability]), json.dumps([scope])))
                    image_update(actor, document, parent, '', key=key, expected=expected, raw_body='invalid')
                    with sqlite3.connect(database) as sql:
                        sql.execute('DELETE FROM tenant_api_keys WHERE key_hash=?', (hashlib.sha256(key.encode()).hexdigest(),))
            assert len(calls) == before_calls + 4 and len(chat_calls) == before_chats
            with sqlite3.connect(database) as sql:
                for table, before in retained.items():
                    assert sql.execute('SELECT * FROM ' + table + ' ORDER BY id').fetchall() == before, ('image updates must retain', table)
                assert all(old_hash != sql.execute('SELECT file_hash FROM knowledges WHERE id=?', (document,)).fetchone()[0] for document, old_hash in documents_before)
            print(json.dumps({'image_update': True, 'own_shared_OCR_caption_children_real_HTTP_vec_FTS_chained_hash_verified': True, 'source_quota_tasks_resources_audit_revisions_retained': True, 'embedding_calls_added': 4}))
        print(json.dumps({'checks':'production multipart → local resource → worker → actual model HTTP → vec/FTS → durable postprocess completion; real shared write + owner tenant/original actor audit; public tag document force/content_only cleanup when enabled; table summary/column task when Office enabled; Hikari pool1','topology':args.topology,'readiness_retries':readiness_retries,'uploads':expected_uploads,'jobs':expected_jobs+len(profile_jobs)+len(cleanup_jobs),'embedding_calls':len(calls),'chat_calls':len(chat_calls),'office_custom_format':bool(office),'summary_generation':summary,'question_generation':question,'auto_tag':tagging,'kb_profile':args.kb_profile,'kb_profile_jobs':len(profile_jobs),'index_delete':args.index_delete,'document_delete':args.document_delete,'public_delete':args.public_delete,'chunk_lifecycle':args.chunk_lifecycle,'folders':args.folders,'update_knowledge':args.update_knowledge,'regenerate_summary':args.regenerate_summary,'chunk_edit':args.chunk_edit,'chunk_questions':args.chunk_questions,'chunk_regenerate':args.chunk_regenerate,'public_delete_jobs':len(cleanup_jobs) if args.public_delete else 0,'index_delete_jobs':len(cleanup_jobs) if args.index_delete else 0,'document_delete_jobs':len(cleanup_jobs) if args.document_delete else 0,'cleanup_jobs':len(cleanup_jobs),'cancellation':args.cancel,'reader_calls':len(reader_calls),'file_response':args.file_response,'image_update':args.image_update,'log':str(log),'database':str(database)},ensure_ascii=False))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill();process.wait(timeout=5)
        if redis_process is not None and redis_process.poll() is None:
            redis_process.terminate()
            try:redis_process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                redis_process.kill();redis_process.wait(timeout=3)
        for release in releases:
            release.set()
        model.shutdown();model.server_close()


if __name__=='__main__':
    main()
