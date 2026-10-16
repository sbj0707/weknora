#!/usr/bin/env python3
"""Isolated packaged native manual APIs -> background SQL workers -> real model/resources/vec/FTS."""
import argparse,base64,hashlib,hmac,json,os,re,secrets,sqlite3,subprocess,tempfile,threading,time,urllib.request,urllib.error,uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('jar',type=Path);p.add_argument('vec',type=Path);p.add_argument('--topology',choices=['single','go'],default='go');p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();root=a.root.resolve();jar=a.jar.resolve();vec=a.vec.resolve();assert jar.is_file() and vec.is_file()
 folder=Path(tempfile.mkdtemp(prefix='weknora-java-manual-smoke-'));db=folder/'test.db';log=folder/'server.log';secret=secrets.token_urlsafe(32);calls=[];chats=[];tokens={};image=bytes([120])*600;process=None
 class Model(BaseHTTPRequestHandler):
  def do_POST(self):
   b=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
   if self.path=='/v1/embeddings':
    calls.append(b);result={'data':[{'index':i,'embedding':[len(t.encode()),sum(t.encode())%251,1]} for i,t in reversed(list(enumerate(b['input'])))]}
   else:
    assert self.path=='/v1/chat/completions',self.path;chats.append(b)
    content='What are the manual controls?\nHow are revisions fenced?\nWhy is access restricted?' if len(b['messages'])==1 else json.dumps({'summary':'Native manual summary 中文','gist':'Manual access controls','doc_type':'policy','topics':['access']})
    result={'choices':[{'message':{'role':'assistant','content':content},'finish_reason':'stop'}]}
   body=json.dumps(result).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
  def log_message(self,*_):pass
 model=ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=model.serve_forever,daemon=True).start()
 with sqlite3.connect(db) as s:
  for name in ('000000_init','000002_knowledge_folder_path','000003_knowledge_base_auto_tag_config','000004_memory','000007_system_admin_and_settings','000008_processing_spans_and_pending_subtasks','000010_knowledge_multi_tags','000011_principal_model','000021_knowledge_profiles','000031_model_catalog_config','000033_chunk_source_locators'):s.executescript((root/'migrations/sqlite'/(name+'.up.sql')).read_text())
  s.executescript((root/'backend-java/migration/sql/java-tasks.sql').read_text());s.execute("INSERT INTO tenants(id,name,business,storage_engine_config) VALUES(1,'Owner','isolated','{}'),(2,'Caller','isolated','{}')")
  for user,tenant,role in [('owner',1,'admin'),('caller',2,'contributor')]:
   s.execute("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)",(user,user,user+'@test.invalid',tenant));s.execute("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",(user,tenant,role))
   enc=lambda v:base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).rstrip(b'=');payload=enc({'alg':'HS256','typ':'JWT'})+b'.'+enc({'user_id':user,'tenant_id':tenant,'type':'access','exp':int(time.time())+3600});token=(payload+b'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),payload,hashlib.sha256).digest()).rstrip(b'=')).decode();tokens[user]=token;s.execute("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",(str(uuid.uuid4()),user,token))
  s.execute("INSERT INTO knowledge_bases(id,tenant_id,name,type,creator_id,embedding_model_id,summary_model_id,indexing_strategy,chunking_config,question_generation_config) VALUES('kb',1,'Manual','document','owner','embedding','summary',?,?,?)",(json.dumps({'vector_enabled':True,'keyword_enabled':True}),json.dumps({'chunk_size':120,'chunk_overlap':5,'separators':['\n\n','\n']}),json.dumps({'enabled':True,'question_count':3})))
  params=json.dumps({'provider':'generic','base_url':f'http://127.0.0.1:{model.server_port}/v1','embedding_parameters':{'dimension':3}}).encode()
  for mid,typ in [('embedding','Embedding'),('summary','KnowledgeQA')]:s.execute("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES(?,1,?,?,'remote',?,false,'active')",(mid,mid,typ,params))
  s.execute("INSERT INTO organizations(id,name,owner_tenant_id,owner_id) VALUES('org','Org',1,'owner')");s.execute("INSERT INTO organization_tenant_members(id,organization_id,tenant_id,role) VALUES('source','org',1,'admin'),('caller','org',2,'editor')");s.execute("INSERT INTO kb_shares(id,knowledge_base_id,organization_id,source_tenant_id,permission,shared_by_user_id) VALUES('share','kb','org',1,'editor','owner')")
 env=os.environ.copy();env.update(NEO4J_ENABLE='false',JAVA_DB_URL='jdbc:sqlite:'+str(db),JWT_SECRET=secret,JAVA_DB_POOL_SIZE='1',JAVA_GO_BRIDGE_ENABLED='false',RETRIEVE_DRIVER='sqlite',LOCAL_STORAGE_BASE_DIR=str(folder/'files'),SSRF_WHITELIST='127.0.0.1',SSRF_DNS_WHITELIST_ONLY='true',WEKNORA_SQLITE_VEC_EXTENSION=str(vec),CONCURRENCY_POOL_SIZE='5')
 for pool in ('CORE','POSTPROCESS','ENRICHMENT','MAINTENANCE','SHARED'):env['WEKNORA_ASYNQ_'+pool+'_CONCURRENCY']='1'
 env['WEKNORA_WIKI_ASYNQ_CONCURRENCY']='1'
 try:
  with log.open('wb') as out:process=subprocess.Popen(['java','-jar',str(jar),'--server.address=127.0.0.1','--server.port=0','--spring.datasource.driver-class-name=org.sqlite.JDBC','--spring.datasource.hikari.connection-timeout=2000','--weknora.tasks.enabled=true','--weknora.tasks.topology='+a.topology,'--weknora.tasks.concurrency=1','--weknora.tasks.poll-millis=100','--weknora.tasks.lease-millis=5000','--weknora.tasks.queues=default=1,postprocess=1,summary=1,question=1','--weknora.knowledge.processing.enabled=true','--weknora.knowledge.upload.enabled=false','--logging.level.root=WARN','--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO'],env=env,stdout=out,stderr=subprocess.STDOUT)
  until=time.monotonic()+45
  while True:
   m=re.search(r'Tomcat started on port (\d+)',log.read_text(errors='replace'))
   if m:base='http://127.0.0.1:'+m.group(1);break
   assert process.poll() is None and time.monotonic()<until,('production startup',str(log));time.sleep(.1)
  def http(method,path,actor,body=None,status=200):
   req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json'},method=method)
   try:
    with urllib.request.urlopen(req,timeout=10) as r:result=json.load(r);assert r.status==status,(r.status,result);return result
   except urllib.error.HTTPError as e:
    result=json.load(e);assert e.code==status,(e.code,result,str(log));return result
  ready=time.monotonic()+30
  while True:
   try:http('GET','/api/v1/auth/me','owner');break
   except AssertionError:
    assert time.monotonic()<ready,('SQL readiness',str(log));time.sleep(.1)
  def settle(expected):
   until=time.monotonic()+45
   while True:
    with sqlite3.connect(db) as s:rows=s.execute('SELECT type,status,last_error FROM java_task_jobs').fetchall()
    assert not any(r[1]=='dead' for r in rows),(rows,str(log))
    if len(rows)==expected and all(r[1]=='succeeded' for r in rows):return rows
    assert process.poll() is None and time.monotonic()<until,('worker deadline',rows,str(log));time.sleep(.05)
  config={'summary_enabled':True,'question_generation_config':{'enabled':True,'question_count':3}}
  owner=http('POST','/api/v1/knowledge-bases/kb/knowledge/manual','owner',{'title':'Manual owner','content':'Draft manual access policy.','status':'draft'})['data']['id']
  with sqlite3.connect(db) as s:assert s.execute('SELECT COUNT(*) FROM java_task_jobs').fetchone()==(0,)
  http('PUT','/api/v1/knowledge/manual/'+owner,'owner',{'content':'Published manual access policy and source revision controls.','status':'publish','process_config':config});settle(4)
  shared=http('POST','/api/v1/knowledge-bases/kb/knowledge/manual','caller',{'title':'Shared manual','content':'Shared native manual ingestion uses the KB owner storage and model.','status':'publish','process_config':config})['data']['id'];settle(8)
  image_content='![inline](data:image/png;base64,'+base64.b64encode(image).decode()+')'
  image_id=http('POST','/api/v1/knowledge-bases/kb/knowledge/manual','caller',{'title':'Manual image','content':image_content,'status':'publish','process_config':{'summary_enabled':False,'question_generation_config':{'enabled':False}}})['data']['id'];settle(10)
  http('PUT','/api/v1/knowledge/manual/'+owner,'owner',{'content':'Republished manual access policy retains fenced revision metadata.','status':'publish','process_config':config});rows=settle(14)
  for doc in (owner,shared,image_id):
   for alias in ('spans','stages'):
    data=http('GET','/api/v1/knowledge/'+doc+'/'+alias,'caller')['data'];assert data['trace']['status']=='done',data
    assert data['latest_attempt']==(2 if doc==owner else 1),data
   index=http('GET','/api/v1/knowledge/'+doc+'/index-status','caller')['data'];assert any(i['kind']=='vector' and i['count']>0 and i['status']=='completed' for i in index['indexes']),index
  with sqlite3.connect(db) as s:
   docs=s.execute('SELECT id,tenant_id,type,parse_status,pending_subtasks_count,metadata,description FROM knowledges ORDER BY id').fetchall();assert len(docs)==3 and all(r[1:5]==(1,'manual','completed',0) for r in docs),docs
   assert json.loads(next(r[5] for r in docs if r[0]==owner))['version']==3,docs
   assert json.loads(next(r[5] for r in docs if r[0]==image_id))['content']==image_content,docs
   assert all(r[6]=='Native manual summary 中文' for r in docs if r[0]!=image_id),docs
   s.enable_load_extension(True);s.load_extension(str(vec));s.enable_load_extension(False)
   indices=s.execute('SELECT COUNT(*) FROM lite_embeddings').fetchone()[0];physical=s.execute('SELECT COUNT(*) FROM vec_embeddings_3').fetchone()[0];fts=s.execute('SELECT COUNT(*) FROM lite_embeddings_fts').fetchone()[0];assert indices==physical==fts and indices>0,(indices,physical,fts)
   resource=s.execute("SELECT r.physical_path,r.tenant_id FROM resources r JOIN resource_bindings b ON b.resource_id=r.id WHERE b.owner_id=? AND b.relation='attachment'",(image_id,)).fetchone();assert resource and resource[1]==1,resource;assert (folder/'files'/resource[0].removeprefix('local://')).read_bytes()==image,resource
   assert s.execute('SELECT COUNT(*) FROM chunks WHERE knowledge_id=? AND deleted_at IS NOT NULL',(owner,)).fetchone()[0]>0
   assert s.execute('SELECT storage_used FROM tenants WHERE id=1').fetchone()[0]>0
   assert s.execute("SELECT actor_user_id FROM audit_logs WHERE action='knowledge.created' AND target_id=?",(shared,)).fetchone()==('caller',)
   for doc in (owner,shared,image_id):
    chunk=s.execute('SELECT id FROM chunks WHERE knowledge_id=? AND deleted_at IS NULL ORDER BY chunk_index LIMIT 1',(doc,)).fetchone()[0];http('GET','/api/v1/knowledge/'+doc+'/chunks/'+chunk+'/details','caller')
  print(json.dumps({'topology':a.topology,'manual_documents':3,'published_deliveries':4,'jobs':len(rows),'embedding_http_calls':len(calls),'chat_http_calls':len(chats),'physical_vectors':physical,'physical_fts':fts,'pool_size':1,'manual_batch_capacity':5,'real_owner_image_bytes':len(image),'artifacts':str(folder)}))
 finally:
  if process:
   process.terminate()
   try:process.wait(timeout=10)
   except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
  model.shutdown();model.server_close();print('Manual packaged artifacts: '+str(folder))
if __name__=='__main__':main()
