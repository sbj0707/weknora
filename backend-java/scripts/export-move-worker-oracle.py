#!/usr/bin/env python3
"""Run the production Go move worker using isolated overlays and actual SQL/Redis repositories."""
import argparse, json, os, subprocess, tempfile, shutil
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args();root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='weknora-move-worker-overlay-') as directory:
 work=Path(directory);replacements={}
 for name in ['knowledge_move_plan_fixture_test.go','knowledge_move_execution_fixture_test.go','knowledge_move_worker_fixture_test.go']:
  target=work/name;target.write_text((root/'backend-java/src/test/fixtures'/name).read_text().replace('//go:build ignore\n','',1));replacements[str(root/'internal/application/service'/('java_'+name))]=str(target)
 module=Path(subprocess.check_output(['go','list','-m','-f','{{.Dir}}','github.com/hibiken/asynq'],cwd=root,text=True).strip())
 dependency=work/'asynq';shutil.copytree(module,dependency);dependency.chmod(0o755);helper=dependency/'java_move_fixture_context.go';helper.write_text((root/'backend-java/src/test/fixtures/asynq_move_context_fixture.go').read_text().replace('//go:build ignore\n','',1))
 modfile=work/'go.mod';shutil.copyfile(root/'go.mod',modfile);shutil.copyfile(root/'go.sum',work/'go.sum');subprocess.run(['go','mod','edit','-modfile',str(modfile),'-replace','github.com/hibiken/asynq='+str(dependency)],cwd=root,check=True)
 overlay=work/'overlay.json';overlay.write_text(json.dumps({'Replace':replacements}));env=os.environ.copy();env['WEKNORA_MOVE_WORKER_ORACLE']=str(a.output.resolve());env['WEKNORA_MOVE_NAMED_ORACLE']=str(a.output.resolve().with_name(a.output.stem+'-named.json'));env['WEKNORA_MOVE_PROGRESS_ORACLE']=str(a.output.resolve().with_name(a.output.stem+'-progress.json'))
 subprocess.run(['go','test','-modfile',str(modfile),'-overlay',str(overlay),'-tags','sqlite_fts5','./internal/application/service','-run','^TestJavaKnowledgeMove(Worker|Progress|NamedQueue)Export$','-count=1'],cwd=root,env=env,check=True)
 print(json.dumps({'cases':len(json.loads(a.output.read_text()))}))
