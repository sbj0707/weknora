#!/usr/bin/env python3
"""Packaged bound-ES8 public hybrid with owned SQL metadata, real ES and default-index fallback checks."""
import argparse,base64,hashlib,hmac,json,os,re,secrets,sqlite3,struct,subprocess,tempfile,threading,time,uuid,urllib.request,urllib.error
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('jar',type=Path);p.add_argument('vec',type=Path);p.add_argument('--es-url',required=True);p.add_argument('--topology',choices=('single','go'),default='go');p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);a=p.parse_args();root=a.root.resolve();folder=Path(tempfile.mkdtemp(prefix='weknora-java-hybrid-package-'));db=folder/'test.db';log=folder/'server.log';secret=secrets.token_urlsafe(32);process=None;calls=[]
 assert re.fullmatch(r'http://127\.0\.0\.1:[0-9]+',a.es_url),'Only owned loopback ES is allowed'
 index='weknora-vector-package-'+str(uuid.uuid4());es_created=False
 def es(method,path,value=None,raw=None):
  data=raw if raw is not None else (json.dumps(value).encode() if value is not None else None)
  with urllib.request.urlopen(urllib.request.Request(a.es_url+path,data=data,method=method,headers={'Content-Type':'application/json'}),timeout=20) as response:return json.load(response)
 fixture=next(c for c in json.loads((root/'backend-java/src/test/resources/go-vector-store-hybrid.json').read_text()) if c['name']=='shared')
 class Model(BaseHTTPRequestHandler):
  def do_POST(self):
   body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));calls.append({'path':self.path,'body':body});assert self.path=='/v1/embeddings',self.path;reply=json.dumps({'data':[{'index':0,'embedding':[1,.125]}]}).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(reply)
  def log_message(self,*args):pass
 model=ThreadingHTTPServer(('127.0.0.1',0),Model);threading.Thread(target=model.serve_forever,daemon=True).start();tokens={}
 try:
  props={'content':{'type':'text'},'embedding':{'type':'dense_vector','dims':2,'index':True,'similarity':'cosine'}}
  props.update({field:{'type':'keyword'} for field in ('chunk_id','source_id','knowledge_id','knowledge_base_id','tag_id')})
  es('PUT','/'+index,{'settings':{'number_of_shards':1,'number_of_replicas':0},'mappings':{'properties':props}});es_created=True
  operations=[]
  for entry in fixture['entries']:
   operations.append(json.dumps({'create':{'_index':index}}));operations.append(json.dumps({'content':entry['Content'],'source_id':entry['SourceID'],'source_type':entry['SourceType'],'chunk_id':entry['ChunkID'],'knowledge_id':entry['KnowledgeID'],'knowledge_base_id':entry['KnowledgeBaseID'],'tag_id':entry['TagID'],'is_enabled':entry['IsEnabled'],'embedding':fixture['vectors'].get(entry['SourceID']),'is_recommended':False}))
  bulk=es('POST','/'+index+'/_bulk',raw=('\n'.join(operations)+'\n').encode());assert not bulk['errors'],bulk
  es('POST','/'+index+'/_refresh');assert es('GET','/'+index+'/_count')['count']==len(fixture['entries'])
  with sqlite3.connect(db) as sql:
   for name in ('000000_init','000002_knowledge_folder_path','000003_knowledge_base_auto_tag_config','000004_memory','000007_system_admin_and_settings','000008_processing_spans_and_pending_subtasks','000010_knowledge_multi_tags','000011_principal_model','000021_knowledge_profiles','000031_model_catalog_config','000033_chunk_source_locators'):sql.executescript((root/'migrations/sqlite'/(name+'.up.sql')).read_text())
   for table in ('tenants','models','knowledge_bases','knowledges','chunks','organizations','organization_tenant_members','custom_agents','agent_shares','kb_shares','vector_stores'):
    for row in fixture['rows'][table] or []:
     row=row.copy();blobs=fixture['sql_blobs'].get(table,{}).get(str(row.get('id')),[])
     if table=='models':params=json.loads(row['parameters']);params['base_url']=f'http://127.0.0.1:{model.server_port}/v1';row['parameters']=json.dumps(params)
     if table=='vector_stores':
      cc=json.loads(row['connection_config']);cc['addr']=a.es_url;row['connection_config']=json.dumps(cc);cfg=json.loads(row['index_config']);cfg['index_name']=index;row['index_config']=json.dumps(cfg)
     values=[v.encode() if k in blobs and isinstance(v,str) else v for k,v in row.items()];sql.execute('INSERT INTO '+table+'('+','.join(row)+') VALUES('+','.join('?' for _ in row)+')',values)
   sql.execute("INSERT INTO tenants(id,name,business) VALUES(9,'Foreign','isolated') ON CONFLICT(id) DO NOTHING")
   for name,tenant in (('caller',1),('owner',2),('foreign',9)):
    sql.execute("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)",(name,name,name+'@test.invalid',tenant));sql.execute("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,'viewer','active')",(name,tenant))
    encode=lambda value:base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=');payload=encode({'alg':'HS256','typ':'JWT'})+b'.'+encode({'user_id':name,'tenant_id':tenant,'type':'access','exp':int(time.time())+3600});tokens[name]=(payload+b'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),payload,hashlib.sha256).digest()).rstrip(b'=')).decode();sql.execute("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",(str(uuid.uuid4()),name,tokens[name]))
   keys={}
   for cap,scope in (('retrieve','kb'),('chat','kb'),('retrieve-denied','missing')):
    key=secrets.token_urlsafe(24);keys[cap]=key;sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,?,?,?,false,?,?)",(cap,hashlib.sha256(key.encode()).hexdigest(),key,json.dumps([cap.split('-')[0]]),json.dumps([scope])))
   sql.enable_load_extension(True);sql.load_extension(str(a.vec.resolve()));sql.executescript("CREATE TABLE lite_embeddings(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at datetime,updated_at datetime,source_id text NOT NULL,source_type integer NOT NULL,chunk_id text,knowledge_id text,knowledge_base_id text,tag_id text,content text NOT NULL,dimension integer NOT NULL,is_enabled numeric DEFAULT true);CREATE UNIQUE INDEX idx_sqlite_emb_source ON lite_embeddings(source_id,source_type);CREATE VIRTUAL TABLE lite_embeddings_fts USING fts5(content,source_id,chunk_id,knowledge_id,knowledge_base_id,content='',contentless_delete=1,tokenize='unicode61');CREATE VIRTUAL TABLE vec_embeddings_2 USING vec0(embedding float[2] distance_metric=cosine);")
   for entry in fixture['entries']:
    vector=fixture['vectors'].get(entry['SourceID']);row=sql.execute('INSERT INTO lite_embeddings(source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,is_enabled) VALUES(?,?,?,?,?,?,?,?,?)',(entry['SourceID'],entry['SourceType'],entry['ChunkID'],entry['KnowledgeID'],entry['KnowledgeBaseID'],entry['TagID'],entry['Content'],len(vector or []),entry['IsEnabled'])).lastrowid;sql.execute('INSERT INTO lite_embeddings_fts(rowid,content,source_id,chunk_id,knowledge_id,knowledge_base_id) VALUES(?,?,?,?,?,?)',(row,entry['Content'],entry['SourceID'],entry['ChunkID'],entry['KnowledgeID'],entry['KnowledgeBaseID']))
    if vector:sql.execute('INSERT INTO vec_embeddings_2(rowid,embedding) VALUES(?,?)',(row,struct.pack('<2f',*vector)))
   assert sql.execute('SELECT COUNT(*) FROM vec_embeddings_2').fetchone()[0]==len(fixture['vectors'])
  env=os.environ.copy();env.update(NEO4J_ENABLE='false',JAVA_DB_URL='jdbc:sqlite:'+str(db),JWT_SECRET=secret,JAVA_DB_POOL_SIZE='1',JAVA_GO_BRIDGE_ENABLED='false',RETRIEVE_DRIVER='sqlite',SSRF_WHITELIST='127.0.0.1',SSRF_DNS_WHITELIST_ONLY='true',WEKNORA_SQLITE_VEC_EXTENSION=str(a.vec.resolve()))
  with log.open('wb') as output:process=subprocess.Popen(['java','-jar',str(a.jar.resolve()),'--server.address=127.0.0.1','--server.port=0','--spring.datasource.driver-class-name=org.sqlite.JDBC','--spring.datasource.hikari.connection-timeout=2000','--weknora.tasks.enabled=false','--weknora.tasks.topology='+a.topology,'--weknora.knowledge.processing.enabled=false','--logging.level.root=WARN','--debug=false','--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO'],env=env,stdout=output,stderr=subprocess.STDOUT)
  end=time.monotonic()+45
  while True:
   match=re.search(r'Tomcat started on port (\d+)',log.read_text(errors='replace'))
   if match:base='http://127.0.0.1:'+match.group(1);break
   assert process.poll() is None and time.monotonic()<end,'Startup failed: '+str(log);time.sleep(.1)
  def request(method,credential,machine=False,body='{"query_text":"refund policy"}',query='',status=200):
   headers={'Content-Type':'application/json','X-API-Key' if machine else 'Authorization':credential if machine else 'Bearer '+credential};req=urllib.request.Request(base+'/api/v1/knowledge-bases/kb/hybrid-search'+query,data=body.encode(),headers=headers,method=method)
   try:
    with urllib.request.urlopen(req,timeout=15) as response:code=response.status;result=json.load(response)
   except urllib.error.HTTPError as error:code=error.code;result=json.load(error)
   assert code==status,(code,result,log);return result
  for name in ('owner','caller'):
   for method in ('GET','POST'):assert request(method,tokens[name])==fixture['body']
  assert request('POST',keys['retrieve'],True)==fixture['body'];before=len(calls)
  assert request('POST',keys['chat'],True,body='{',status=403)=={'error':'Forbidden: API key scope does not allow this operation'}
  assert request('POST',keys['retrieve-denied'],True,body='{',status=403)['error']['code']==1002
  request('GET',tokens['foreign'],body='{',status=403);request('GET',tokens['caller'],body='',query='?query_text=refund',status=400);request('GET',keys['retrieve'],True,query='?resource_urls=public',status=403);assert len(calls)==before==5
  with sqlite3.connect(db) as sql:sql.execute("UPDATE kb_shares SET deleted_at=CURRENT_TIMESTAMP WHERE knowledge_base_id='kb'")
  request('POST',tokens['caller'],body='{',status=403);assert len(calls)==5
  es('POST','/'+index+'/_delete_by_query',{'query':{'match_all':{}}});es('POST','/'+index+'/_refresh');assert es('GET','/'+index+'/_count')['count']==0
  assert request('POST',tokens['owner'])['data'] is None
  # The default vec/FTS data still exists, but a bound empty store must stay empty.
  with sqlite3.connect(db) as sql:
   sql.enable_load_extension(True);sql.load_extension(str(a.vec.resolve()));assert sql.execute('SELECT COUNT(*) FROM vec_embeddings_2').fetchone()[0]==len(fixture['vectors'])
  with sqlite3.connect(db) as sql:sql.execute("UPDATE knowledge_bases SET vector_store_id=NULL WHERE id='kb'")
  assert request('POST',tokens['owner'])['data']
  with sqlite3.connect(db) as sql:sql.execute("UPDATE knowledge_bases SET vector_store_id='missing-store' WHERE id='kb'")
  unavailable=request('POST',tokens['owner'],status=400);assert unavailable['error']['code']==2200,unavailable
  assert len(calls)==8,len(calls)
  print(json.dumps({'topology':a.topology,'requests':14,'model_http':len(calls),'physical_entries':len(fixture['entries']),'tasks_enabled':False,'es_index':index,'checks':'native bound ES8 GET+POST own/shared JWT/API scopes, Go DTO, physical ES delete, empty store never falls back, explicit unbind/default retrieval, missing binding owner error; SQL Hikari1; no bound document publication claim','artifacts':str(folder)}))
 finally:
  if process:
   process.terminate()
   try:process.wait(15)
   except subprocess.TimeoutExpired:process.kill();process.wait(5)
  if es_created:
   try:es('DELETE','/'+index)
   except Exception as error:print('Owned ES cleanup failed: '+repr(error))
  model.shutdown();model.server_close();print('Bound ES hybrid package artifacts: '+str(folder))
if __name__=='__main__':main()
