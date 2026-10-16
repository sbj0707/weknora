#!/usr/bin/env python3
"""Compare explicit native entry points; their presence does not prove parity.

Runs the Go AST inventory without rewriting the migration ledger. Optional
Surefire reports must come from a separately observed successful Maven run.
"""
from pathlib import Path
from collections import Counter
from datetime import datetime
import argparse
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "backend-java/migration"


def arguments(source, start):
    while start < len(source) and source[start].isspace():
        start += 1
    if start == len(source) or source[start] != "(":
        return ""
    depth, quoted, escaped = 0, False, False
    for end in range(start, len(source)):
        char = source[end]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[start + 1:end]
    raise ValueError("Unbalanced Java mapping annotation")


def paths(args):
    match = re.search(r'(?:\bvalue|\bpath)\s*=\s*(\{[^}]*\}|"(?:\\.|[^"\\])*")', args)
    content = match[1] if match else args if args.lstrip().startswith(('"', '{')) else ''
    return [json.loads(x) for x in re.findall(r'"(?:\\.|[^"\\])*"', content)] or ['']


def normalize(path):
    return re.sub(r'\{[^}]+\}|:[^/]+', ':parameter', path).rstrip('/') or '/'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tests-report', type=Path)
    args = parser.parse_args()
    ast = json.loads(subprocess.check_output(
        ['go', 'run', str(MIGRATION / 'inventory_ast.go'), str(ROOT)], cwd=ROOT))
    if ast.get('unresolved') or ast.get('unvisited_registration_sites'):
        raise ValueError('Incomplete Go route inventory')
    mappings, handlers = [], {}
    for path in sorted((ROOT / 'backend-java/src/main/java').rglob('*.java')):
        source = path.read_text()
        if re.search(r'implements\s+TaskHandler\b', source):
            match = re.search(r'String\s+type\s*\(\)\s*\{\s*return\s*"([^"]+)"', source)
            if match:
                handlers[match[1]] = str(path.relative_to(ROOT))
        if '@RestController' not in source:
            continue
        class_at = re.search(r'\bclass\s+\w+', source)
        prefix = ''
        for ann in re.finditer(r'@(Get|Post|Put|Delete|Patch|Request)Mapping\b', source):
            values, kind = arguments(source, ann.end()), ann[1]
            if ann.start() < class_at.start():
                if kind == 'Request':
                    prefix = paths(values)[0]
                continue
            methods = re.findall(r'RequestMethod\.([A-Z]+)', values) if kind == 'Request' else [kind.upper()]
            for method in methods:
                for endpoint in paths(values):
                    mappings.append(dict(method=method, path=(prefix + endpoint).rstrip('/') or '/',
                                         source=str(path.relative_to(ROOT)), line=source.count('\n', 0, ann.start()) + 1))
    explicit = {(row['method'], normalize(row['path'])) for row in mappings}
    matched, missing, modules = [], [], {}
    status = json.loads((MIGRATION / 'status.json').read_text())['routes']
    drift = []
    for route in ast['routes']:
        pattern = re.escape(normalize(route['path'])).replace(':parameter', '[^/]+')
        found = any(method == route['method'] and re.fullmatch(pattern, path) for method, path in explicit)
        (matched if found else missing).append(route)
        module = modules.setdefault(route['function'], dict(go=0, java_entry=0))
        module['go'] += 1
        module['java_entry'] += int(found)
        key = route['method'] + ' ' + route['path']
        state = status.get(key, 'not_started')
        if found and state == 'not_started' or not found and state in ('in_progress', 'implemented', 'verified'):
            drift.append(dict(route=key, ledger_status=state, has_java_entry=found))
    go_tasks = set(re.findall(r'\bType\w+\s*=\s*"([\w:]+)"', (ROOT / 'internal/types/task.go').read_text()))
    inputs = [ROOT / 'backend-java/pom.xml', *(ROOT / 'backend-java/src').rglob('*')]
    hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs if p.is_file()}
    result = dict(date=datetime.now().astimezone().isoformat(), conclusion='not_equivalent',
                  method='Fresh Go AST / explicit Spring annotations / concrete TaskHandler type declarations. Static presence is not functional parity; conditional configuration and runtime side effects require separate verification.',
                  go_routes=len(ast['routes']), registration_sites=ast['candidate_registration_sites'],
                  visited_registration_sites=ast['visited_registration_sites'],
                  java_explicit_method_paths=len(mappings), go_routes_with_java_entry=len(matched),
                  missing_native_entries=len(missing), entry_coverage_percent=round(100*len(matched)/len(ast['routes']), 1),
                  ledger_counts=dict(Counter(status.get(r['method']+' '+r['path'], 'not_started') for r in ast['routes'])),
                  ledger_disagreements=drift, modules=dict(sorted(modules.items())),
                  go_task_types=len(go_tasks), java_partial_handler_types=len(go_tasks & handlers.keys()),
                  java_handler_sources=handlers, missing_handlers=sorted(go_tasks-handlers.keys()),
                  java_mappings=mappings, matched=matched, without_match=missing,
                  current_input_hashes=hashes, current_full_suite_rerun=False,
                  operational_changes=dict(service_restarted=False, jar_deployed=False, frontend_switched=False))
    if args.tests_report:
        files = sorted(args.tests_report.glob('TEST-*.xml'))
        if not files:
            raise ValueError('No Surefire reports')
        counts = Counter()
        for file in files:
            xml = ET.parse(file).getroot()
            counts.update({k: int(xml.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')})
        result['current_focused_tests'] = dict(directory=str(args.tests_report), classes=len(files), **counts,
            report_hashes={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: v for k, v in result.items() if k not in (
        'java_mappings', 'matched', 'without_match', 'current_input_hashes', 'java_handler_sources', 'current_focused_tests')}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
