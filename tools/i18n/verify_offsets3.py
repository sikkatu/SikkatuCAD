import json, io, os
BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, '..', '..', 'app', 'src', 'main', 'java')
pathmap = {}
for root, dirs, fs in os.walk(SRC):
    for fn in fs:
        if fn.endswith('.kt'):
            pathmap[fn] = os.path.join(root, fn)
lits = json.load(io.open(os.path.join(BASE, 'literals.json'), encoding='utf-8'))
ok = bad = 0
for l in lits:
    full = pathmap[os.path.basename(l['file'])]
    content = io.open(full, encoding='utf-8').read()
    s, e = l['offset'], l['end']
    slice_ = content[s:e]
    if slice_ == '"' + l['raw'] + '"' or slice_ == "'" + l['raw'] + "'":
        ok += 1
    else:
        bad += 1
        if bad <= 5:
            print('MISMATCH', l['file'], l['line'])
            print('  raw  :', repr(l['raw'])[:90])
            print('  slice:', repr(slice_)[:90])
print('quoted-match OK:', ok, 'BAD:', bad)
# also check sorted non-overlap per file
for fn in set(os.path.basename(l['file']) for l in lits):
    items = sorted([l for l in lits if os.path.basename(l['file']) == fn], key=lambda x: x['offset'])
    overlaps = 0
    prev_end = -1
    for l in items:
        if l['offset'] < prev_end:
            overlaps += 1
        prev_end = max(prev_end, l['end'])
    print('%-24s items=%d overlaps=%d' % (fn, len(items), overlaps))
