#!/usr/bin/env python3
"""Compare actual Go and native folder listing/rename/move HTTP/SQL over an owned full-schema PostgreSQL DB."""
import argparse,json,os,subprocess,tempfile,time,uuid
from pathlib import Path

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--image',default='paradedb/paradedb:v0.22.6-pg17');p.add_argument('--docker',default='docker');p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);args=p.parse_args()
 root=args.root.resolve();output=Path(tempfile.mkdtemp(prefix='weknora-postgres-folder-'));container=None
 def command(values,**kwargs):return subprocess.run(values,cwd=root,check=True,**kwargs)
 try:
  container=command([args.docker,'run','-d','--rm','--name','weknora-java-folder-'+uuid.uuid4().hex[:12],'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD=local-parity-only','-e','POSTGRES_DB=weknora_folder_parity',args.image],capture_output=True,text=True).stdout.strip()
  deadline=time.monotonic()+45
  while subprocess.run([args.docker,'exec',container,'pg_isready','-h','127.0.0.1','-U','postgres','-d','weknora_folder_parity'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
   if time.monotonic()>deadline:raise RuntimeError('Owned PG startup deadline')
   time.sleep(.2)
  port=command([args.docker,'port',container,'5432'],capture_output=True,text=True).stdout.strip().rsplit(':',1)[1]
  migrations=sorted((root/'migrations/versioned').glob('*.up.sql'))
  with (output/'schema.log').open('wb') as log:
   for migration in migrations+[root/'backend-java/migration/sql/java-tasks.sql']:
    log.write((str(migration)+'\n').encode());log.flush();command([args.docker,'exec','-i',container,'psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','weknora_folder_parity'],input=migration.read_bytes(),stdout=log,stderr=subprocess.STDOUT)
  env=os.environ.copy();env['WEKNORA_FOLDER_PARITY_PG_DSN']=f'postgres://postgres:local-parity-only@127.0.0.1:{port}/weknora_folder_parity?sslmode=disable';fixture=output/'go-folder-http-pg.json'
  count=151
  with (output/'go.log').open('wb') as log:command(['go','run','-tags','sqlite_fts5','backend-java/src/test/fixtures/folder_http_fixture.go',str(fixture)],env=env,stdout=log,stderr=subprocess.STDOUT)
  assert len(json.loads(fixture.read_text()))==count
  with (output/'java.log').open('wb') as log:command(['mvn','-q','-f','backend-java/pom.xml','-Dweknora.build.directory='+str(output/'build'),'-Dweknora.folder.pg.url=jdbc:postgresql://127.0.0.1:'+port+'/weknora_folder_parity','-Dweknora.folder.pg.fixture='+str(fixture),'-Dtest='+'KnowledgeFolderPostgresGoParityIT','clean','test'],stdout=log,stderr=subprocess.STDOUT)
  print(json.dumps({'cases':count,'go_migrations':len(migrations),'pool_size':1,'checks':'actual Go folder listing/rename/move handlers and KB/role/API-scope middleware vs native MVC; complete tree/DTO/binder/diagnostics/SQL projection and denial audits; physical GIN sentinel/chunk/document/quota rows retained and no queue admission','artifacts':str(output)}))
 finally:
  if container:subprocess.run([args.docker,'stop',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
  print('Folder parity artifacts: '+str(output))
if __name__=='__main__':main()
