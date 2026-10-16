#!/usr/bin/env python3
"""Read-only packaged-service probe; credentials remain in environment variables."""
import argparse
import datetime
import json
import os
import urllib.parse
import urllib.request


def walk(node):
    yield node
    for child in node.get('children', []):
        yield from walk(child)


def stable_trace(node):
    result = dict(node)
    if not result['span_id']:
        # Each HTTP request creates new placeholders at its own wall-clock time.
        assert result['created_at'] == result['updated_at']
        datetime.datetime.fromisoformat(result['created_at'].replace('Z', '+00:00'))
        result.pop('created_at')
        result.pop('updated_at')
    if 'children' in result:
        result['children'] = [stable_trace(child) for child in result['children']]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8081')
    parser.add_argument('--knowledge-id', required=True)
    parser.add_argument('--attempt', type=int)
    parser.add_argument('--require-native-records', action='store_true', help='Require persisted spans; provenance must be established by the invoking pipeline test.')
    args = parser.parse_args()
    headers = {'Accept': 'application/json'}
    if key := os.environ.get('JAVA_SPAN_PROBE_API_KEY'):
        headers['X-API-Key'] = key
    elif token := os.environ.get('JAVA_SPAN_PROBE_ACCESS_TOKEN'):
        headers['Authorization'] = 'Bearer ' + token
    else:
        parser.error('Set JAVA_SPAN_PROBE_API_KEY or JAVA_SPAN_PROBE_ACCESS_TOKEN')
    results = []
    for alias in ('spans', 'stages'):
        url = args.base_url.rstrip('/') + '/api/v1/knowledge/' + urllib.parse.quote(args.knowledge_id, safe='') + '/' + alias
        if args.attempt is not None:
            url += '?' + urllib.parse.urlencode({'attempt': args.attempt})
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
            assert response.status == 200
            body = json.load(response)
        assert body['success'] is True
        data = body['data']
        assert data['knowledge_id'] == args.knowledge_id
        assert data['attempt'] == data['current_attempt']
        nodes = list(walk(data['trace']))
        assert all('error_detail' not in node and 'id' not in node for node in nodes)
        stage_names = {node['name'] for node in data['trace'].get('children', []) if node['kind'] == 'stage'}
        assert {'docreader', 'chunking', 'embedding', 'multimodal', 'postprocess'} <= stage_names
        persisted = sum(bool(node['span_id']) for node in nodes)
        if args.require_native_records:
            assert persisted > 0, 'No persisted tracing rows; native task tracker integration is still missing'
        normalized = dict(data)
        normalized['trace'] = stable_trace(data['trace'])
        results.append(normalized)
        print(json.dumps({'endpoint': alias, 'attempt': data['attempt'], 'latest_attempt': data['latest_attempt'], 'parse_status': data['parse_status'], 'root_status': data['trace']['status'], 'persisted_spans': persisted}, ensure_ascii=False))
    assert results[0] == results[1], 'spans/stages returned different persisted trace data; retry while the pipeline is idle'


if __name__ == '__main__':
    main()
