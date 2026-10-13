"""Prepare pinned official data. Gold labels never enter the runner's input file."""
import argparse
import ast
import collections
import concurrent.futures
import csv
import hashlib
import json
import pathlib
import urllib.request
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = ROOT / '.memory-eval' / 'data'


def fetch(url, destination, expected_size=None, headers=None):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and expected_size and destination.stat().st_size != expected_size:
        destination.replace(destination.with_suffix(f'.incomplete-{destination.stat().st_size}'))
    if not destination.exists():
        partial = destination.with_name(destination.name + '.part')
        for attempt in range(4):
            try:
                request = urllib.request.Request(url, headers=headers or {})
                with urllib.request.urlopen(request, timeout=60) as response, partial.open('wb') as output:
                    if headers and 'Range' in headers and response.status != 206:
                        raise ValueError('Server did not honor byte range')
                    advertised = response.headers.get('Content-Length')
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
                size = partial.stat().st_size
                if (expected_size and size != expected_size) or (advertised and size != int(advertised)):
                    raise ValueError('Incomplete HTTP response')
                partial.replace(destination)
                break
            except Exception:
                if attempt == 3:
                    raise
                time.sleep(2 ** attempt)
    if expected_size and destination.stat().st_size != expected_size:
        raise ValueError(f'Incomplete dataset download: {destination.stat().st_size}/{expected_size}; refusing partial data')
    return destination


def fetch_large(url, destination, size):
    if destination.exists() and destination.stat().st_size == size:
        return destination
    parts = destination.with_suffix('.parts')
    parts.mkdir(exist_ok=True)
    chunk_size = 8 * 1024 * 1024
    offsets = list(range(0, size, chunk_size))
    def part(start):
        end = min(size, start + chunk_size) - 1
        return fetch(url + f'?part={start}', parts / str(start), end - start + 1,
                     {'Range': f'bytes={start}-{end}'})
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        files = list(pool.map(part, offsets))
    partial = destination.with_suffix('.assembled')
    with partial.open('wb') as output:
        for file in files:
            with file.open('rb') as stream:
                while chunk := stream.read(1024 * 1024):
                    output.write(chunk)
    if partial.stat().st_size != size:
        raise ValueError('Assembled dataset size mismatch')
    partial.replace(destination)
    return destination


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def select(rows, count, seed):
    """Round-robin strata with hash-based ordering, independent of input order."""
    groups = collections.defaultdict(list)
    for row in rows:
        groups[row['category']].append(row)
    if not len(groups) <= count <= len(rows):
        raise ValueError('Sample size must cover all categories and not exceed dataset size')
    for group in groups.values():
        group.sort(key=lambda row: hashlib.sha256(f'{seed}:{row["id"]}'.encode()).hexdigest())
    selected = []
    while len(selected) < count:
        for category in sorted(groups):
            if groups[category] and len(selected) < count:
                selected.append(groups[category].pop(0))
    return selected


def prepare_longmem(count=56, seed=20261013):
    api = 'https://huggingface.co/api/datasets/xiaowu0162/longmemeval-cleaned'
    metadata = CACHE / 'longmemeval-revision.json'
    fetch(api, metadata)
    revision = json.loads(metadata.read_text(encoding='utf-8'))['sha']
    url = f'https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned/resolve/{revision}/longmemeval_s_cleaned.json'
    source = fetch_large(url, CACHE / f'longmemeval-s-{revision}.json', 277383467)
    raw = json.loads(source.read_text(encoding='utf-8'))
    indexed = [{'id': row['question_id'], 'category': 'abstention' if row['question_id'].endswith('_abs')
                else row['question_type'], 'raw': row} for row in raw]
    selected = select(indexed, count, seed)
    inputs, gold = [], []
    for item in selected:
        row = item['raw']
        if not len(row['haystack_session_ids']) == len(row['haystack_dates']) == len(row['haystack_sessions']):
            raise ValueError('Misaligned sessions')
        sessions = [{'id': sid, 'date': date, 'turns': [{'role': t['role'], 'content': t['content']} for t in turns]}
                    for sid, date, turns in zip(row['haystack_session_ids'], row['haystack_dates'], row['haystack_sessions'])]
        inputs.append({'id': item['id'], 'category': item['category'], 'question': row['question'],
                       'questionDate': row['question_date'], 'sessions': sessions})
        gold.append({'question_id': item['id'], 'question_type': row['question_type'], 'category': item['category'],
                     'question': row['question'], 'answer': row['answer'], 'answer_session_ids': row['answer_session_ids']})
    output = CACHE / f'longmemeval-s-{count}-seed{seed}'
    output.mkdir(exist_ok=True)
    for name, value in [('input.json', inputs), ('gold.json', gold)]:
        (output / name).write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
    manifest = {'benchmark': 'LongMemEval-S cleaned', 'source_url': url, 'revision': revision,
                'source_sha256': digest(source), 'input_sha256': digest(output / 'input.json'), 'seed': seed,
                'count': count, 'categories': dict(collections.Counter(i['category'] for i in inputs)),
                'question_ids': [i['id'] for i in inputs], 'history_truncated': False,
                'sessions': sum(len(i['sessions']) for i in inputs),
                'unique_user_turns': len({t['content'] for i in inputs for s in i['sessions'] for t in s['turns'] if t['role'] == 'user'}),
                'user_turns': sum(t['role'] == 'user' for i in inputs for s in i['sessions'] for t in s['turns'])}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in manifest.items() if k != 'question_ids'}, indent=2))


def save_bundle(name, inputs, gold, provenance):
    output = CACHE / name
    output.mkdir(parents=True, exist_ok=True)
    for filename, rows in [('input.json', inputs), ('gold.json', gold)]:
        (output / filename).write_text(json.dumps(rows, ensure_ascii=False), encoding='utf-8')
    manifest = dict(provenance, count=len(inputs), input_sha256=digest(output / 'input.json'),
                    categories=dict(collections.Counter(i['category'] for i in inputs)),
                    question_ids=[i['id'] for i in inputs],
                    unique_user_turns=len({t['content'] for i in inputs for s in i['sessions'] for t in s['turns'] if t['role'] == 'user'}),
                    user_turns=sum(t['role'] == 'user' for i in inputs for s in i['sessions'] for t in s['turns']))
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in manifest.items() if k != 'question_ids'}, indent=2))


def prepare_persona(count, seed):
    metadata = fetch('https://huggingface.co/api/datasets/bowen-upenn/PersonaMem', CACHE / 'personamem-revision.json')
    revision = json.loads(metadata.read_text(encoding='utf-8'))['sha']
    base = f'https://huggingface.co/datasets/bowen-upenn/PersonaMem/resolve/{revision}/'
    questions = fetch(base + 'questions_32k.csv', CACHE / f'persona-{revision}.csv')
    contexts = fetch(base + 'shared_contexts_32k.jsonl', CACHE / f'persona-{revision}.jsonl')
    with questions.open(encoding='utf-8', newline='') as stream:
        rows = [{'id': r['question_id'], 'category': r['question_type'], 'raw': r} for r in csv.DictReader(stream)]
    shared = {}
    for line in contexts.read_text(encoding='utf-8').splitlines():
        shared.update(json.loads(line))
    inputs, gold = [], []
    for item in select(rows, count, seed):
        row = item['raw']
        history = shared[row['shared_context_id']]
        end = int(row['end_index_in_shared_context'])
        if not 0 < end <= len(history):
            raise ValueError('Invalid PersonaMem history cutoff')
        options = ast.literal_eval(row['all_options'])
        if not isinstance(options, list) or not all(isinstance(o, str) for o in options):
            raise ValueError('Invalid choices')
        question = row['user_question_or_message'] + '\nChoose the best personalized response. Return only its label (a), (b), (c), or (d).\n' + '\n'.join(options)
        # Future turns beyond the official cutoff and correctness labels are not part of the input.
        inputs.append({'id': item['id'], 'category': item['category'], 'question': question, 'questionDate': '',
                       'sessions': [{'id': row['shared_context_id'], 'date': '',
                                     'turns': [{'role': t['role'], 'content': t['content']} for t in history[:end]]}]})
        gold.append({'question_id': item['id'], 'category': item['category'], 'answer': row['correct_answer'],
                     'question': row['user_question_or_message'], 'topic': row['topic']})
    save_bundle(f'personamem-32k-{count}-seed{seed}', inputs, gold,
                {'benchmark': 'PersonaMem v1 32k', 'revision': revision, 'seed': seed,
                 'source_sha256': {questions.name: digest(questions), contexts.name: digest(contexts)},
                 'history_cutoff': 'official end_index_in_shared_context; system persona is not extracted'})


def prepare_memora():
    metadata = fetch('https://api.github.com/repos/geniesinc/Memora/commits/main', CACHE / 'memora-revision.json')
    revision = json.loads(metadata.read_text(encoding='utf-8'))['sha']
    path = 'data/weekly/software_engineer'
    base = f'https://raw.githubusercontent.com/geniesinc/Memora/{revision}/{path}/'
    directory = CACHE / f'memora-{revision}'
    questions = fetch(base + 'evaluation_questions_software_engineer.json', directory / 'questions.json')
    listing = fetch(f'https://api.github.com/repos/geniesinc/Memora/contents/{path}/conversations?ref={revision}', directory / 'listing.json')
    names = sorted(i['name'] for i in json.loads(listing.read_text(encoding='utf-8')) if i['name'].endswith('.json'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        files = list(pool.map(lambda name: fetch(base + 'conversations/' + name, directory / name), names))
    sessions = []
    for file in files:
        row = json.loads(file.read_text(encoding='utf-8'))
        sessions.append({'id': str(row['session_id']), 'date': row['date'],
                         'turns': [{'role': 'assistant' if t['speaker'] == 'ai_agent' else 'user', 'content': t['message']}
                                   for t in row['conversation']]})
    inputs, gold = [], []
    for category, rows in json.loads(questions.read_text(encoding='utf-8'))['questions'].items():
        for row in rows:
            inputs.append({'id': row['question_id'], 'category': category, 'question': row['question'],
                           'questionDate': row['question_date'], 'sessions': [s for s in sessions if s['date'] <= row['question_date']]})
            gold.append({'question_id': row['question_id'], 'category': category, 'question': row['question'],
                         'evaluation': row['evaluation']})
    save_bundle('memora-weekly-software-engineer', inputs, gold,
                {'benchmark': 'Memora weekly software_engineer', 'revision': revision,
                 'source_sha256': {f.name: digest(f) for f in [questions, *files]},
                 'operations': 'natural language only; no oracle-driven delete/correct calls'})


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--count', type=int, default=56)
    parser.add_argument('--seed', type=int, default=20261013)
    parser.add_argument('--benchmark', choices=['longmemeval', 'personamem', 'memora'], default='longmemeval')
    args = parser.parse_args()
    if args.benchmark == 'longmemeval':
        prepare_longmem(args.count, args.seed)
    elif args.benchmark == 'personamem':
        prepare_persona(args.count, args.seed)
    else:
        prepare_memora()
