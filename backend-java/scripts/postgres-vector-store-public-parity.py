#!/usr/bin/env python3
"""Actual public vector-store CRUD/probe Go HTTP/SQL versus native PG/Hikari1 with real ES8."""
import argparse,json,os,subprocess,tempfile,time,uuid
from pathlib import Path

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--es-url',required=True);p.add_argument('--image',default='paradedb/paradedb:v0.22.6-pg17');p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);args=p.parse_args();root=args.root.resolve();output=Path(tempfile.mkdtemp(prefix='weknora-postgres-vector-public-'));container=None
 def command(values,**kwargs):return subprocess.run(values,cwd=root,check=True,**kwargs)
 try:
  container=command(['docker','run','-d','--rm','--name','weknora-java-hybrid-'+uuid.uuid4().hex[:12],'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD=local-parity-only','-e','POSTGRES_DB=weknora_vector_public_parity',args.image],capture_output=True,text=True).stdout.strip()
  end=time.monotonic()+45
  while subprocess.run(['docker','exec',container,'pg_isready','-h','127.0.0.1','-U','postgres','-d','weknora_vector_public_parity'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
   if time.monotonic()>end:raise RuntimeError('Owned PG startup deadline')
   time.sleep(.2)
  port=command(['docker','port',container,'5432'],capture_output=True,text=True).stdout.strip().rsplit(':',1)[1];migrations=sorted((root/'migrations/versioned').glob('*.up.sql'))
  with (output/'schema.log').open('wb') as log:
   for migration in migrations:log.write((str(migration)+'\n').encode());log.flush();command(['docker','exec','-i',container,'psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','weknora_vector_public_parity'],input=migration.read_bytes(),stdout=log,stderr=subprocess.STDOUT)
  env=os.environ.copy();env['WEKNORA_VECTOR_PUBLIC_PG_DSN']=f'postgres://postgres:local-parity-only@127.0.0.1:{port}/weknora_vector_public_parity?sslmode=disable';fixture=output/'go-vector-public-pg.json';env['WEKNORA_ES_PARITY_URL']=args.es_url
  with (output/'go.log').open('wb') as log:command(['go','run','-tags','sqlite_fts5','backend-java/src/test/fixtures/vector_store_public_fixture.go',str(fixture)],env=env,stdout=log,stderr=subprocess.STDOUT)
  assert len(json.loads(fixture.read_text())['cases'])==133
  with (output/'java.log').open('wb') as log:command(['mvn','-q','-f','backend-java/pom.xml','-Dweknora.build.directory='+str(output/'build'),'-Dweknora.vector.public.pg.url=jdbc:postgresql://127.0.0.1:'+port+'/weknora_vector_public_parity','-Dweknora.vector.public.fixture='+str(fixture),'-Dweknora.es.url='+args.es_url,'-Dtest=VectorStorePublicPostgresGoParityIT','clean','test'],stdout=log,stderr=subprocess.STDOUT)
  print(json.dumps({'cases':133,'go_migrations':len(migrations),'pool_size':1,'checks':'actual Go public CRUD/test/types/env/role/API scope, real ES8 and exact SQL owner/credential/lifecycle side effects vs native Hikari1','artifacts':str(output)}))
 finally:
  if container:subprocess.run(['docker','stop',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
  print('Vector store public parity artifacts: '+str(output))
if __name__=='__main__':main()
