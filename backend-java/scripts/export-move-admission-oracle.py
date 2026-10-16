#!/usr/bin/env python3
"""Export the actual Go public move handler, API key gate and role guard without editing production files."""
import argparse,json,os,subprocess,tempfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args();root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='weknora-move-admission-') as d:
 work=Path(d);fixture=work/'fixture_test.go';fixture.write_text((root/'backend-java/src/test/fixtures/knowledge_move_admission_fixture_test.go').read_text().replace('//go:build ignore\n','',1));overlay=work/'overlay.json';overlay.write_text(json.dumps({'Replace':{str(root/'internal/handler/java_knowledge_move_admission_fixture_test.go'):str(fixture)}}));env=os.environ.copy();env['WEKNORA_MOVE_ADMISSION_ORACLE']=str(a.output.resolve());subprocess.run(['go','test','-overlay',str(overlay),'-tags','sqlite_fts5','./internal/handler','-run','^TestJavaKnowledgeMoveAdmissionExport$','-count=1'],cwd=root,env=env,check=True)
 print(json.dumps({'cases':len(json.loads(a.output.read_text()))}))
