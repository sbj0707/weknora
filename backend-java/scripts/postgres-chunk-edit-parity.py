#!/usr/bin/env python3
"""Compare actual Go and native versioned chunk edit/revert and synchronous index replacement HTTP/SQL over an owned full-schema PostgreSQL DB."""
import argparse,json,os,subprocess,tempfile,time,uuid
from pathlib import Path

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--image',default='paradedb/paradedb:v0.22.6-pg17');p.add_argument('--docker',default='docker');p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);p.add_argument('--questions',action='store_true');p.add_argument('--regenerate',action='store_true');args=p.parse_args()
 if args.questions and args.regenerate:p.error('Choose one question mode')
 root=args.root.resolve();output=Path(tempfile.mkdtemp(prefix='weknora-postgres-chunk-edit-'));container=None
 def command(values,**kwargs):return subprocess.run(values,cwd=root,check=True,**kwargs)
 try:
  container=command([args.docker,'run','-d','--rm','--name','weknora-java-chunk-edit-'+uuid.uuid4().hex[:12],'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD=local-parity-only','-e','POSTGRES_DB=weknora_chunk_edit_parity',args.image],capture_output=True,text=True).stdout.strip()
  deadline=time.monotonic()+45
  while subprocess.run([args.docker,'exec',container,'pg_isready','-h','127.0.0.1','-U','postgres','-d','weknora_chunk_edit_parity'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
   if time.monotonic()>deadline:raise RuntimeError('Owned PG startup deadline')
   time.sleep(.2)
  port=command([args.docker,'port',container,'5432'],capture_output=True,text=True).stdout.strip().rsplit(':',1)[1]
  migrations=sorted((root/'migrations/versioned').glob('*.up.sql'))
  with (output/'schema.log').open('wb') as log:
   for migration in migrations+[root/'backend-java/migration/sql/java-tasks.sql']:
    log.write((str(migration)+'\n').encode());log.flush();command([args.docker,'exec','-i',container,'psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','weknora_chunk_edit_parity'],input=migration.read_bytes(),stdout=log,stderr=subprocess.STDOUT)
  env=os.environ.copy();env['WEKNORA_CHUNK_EDIT_PARITY_PG_DSN']=f'postgres://postgres:local-parity-only@127.0.0.1:{port}/weknora_chunk_edit_parity?sslmode=disable';fixture=output/'go-chunk-edit-http-pg.json'
  count=81 if args.regenerate else 130 if args.questions else 196
  if args.questions:env['WEKNORA_CHUNK_QUESTION_PARITY']='true'
  if args.regenerate:env['WEKNORA_CHUNK_REGENERATE_PARITY']='true'
  with (output/'go.log').open('wb') as log:command(['go','run','-tags','sqlite_fts5','backend-java/src/test/fixtures/chunk_edit_http_fixture.go',str(fixture)],env=env,stdout=log,stderr=subprocess.STDOUT)
  assert len(json.loads(fixture.read_text()))==count
  with (output/'java.log').open('wb') as log:command(['mvn','-q','-f','backend-java/pom.xml','-Dweknora.build.directory='+str(output/'build'),'-Dweknora.chunk.edit.pg.url=jdbc:postgresql://127.0.0.1:'+port+'/weknora_chunk_edit_parity','-Dweknora.chunk.edit.pg.fixture='+str(fixture),'-Dtest='+('ChunkQuestionRegenerationPostgresGoParityIT' if args.regenerate else 'ChunkQuestionPostgresGoParityIT' if args.questions else 'ChunkEditPostgresGoParityIT'),'clean','test'],stdout=log,stderr=subprocess.STDOUT)
  print(json.dumps({'cases':count,'question_mutations':args.questions,'regenerate':args.regenerate,'go_migrations':len(migrations),'pool_size':1,'checks':'actual Go versioned chunk edit/revert and synchronous index replacement handlers and KB/role/API-scope middleware vs native MVC; complete chunk DTO/binder/diagnostics/rows and immutable revisions; actual halfvec float32 bits per source/type; real model HTTP and physical source metadata; independent GIN sentinel; real Go Asynq refresh options','artifacts':str(output)}))
 finally:
  if container:subprocess.run([args.docker,'stop',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
  print('Chunk edit parity artifacts: '+str(output))
if __name__=='__main__':main()
