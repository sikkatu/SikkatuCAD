import json, io, os, glob
BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, '..', '..', 'app', 'src', 'main', 'java')
# build basename -> full path map
pathmap = {}
for root, dirs, fs in os.walk(SRC):
    for fn in fs:
        if fn.endswith('.kt'):
            pathmap[fn] = os.path.join(root, fn)
lits = json.load(io.open(os.path.join(BASE, 'literals.json'), encoding='utf-8'))
ok = bad = 0
for l in lits:
    fn = l['file']
    full = pathmap.get(fn) or pathmap.get(os.path.basename(fn))
    if not full:
        print('NO PATH for', fn); continue
    if 'content' not in l.__class__.__name__: pass
for fn in set(l['file'] for l in lits):
    full = pathmap.get(os.path.basename(fn))
    content = io.open(full, encoding='utf-8').read()
    items = [l for l in lits if os.path.basename(l['file']) == os.path.basename(fn)]
    fok = fbad = 0
    for l in items:
        s, e = l['offset'], l['end']
        if content[s:e] == l['raw']:
            fok += 1
        else:
            fbad += 1
            if fbad <= 3:
                print('MISMATCH', fn, 'line', l['line'])
                print('  raw  :', repr(l['raw'])[:90])
                print('  slice:', repr(content[s:e])[:90])
    print('%-24s total=%d ok=%d bad=%d' % (fn, len(items), fok, fbad))
    ok += fok; bad += fbad
print('TOTAL OK:', ok, 'BAD:', bad, '=> offsets are', 'CHAR-BASED' if bad==0 else 'PROBLEM')
