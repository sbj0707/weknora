#!/usr/bin/env python3
"""Live packaged native graph acceptance; owns only random document namespaces in an isolated Neo4j/APOC instance."""
import argparse, base64, hashlib, hmac, json, os, re, secrets, sqlite3, subprocess, tempfile, threading, time, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('jar', type=Path)
    p.add_argument('vec', type=Path)
    p.add_argument('--neo4j-uri', required=True, help='Bolt URI of an isolated Neo4j instance with APOC; never a development or production database')
    p.add_argument('--neo4j-http-url', required=True, help='Explicit HTTP URL of the same isolated instance; obtain its actual published 7474 port')
    p.add_argument('--neo4j-user', default='neo4j')
    p.add_argument('--neo4j-password', default='isolated-test')
    p.add_argument('--neo4j-auth-none', action='store_true', help='Use only with an isolated Neo4j instance configured with auth disabled')
    p.add_argument('--topology', choices=('single', 'go'), default='go')
    p.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = p.parse_args()
    root, jar, vec = args.root.resolve(), args.jar.resolve(), args.vec.resolve()
    assert jar.is_file() and vec.is_file(), 'jar and compiled sqlite-vec library must exist'
    neo_http = args.neo4j_http_url
    ids = []
    kb = 'graph-smoke-'+str(uuid.uuid4())
    folder = Path(tempfile.mkdtemp(prefix='weknora-java-graph-smoke-'))
    database, log = folder/'test.db', folder/'server.log'
    secret = secrets.token_urlsafe(32)
    tokens, embeddings, chats, failures = {}, [], [], []

    def cypher(query, parameters):
        headers = {'Content-Type': 'application/json'}
        if not args.neo4j_auth_none:
            headers['Authorization'] = 'Basic '+base64.b64encode((args.neo4j_user+':'+args.neo4j_password).encode()).decode()
        data = json.dumps({'statements':[{'statement':query, 'parameters':parameters}]}).encode()
        with urllib.request.urlopen(urllib.request.Request(neo_http.rstrip('/')+'/db/neo4j/tx/commit', data=data, headers=headers), timeout=15) as r:
            result = json.load(r)
        assert not result['errors'], result
        return [entry['row'] for entry in result['results'][0]['data']]

    assert cypher('RETURN apoc.version()', {})[0][0], 'Actual APOC must be installed'

    class Provider(BaseHTTPRequestHandler):
        def do_POST(self):
            try:
                request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if self.path == '/v1/embeddings':
                    embeddings.append(request)
                    result = {'data':[{'index':i, 'embedding':[1,0,0]} for i in reversed(range(len(request['input'])))]}
                else:
                    assert self.path == '/v1/chat/completions', self.path
                    chats.append(request)
                    assert request['chat_template_kwargs']['enable_thinking'] is False, request
                    if request['max_tokens'] == 8192:
                        assert request['temperature'] == .3 and request['messages'][1]['content'].startswith('# Question\nQ: '), request
                        reply = json.dumps([{'entity':'Alice','entity_attributes':['Owner']},{'entity':'Release'},{'entity1':'Alice','entity2':'Release','relation':'owns'}])
                    elif request['messages'][0]['role'] == 'user':
                        assert request['temperature'] == .7 and request['max_tokens'] == 512, request
                        reply = 'Who owns the release?\nWhat is the release policy?\nWhere is the release checkpoint?'
                    else:
                        assert request['temperature'] == .3 and request['max_tokens'] == 1024, request
                        reply = json.dumps({'summary':'Release summary 中文','gist':'Release profile','topics':['migration']})
                    result = {'choices':[{'message':{'role':'assistant','content':reply},'finish_reason':'stop'}]}
                data = json.dumps(result).encode()
                self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data))); self.end_headers(); self.wfile.write(data)
            except Exception as error:
                failures.append(repr(error))
                self.send_error(500, str(error))
        def log_message(self, *_):
            pass

    model = ThreadingHTTPServer(('127.0.0.1',0), Provider)
    threading.Thread(target=model.serve_forever,daemon=True).start()
    with sqlite3.connect(database) as sql:
        for name in ('000000_init','000002_knowledge_folder_path','000003_knowledge_base_auto_tag_config','000005_messages_attachments_and_invitation_fields','000006_task_queue_and_dead_letters','000007_system_admin_and_settings','000008_processing_spans_and_pending_subtasks','000010_knowledge_multi_tags','000011_principal_model','000021_knowledge_profiles','000031_model_catalog_config','000033_chunk_source_locators'):
            sql.executescript((root/'migrations/sqlite'/(name+'.up.sql')).read_text())
        sql.executescript((root/'backend-java/migration/sql/java-tasks.sql').read_text())
        sql.execute("INSERT INTO tenants(id,name,business,storage_engine_config) VALUES(1,'Owner','isolated','{}'),(2,'Shared caller','isolated','{}'),(3,'Unshared','isolated','{}')")
        for user,tenant,role in (('owner',1,'owner'),('caller',2,'contributor'),('unshared',3,'owner')):
            sql.execute("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)",(user,user,user+'@test.invalid',tenant))
            sql.execute("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",(user,tenant,role))
            encode = lambda x: base64.urlsafe_b64encode(json.dumps(x,separators=(',',':')).encode()).rstrip(b'=')
            payload = encode({'alg':'HS256','typ':'JWT'})+b'.'+encode({'user_id':user,'tenant_id':tenant,'type':'access','exp':int(time.time())+3600})
            token = (payload+b'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),payload,hashlib.sha256).digest()).rstrip(b'=')).decode()
            tokens[user] = token
            sql.execute("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",(str(uuid.uuid4()),user,token))
        strategy = json.dumps({'vector_enabled':True,'keyword_enabled':True,'graph_enabled':True,'wiki_enabled':False}).encode()
        chunks = json.dumps({'chunk_size':1024,'chunk_overlap':0,'enable_parent_child':False}).encode()
        extract = json.dumps({'enabled':True,'tags':['owns']}).encode()
        questions = json.dumps({'enabled':True,'question_count':3}).encode()
        sql.execute("INSERT INTO knowledge_bases(id,tenant_id,name,type,creator_id,embedding_model_id,summary_model_id,indexing_strategy,chunking_config,extract_config,question_generation_config) VALUES(?,1,'Graph acceptance','document','owner','embedding','chat',?,?,?,?)",(kb,strategy,chunks,extract,questions))
        parameters = json.dumps({'provider':'generic','base_url':f'http://127.0.0.1:{model.server_port}/v1','embedding_parameters':{'dimension':3}}).encode()
        for id,kind in (('embedding','Embedding'),('chat','KnowledgeQA')):
            sql.execute("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES(?,1,'unknown',?,'remote',?,false,'active')",(id,kind,parameters))
        sql.execute("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'owner')")
        sql.execute("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('caller','org',2,'editor')")
        sql.execute("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share',?,'org',1,'editor','owner')",(kb,))
    env = os.environ.copy()
    env.update(NEO4J_ENABLE='true',NEO4J_URI=args.neo4j_uri,NEO4J_USERNAME=args.neo4j_user,NEO4J_PASSWORD=args.neo4j_password,JAVA_DB_URL='jdbc:sqlite:'+str(database),JWT_SECRET=secret,JAVA_DB_POOL_SIZE='1',JAVA_GO_BRIDGE_ENABLED='false',RETRIEVE_DRIVER='sqlite',LOCAL_STORAGE_BASE_DIR=str(folder/'files'),MAX_FILE_SIZE_MB='1',SSRF_WHITELIST='127.0.0.1',SSRF_DNS_WHITELIST_ONLY='true',WEKNORA_SQLITE_VEC_EXTENSION=str(vec))
    for pool in ('CORE','POSTPROCESS','ENRICHMENT','MAINTENANCE','SHARED'):
        env['WEKNORA_ASYNQ_'+pool+'_CONCURRENCY'] = '1'
    env['WEKNORA_WIKI_ASYNQ_CONCURRENCY'] = '1'
    process = None
    try:
        with log.open('wb') as output:
            process = subprocess.Popen(['java','-jar',str(jar),'--server.address=127.0.0.1','--server.port=0','--spring.datasource.driver-class-name=org.sqlite.JDBC','--spring.datasource.hikari.connection-timeout=2000','--weknora.tasks.enabled=true','--weknora.tasks.topology='+args.topology,'--weknora.knowledge.processing.enabled=true','--weknora.knowledge.upload.enabled=true','--weknora.tasks.concurrency=1','--weknora.tasks.poll-millis=100','--weknora.tasks.lease-millis=5000','--weknora.tasks.queues=default=1,postprocess=1,summary=1,question=1,graph=1','--logging.level.root=WARN','--debug=false','--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO'],env=env,stdout=output,stderr=subprocess.STDOUT)
        deadline = time.monotonic()+45
        while True:
            match = re.search(r'Tomcat started on port (\d+)',log.read_text(errors='replace'))
            if match:
                base = 'http://127.0.0.1:'+match.group(1); break
            assert process.poll() is None and time.monotonic()<deadline, 'Production startup failed: '+str(log)
            time.sleep(.1)

        def get(path, actor='owner', expected=200):
            try:
                with urllib.request.urlopen(urllib.request.Request(base+path,headers={'Authorization':'Bearer '+tokens[actor]}),timeout=10) as response:
                    assert response.status == expected
                    return json.load(response)
            except urllib.error.HTTPError as error:
                assert error.code == expected, (error.code,error.read().decode(),log)
                error.close(); return None

        deadline = time.monotonic()+30
        while True:
            try:
                assert get('/api/v1/auth/me')['data']['user']['id']=='owner'; break
            except AssertionError:
                assert process.poll() is None and time.monotonic()<deadline, str(log)
                time.sleep(.1)
        for actor in ('owner','caller'):
            config = json.dumps({'summary_enabled':True,'question_generation_config':{'enabled':True,'question_count':3},'graph_enabled':True})
            content = ('Alice owns release policy paragraph. '+str(uuid.uuid4())).encode()
            body = ('--boundary\r\nContent-Disposition: form-data; name="process_config"\r\n\r\n'+config+'\r\n--boundary\r\nContent-Disposition: form-data; name="file"; filename="'+actor+'.txt"\r\nContent-Type: text/plain\r\n\r\n').encode()+content+b'\r\n--boundary--\r\n'
            request = urllib.request.Request(base+'/api/v1/knowledge-bases/'+kb+'/knowledge/file',data=body,headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'multipart/form-data; boundary=boundary','Accept-Language':'en-US'},method='POST')
            with urllib.request.urlopen(request,timeout=10) as response:
                result = json.load(response); assert response.status==200 and result['success'], result
            ids.append((actor,result['data']['id']))
        deadline = time.monotonic()+30
        while True:
            with sqlite3.connect(database,timeout=2) as sql:
                docs = sql.execute('SELECT id,parse_status,pending_subtasks_count,error_message FROM knowledges').fetchall()
                jobs = sql.execute('SELECT type,status,last_error FROM java_task_jobs').fetchall()
            if len(docs)==2 and all(r[1:3]==('completed',0) for r in docs) and len(jobs)==10 and all(r[1]=='succeeded' for r in jobs): break
            assert process.poll() is None and not failures and time.monotonic()<deadline and all(r[1]!='dead' for r in jobs), (docs,jobs,failures,str(log))
            time.sleep(.1)
        with sqlite3.connect(database,timeout=2) as sql:
            for actor,id in ids:
                assert sql.execute('SELECT tenant_id,summary_status FROM knowledges WHERE id=?',(id,)).fetchone()==(1,'completed')
                chunk,metadata = sql.execute("SELECT id,metadata FROM chunks WHERE knowledge_id=? AND chunk_type='text' AND deleted_at IS NULL",(id,)).fetchone()
                assert len(json.loads(metadata)['generated_questions'])==3
                assert sql.execute('SELECT COUNT(*) FROM lite_embeddings WHERE knowledge_id=?',(id,)).fetchone()==(5,)
                assert sql.execute('SELECT COUNT(*) FROM java_graph_task_drains WHERE tenant_id=1 AND knowledge_id=?',(id,)).fetchone()==(1,)
                assert sql.execute("SELECT COUNT(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND kind='root' AND status='done'",(id,)).fetchone()==(1,)
                assert sql.execute("SELECT COUNT(*) FROM knowledge_processing_spans WHERE knowledge_id=? AND status IN ('pending','running')",(id,)).fetchone()==(0,)
                audit = sql.execute("SELECT tenant_id,actor_user_id,outcome FROM audit_logs WHERE action='knowledge.created' AND target_id=?",(id,)).fetchone()
                assert audit==(1,actor,'accepted'), audit
                assert cypher('MATCH (n) WHERE n.kg=$doc RETURN count(n)',{'doc':id})==[[2]]
                assert cypher('MATCH (a)-[r]->(b) WHERE a.kg=$doc AND b.kg=$doc RETURN count(r)',{'doc':id})==[[1]]
                status = get('/api/v1/knowledge/'+id+'/index-status',actor)['data']
                graph = next(r for r in status['indexes'] if r['kind']=='graph')
                assert graph['enabled'] and graph['status']=='completed' and graph['count']==2 and graph['relation_count']==1, graph
                detail = get('/api/v1/knowledge/'+id+'/chunks/'+chunk+'/details',actor)['data']
                graph = next(r for r in detail['indexes'] if r['kind']=='graph')
                assert graph['available'] and len(graph['graph']['nodes'])==2 and len(graph['graph']['relations'])==1, graph
                assert all(n['id'] and n['chunk_ids']==[chunk] for n in graph['graph']['nodes']), graph
                trace = get('/api/v1/knowledge/'+id+'/spans',actor)['data']['trace']
                def flat(n):
                    yield n
                    for child in n.get('children',[]): yield from flat(child)
                spans = list(flat(trace))
                assert any(n['name']=='postprocess.graph.chunk[0]' and n['status']=='done' for n in spans), spans
                assert any(n['name']=='postprocess.summary' and n['status']=='done' for n in spans), spans
                assert any(n['name']=='postprocess.question' and n['status']=='done' for n in spans), spans
                stages = get('/api/v1/knowledge/'+id+'/stages',actor)['data']
                assert stages, stages
                get('/api/v1/knowledge/'+id+'/index-status','unshared',403)
                get('/api/v1/knowledge/'+id+'/chunks/'+chunk+'/details','unshared',403)
            assert sql.execute('SELECT COUNT(*) FROM java_task_jobs WHERE tenant_id<>1').fetchone()==(0,)
            assert sql.execute("SELECT COUNT(*) FROM java_task_jobs WHERE type='chunk:extract' AND queue='graph' AND max_attempts=4 AND timeout_millis=1800000").fetchone()==(2,)
        assert len(embeddings)==6 and len(chats)==6 and not failures, (len(embeddings),len(chats),failures)
        print(json.dumps({'checks':'packaged native own/shared uploads; actual Neo4j/APOC nodes/relations; graph+summary+question counters; physical indexes; public counts/details/spans/stages; unshared denial; owner tasks/audit; Hikari1','topology':args.topology,'uploads':2,'jobs':10,'embedding_calls':len(embeddings),'chat_calls':len(chats),'namespace':kb,'log':str(log),'database':str(database)},ensure_ascii=False))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        model.shutdown(); model.server_close()
        # Only document IDs returned by this run can ever be deleted; no global or development cleanup.
        if ids: cypher('MATCH (n) WHERE n.kg IN $docs DETACH DELETE n',{'docs':[id for _,id in ids]})


if __name__=='__main__':
    main()
