#!/usr/bin/env python3
"""Actual Go public hybrid HTTP/model/physical PG engine versus Java/Hikari1 in an owned DB."""
import argparse,json,os,subprocess,tempfile,time,uuid
from pathlib import Path

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--image',default='paradedb/paradedb:v0.22.6-pg17');p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);p.add_argument('--jieba-library',type=Path,required=True);p.add_argument('--jieba-dict',type=Path,required=True);args=p.parse_args();root=args.root.resolve();output=Path(tempfile.mkdtemp(prefix='weknora-postgres-hybrid-'));container=None
 def command(values,**kwargs):return subprocess.run(values,cwd=root,check=True,**kwargs)
 try:
  container=command(['docker','run','-d','--rm','--name','weknora-java-hybrid-'+uuid.uuid4().hex[:12],'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD=local-parity-only','-e','POSTGRES_DB=weknora_hybrid_parity',args.image],capture_output=True,text=True).stdout.strip()
  end=time.monotonic()+45
  while subprocess.run(['docker','exec',container,'pg_isready','-h','127.0.0.1','-U','postgres','-d','weknora_hybrid_parity'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
   if time.monotonic()>end:raise RuntimeError('Owned PG startup deadline')
   time.sleep(.2)
  port=command(['docker','port',container,'5432'],capture_output=True,text=True).stdout.strip().rsplit(':',1)[1];migrations=sorted((root/'migrations/versioned').glob('*.up.sql'))
  with (output/'schema.log').open('wb') as log:
   for migration in migrations:log.write((str(migration)+'\n').encode());log.flush();command(['docker','exec','-i',container,'psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','weknora_hybrid_parity'],input=migration.read_bytes(),stdout=log,stderr=subprocess.STDOUT)
  env=os.environ.copy();env['WEKNORA_HYBRID_PARITY_PG_DSN']=f'postgres://postgres:local-parity-only@127.0.0.1:{port}/weknora_hybrid_parity?sslmode=disable';fixture=output/'go-hybrid-search-pg.json'
  with (output/'go.log').open('wb') as log:command(['go','run','-tags','sqlite_fts5','backend-java/src/test/fixtures/hybrid_search_fixture.go',str(fixture)],env=env,stdout=log,stderr=subprocess.STDOUT)
  assert len(json.loads(fixture.read_text()))==143
  with (output/'java.log').open('wb') as log:command(['mvn','-q','-f','backend-java/pom.xml','-Dweknora.build.directory='+str(output/'build'),'-Dweknora.hybrid.pg.url=jdbc:postgresql://127.0.0.1:'+port+'/weknora_hybrid_parity','-Dweknora.hybrid.pg.fixture='+str(fixture),'-Dweknora.jieba.library='+str(args.jieba_library.resolve()),'-Dweknora.jieba.dict='+str(args.jieba_dict.resolve()),'-Dtest=HybridSearchPostgresGoParityIT','clean','test'],stdout=log,stderr=subprocess.STDOUT)
  print(json.dumps({'cases':143,'go_migrations':len(migrations),'pool_size':1,'checks':'actual public Go GET/POST/middleware/SQL/model/halfvec/ParadeDB against native MVC/physical stores; exact full JSON and provider calls','artifacts':str(output)}))
 finally:
  if container:subprocess.run(['docker','stop',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
  print('Hybrid parity artifacts: '+str(output))
if __name__=='__main__':main()
