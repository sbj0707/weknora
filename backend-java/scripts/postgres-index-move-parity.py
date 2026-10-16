#!/usr/bin/env python3
"""Compare native physical SQL index moves, rollback, retry and retrieval with actual Go using an owned full-schema PostgreSQL DB."""
import argparse,json,os,subprocess,tempfile,time,uuid
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image',default='paradedb/paradedb:v0.22.6-pg17')
    parser.add_argument('--docker',default='docker')
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2])
    args=parser.parse_args();root=args.root.resolve()
    output=Path(tempfile.mkdtemp(prefix='weknora-postgres-index-move-'));container=None
    def command(values,**kwargs):return subprocess.run(values,cwd=root,check=True,**kwargs)
    try:
        container=command([args.docker,'run','-d','--rm','--name','weknora-java-index-move-'+uuid.uuid4().hex[:12],'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD=local-parity-only','-e','POSTGRES_DB=weknora_index_move_parity',args.image],capture_output=True,text=True).stdout.strip()
        deadline=time.monotonic()+45
        while subprocess.run([args.docker,'exec',container,'pg_isready','-h','127.0.0.1','-U','postgres','-d','weknora_index_move_parity'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
            if time.monotonic()>deadline:raise RuntimeError('Owned PG startup deadline')
            time.sleep(.2)
        port=command([args.docker,'port',container,'5432'],capture_output=True,text=True).stdout.strip().rsplit(':',1)[1]
        migrations=sorted((root/'migrations/versioned').glob('*.up.sql'))
        with (output/'schema.log').open('wb') as log:
            for migration in migrations+[root/'backend-java/migration/sql/java-tasks.sql']:
                log.write((str(migration)+'\n').encode());log.flush()
                command([args.docker,'exec','-i',container,'psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','weknora_index_move_parity'],input=migration.read_bytes(),stdout=log,stderr=subprocess.STDOUT)
        env=os.environ.copy();env['WEKNORA_INDEX_MOVE_PG_DSN']=f'postgres://postgres:local-parity-only@127.0.0.1:{port}/weknora_index_move_parity?sslmode=disable'
        fixture=output/'go-index-move-pg.json'
        with (output/'go.log').open('wb') as log:
            command(['go','run','-tags','sqlite_fts5','backend-java/src/test/fixtures/knowledge_index_move_fixture.go',str(fixture)],env=env,stdout=log,stderr=subprocess.STDOUT)
        rows=json.loads(fixture.read_text());assert len(rows)==13
        with (output/'java.log').open('wb') as log:
            command(['mvn','-q','-f','backend-java/pom.xml','-Dweknora.build.directory='+str(output/'build'),'-Dweknora.index.move.pg.url=jdbc:postgresql://127.0.0.1:'+port+'/weknora_index_move_parity','-Dweknora.index.move.fixture='+str(fixture),'-Dtest=KnowledgeIndexMovePostgresGoParityIT','clean','test'],stdout=log,stderr=subprocess.STDOUT)
        print(json.dumps({'cases':len(rows),'go_migrations':len(migrations),'pool_size':1,'checks':'Actual Go physical metadata moves, stable halfvec/FTS identities, exact scope, disabled rows, SQL failure/rollback and source/destination retrieval sets','artifacts':str(output)}))
    finally:
        if container:subprocess.run([args.docker,'stop',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
        print('Index-move parity artifacts: '+str(output))

if __name__=='__main__':main()
