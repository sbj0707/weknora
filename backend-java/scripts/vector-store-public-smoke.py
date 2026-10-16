#!/usr/bin/env python3
"""Fixed-jar vector-store management through real JWT/API auth and owned ES8/SQLite.

Both topology configurations intentionally set tasks=false: these are synchronous
management APIs, and this probe makes no claim about bound document publication.
"""
import argparse,base64,hashlib,hmac,json,os,re,secrets,sqlite3,subprocess,tempfile,time,uuid,urllib.error,urllib.request
from pathlib import Path

class PgConnection:
 """Tiny owned-fixture client; real psql executes all statements against the owned PG."""
 def __init__(self,container):self.container=container;self.result=None
 def __enter__(self):return self
 def __exit__(self,*args):pass
 def execute(self,sql,values=()):
  parts=sql.split('?');assert len(parts)==len(values)+1
  literal=lambda v:'NULL' if v is None else ('true' if v else 'false') if isinstance(v,bool) else str(v) if isinstance(v,int) else "'"+str(v).replace("'","''")+"'"
  statement=parts[0]+''.join(literal(v)+tail for v,tail in zip(values,parts[1:]));output=subprocess.run(['docker','exec','-i',self.container,'psql','-X','-v','ON_ERROR_STOP=1','-qAt','-U','postgres','-d','weknora_vector_public_parity'],input=statement+';\n',capture_output=True,text=True,check=False);assert output.returncode==0,'Owned PG fixture SQL failed: '+output.stderr;output=output.stdout.strip();self.result=None if not output else (int(output) if re.fullmatch('[0-9]+',output) else output,);return self
 def fetchone(self):return self.result

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('jar',type=Path);p.add_argument('vec',type=Path);p.add_argument('--es-url',required=True);p.add_argument('--topology',choices=('single','go'),default='go');p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);p.add_argument('--postgres',action='store_true',help='Create an owned PG17 container, apply all Go migrations, and run the same authenticated management checks against PG metadata');a=p.parse_args();root=a.root.resolve()
 assert re.fullmatch(r'http://127\.0\.0\.1:[0-9]+',a.es_url),'An owned loopback ES is required'
 folder=Path(tempfile.mkdtemp(prefix='weknora-vector-public-package-'));db=folder/'test.db';log=folder/'server.log';secret=secrets.token_urlsafe(32);process=None;indexes=[];count=0;pg=None;pg_port=None
 def es(method,path,body=None):
  request=urllib.request.Request(a.es_url+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'},method=method)
  with urllib.request.urlopen(request,timeout=20) as response:return json.load(response)
 try:
  if a.postgres:
   pg=subprocess.run(['docker','run','-d','--rm','--name','weknora-vector-public-package-'+uuid.uuid4().hex[:12],'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD=local-parity-only','-e','POSTGRES_DB=weknora_vector_public_parity','paradedb/paradedb:v0.22.6-pg17'],check=True,capture_output=True,text=True).stdout.strip();end=time.monotonic()+45
   while subprocess.run(['docker','exec',pg,'pg_isready','-h','127.0.0.1','-U','postgres','-d','weknora_vector_public_parity'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
    assert time.monotonic()<end,'Owned PostgreSQL startup deadline';time.sleep(.2)
   pg_port=subprocess.run(['docker','port',pg,'5432'],check=True,capture_output=True,text=True).stdout.strip().rsplit(':',1)[1]
   with (folder/'schema.log').open('wb') as schema_log:
    for migration in sorted((root/'migrations/versioned').glob('*.up.sql')):subprocess.run(['docker','exec','-i',pg,'psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','weknora_vector_public_parity'],input=migration.read_bytes(),stdout=schema_log,stderr=subprocess.STDOUT,check=True)
  connect=lambda:PgConnection(pg) if pg else sqlite3.connect(db)
  with connect() as sql:
   for name in (() if pg else ('000000_init','000002_knowledge_folder_path','000003_knowledge_base_auto_tag_config','000007_system_admin_and_settings','000008_processing_spans_and_pending_subtasks','000010_knowledge_multi_tags','000011_principal_model','000021_knowledge_profiles','000031_model_catalog_config','000033_chunk_source_locators')):sql.executescript((root/'migrations/sqlite'/(name+'.up.sql')).read_text())
   sql.execute("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','isolated'),(2,'Foreign','isolated')")
   tokens={}
   for user,tenant,role in (('admin',1,'admin'),('viewer',1,'viewer'),('foreign',2,'admin')):
    sql.execute("INSERT INTO users(id,username,email,password_hash,tenant_id) VALUES(?,?,?,'unused',?)",(user,user,user+'@example.invalid',tenant));sql.execute("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES(?,?,?,'active')",(user,tenant,role))
    encode=lambda v:base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).rstrip(b'=');payload=encode({'alg':'HS256','typ':'JWT'})+b'.'+encode({'user_id':user,'tenant_id':tenant,'type':'access','exp':int(time.time())+3600});token=(payload+b'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),payload,hashlib.sha256).digest()).rstrip(b'=')).decode();tokens[user]=token;sql.execute("INSERT INTO auth_tokens(id,user_id,token,token_type,expires_at,is_revoked) VALUES(?,?,?,'access_token','2099-01-01 00:00:00',false)",(str(uuid.uuid4()),user,token))
   keys={}
   for cap in ('manage_vector_stores','retrieve'):
    key=secrets.token_urlsafe(24);keys[cap]=key;sql.execute("INSERT INTO tenant_api_keys(tenant_id,name,key_hash,api_key,full_access,capabilities,knowledge_base_ids) VALUES(1,?,?,?,false,?,'[\"unrelated-kb\"]')",(cap,hashlib.sha256(key.encode()).hexdigest(),key,json.dumps([cap])))
  env=os.environ.copy();env.update(NEO4J_ENABLE='false',JAVA_DB_URL='jdbc:sqlite:'+str(db),JWT_SECRET=secret,JAVA_DB_POOL_SIZE='1',JAVA_GO_BRIDGE_ENABLED='false',RETRIEVE_DRIVER='sqlite',SSRF_WHITELIST='127.0.0.1',SSRF_DNS_WHITELIST_ONLY='true',WEKNORA_SQLITE_VEC_EXTENSION=str(a.vec.resolve()),SYSTEM_AES_KEY='0123456789abcdef0123456789abcdef',WEKNORA_TENANT_ENABLE_RBAC='true')
  if pg:env.update(JAVA_DB_URL='jdbc:postgresql://127.0.0.1:'+pg_port+'/weknora_vector_public_parity',JAVA_DB_USERNAME='postgres',JAVA_DB_PASSWORD='local-parity-only')
  with log.open('wb') as output:process=subprocess.Popen(['java','-jar',str(a.jar.resolve()),'--server.address=127.0.0.1','--server.port=0','--spring.datasource.driver-class-name='+('org.postgresql.Driver' if pg else 'org.sqlite.JDBC'),'--spring.datasource.hikari.connection-timeout=2000','--weknora.tasks.enabled=false','--weknora.tasks.topology='+a.topology,'--weknora.knowledge.processing.enabled=false','--logging.level.root=WARN','--logging.level.org.springframework.boot.web.embedded.tomcat.TomcatWebServer=INFO'],env=env,stdout=output,stderr=subprocess.STDOUT)
  end=time.monotonic()+45
  while True:
   match=re.search(r'Tomcat started on port (\d+)',log.read_text(errors='replace'))
   if match:base='http://127.0.0.1:'+match.group(1);break
   assert process.poll() is None and time.monotonic()<end,'Startup failed: '+str(log);time.sleep(.1)
  def request(method,path='',body=None,user='admin',machine=False,status=200):
   nonlocal count
   credential=keys[user] if machine else tokens[user];headers={'Content-Type':'application/json','X-API-Key' if machine else 'Authorization':credential if machine else 'Bearer '+credential};data=None if body is None else body.encode() if isinstance(body,str) else json.dumps(body).encode();req=urllib.request.Request(base+'/api/v1/vector-stores'+path,data=data,headers=headers,method=method)
   try:
    with urllib.request.urlopen(req,timeout=20) as response:code=response.status;result=json.load(response)
   except urllib.error.HTTPError as error:code=error.code;result=json.load(error)
   count+=1;assert code==status,(code,status,result,log);return result
  for user in ('admin','viewer'):
   assert request('GET',user=user)['data'][0]['id']=='__env_sqlite__';assert request('GET','/types',user=user)['data']
  assert request('GET','/__env_sqlite__',user='viewer')['data']['readonly']
  assert request('POST','/__env_sqlite__/test',body='{')['version']==''
  for method in ('PUT','DELETE'):request(method,'/__env_sqlite__',body='{',status=400)
  for path,method in (('','POST'),('/test','POST'),('/missing','PUT'),('/missing','DELETE')):request(method,path,body='{',user='viewer',status=403)
  for method,path in (('GET',''),('GET','/types'),('GET','/missing'),('POST',''),('PUT','/missing'),('DELETE','/missing'),('POST','/test'),('POST','/missing/test')):request(method,path,body='{',user='retrieve',machine=True,status=403)
  version=es('GET','/')['version']['number'];created=[]
  for user,machine in (('admin',False),('manage_vector_stores',True)):
   index='weknora-vector-public-package-'+uuid.uuid4().hex;indexes.append(index);body={'name':'Owned ES','engine_type':'elasticsearch','connection_config':{'addr':a.es_url,'password':'synthetic-only-password','api_key':'synthetic-only-api'},'index_config':{'index_name':index,'number_of_replicas':0}}
   result=request('POST',body=body,user=user,machine=machine,status=201)['data'];id=result['id'];created.append(id);assert result['connection_config']['password']==result['connection_config']['api_key']=='***';assert result['connection_config']['version']==version
   with connect() as sql:
    raw=sql.execute('SELECT connection_config FROM vector_stores WHERE id=?',(id,)).fetchone()[0];raw=raw.encode() if isinstance(raw,str) else raw;assert b'synthetic-only-' not in raw and b'enc:v1:' in raw
   assert request('GET','/'+id,user='viewer')['data']==result
   for method in ('GET','PUT','DELETE'):request(method,'/'+id,body='{',user='foreign',status=404)
   request('POST','/'+id+'/test',user='foreign',status=404)
   assert request('PUT','/'+id,body={'name':'Renamed','engine_type':'qdrant','connection_config':{'password':'ignored'}})['data']['engine_type']=='elasticsearch'
   assert request('POST','/'+id+'/test')['version']==version
   assert request('POST','/test',body=body)['version']==version
   request('POST',body=body,status=409)
   # Management removes metadata/cache only. Existing physical index data must survive.
   es('PUT','/'+index+'/_doc/probe?refresh=true',{'content':'owned lifecycle sentinel'});assert es('GET','/'+index+'/_count')['count']==1
   with connect() as sql:sql.execute("INSERT INTO knowledge_bases(id,tenant_id,name,type,vector_store_id,embedding_model_id,summary_model_id) VALUES(?,1,'Bound','document',?,'','')",(id,id))
   assert request('DELETE','/'+id,status=400)['error']['code']==1000
   with connect() as sql:sql.execute('UPDATE knowledge_bases SET vector_store_id=NULL WHERE id=?',(id,))
   request('DELETE','/'+id);request('GET','/'+id,status=404);assert es('GET','/'+index+'/_count')['count']==1
   with connect() as sql:assert sql.execute('SELECT deleted_at FROM vector_stores WHERE id=?',(id,)).fetchone()[0] is not None
  for engine in ('qdrant','milvus','weaviate','doris','tencent_vectordb','opensearch'):
   body={'name':'Unsupported must never persist','engine_type':engine,'connection_config':{'addr':a.es_url,'host':a.es_url,'username':'fixture','api_key':'synthetic-only-api','database':'db'}}
   for path in ('/test',''):assert request('POST',path,body=body,status=503)['error']['code']==2201
  with connect() as sql:assert sql.execute('SELECT COUNT(*) FROM vector_stores').fetchone()[0]==2
  print(json.dumps({'topology':a.topology,'metadata_db':'postgresql' if pg else 'sqlite','go_migrations':len(list((root/'migrations/versioned').glob('*.up.sql'))) if pg else None,'tasks_enabled':False,'requests':count,'created_stores':2,'soft_deleted_stores':2,'physical_indices_preserved':2,'checks':'real JWT/API management capability and Viewer/Admin, env projections/readonly, typed errors, owner isolation, encrypted SQL, saved/raw health/version, name-only update, real ES8 factory index, duplicate and bound-KB guards, metadata delete preserves physical data; Hikari1','jar_sha256':hashlib.sha256(a.jar.read_bytes()).hexdigest(),'runner_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'artifacts':str(folder)}))
 finally:
  if process:
   process.terminate()
   try:process.wait(15)
   except subprocess.TimeoutExpired:process.kill();process.wait(5)
  for index in indexes:
   try:es('DELETE','/'+index)
   except Exception as error:print('Owned index cleanup failed: '+repr(error))
  if pg:subprocess.run(['docker','stop',pg],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
  print('Vector store public package artifacts: '+str(folder))
if __name__=='__main__':main()
