import json, io, os
BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, '..', '..', 'app', 'src', 'main', 'java')
lits = json.load(io.open(os.path.join(BASE, 'literals.json'), encoding='utf-8'))
# group by file
files = {}
for l in lits:
    files.setdefault(l['file'], []).append(l)
print('files in literals.json:')
for f in files: print('  ', f, len(files[f]), 'literals')
# verify offsets per file (char-based)
print('=== offset verification (char slicing) ===')
ok = bad = 0
for fpath, items in files.items():
    try:
        content = io.open(fpath, encoding='utf-8').read()
    except Exception as e:
        print('CANNOT READ', fpath, e); continue
    for l in items:
        s, e = l['offset'], l['end']
        if content[s:e] == l['raw']:
            ok += 1
        else:
            bad += 1
            if bad <= 5:
                print('MISMATCH', os.path.basename(fpath), 'line', l['line'])
                print('  raw   :', repr(l['raw'])[:80])
                print('  slice :', repr(content[s:e])[:80])
print('OK:', ok, 'BAD:', bad)
# comments.json structure
cmts = json.load(io.open(os.path.join(BASE, 'comments.json'), encoding='utf-8'))
print('=== comments.json === type:', type(cmts), 'len:', len(cmts))
if isinstance(cmts, list):
    print('elem0 type:', type(cmts[0]))
    print('elem0:', json.dumps(cmts[0], ensure_ascii=False)[:300] if isinstance(cmts[0], (dict,list)) else repr(cmts[0])[:200])
    if isinstance(cmts[0], dict): print('keys:', list(cmts[0].keys()))
