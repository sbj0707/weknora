#!/usr/bin/env python3
"""Execute actual Go move preflight, metadata and CAS via an isolated test overlay."""
import argparse,json,os,subprocess,tempfile
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2])
    args=parser.parse_args();root=args.root.resolve();output=args.output.resolve()
    with tempfile.TemporaryDirectory(prefix='weknora-move-plan-overlay-') as directory:
        work=Path(directory);fixture=work/'java_move_plan_fixture_test.go'
        original=root/'backend-java/src/test/fixtures/knowledge_move_plan_fixture_test.go'
        fixture.write_text(original.read_text().replace('//go:build ignore\n','',1))
        overlay=work/'overlay.json';overlay.write_text(json.dumps({'Replace':{str(root/'internal/application/service/java_move_plan_fixture_test.go'):str(fixture)}}))
        env=os.environ.copy();env['WEKNORA_MOVE_PLAN_ORACLE']=str(output)
        subprocess.run(['go','test','-overlay',str(overlay),'-tags','sqlite_fts5','./internal/application/service','-run','^TestJavaKnowledgeMovePlanExport$','-count=1'],cwd=root,env=env,check=True)
        result=json.loads(output.read_text());print(json.dumps({k:len(v) for k,v in result.items()}))

if __name__=='__main__':main()
