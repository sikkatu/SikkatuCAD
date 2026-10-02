import json, re, io, os
BASE = os.path.dirname(os.path.abspath(__file__))
lits = json.load(io.open(os.path.join(BASE, 'literals.json'), encoding='utf-8'))
pat = re.compile(r'\\s|\(\?i\)|\(\?:|\\d|\\w|\[A-Z\]')
seen = set()
print('=== literals NOT resourceable (regex-ish) ===')
for l in lits:
    raw = l['raw']
    if raw in seen: continue
    seen.add(raw)
    if pat.search(raw):
        print('---', l['file'].split('/')[-1], 'line', l['line'])
        print(repr(raw)[:200])
print()
print('=== literals with .format( or % usage ===')
seen2 = set()
for l in lits:
    raw = l['raw']
    if raw in seen2: continue
    seen2.add(raw)
    if '%' in raw.replace('%1$s','').replace('%2$s','') or '%.2f' in raw or '%d' in raw or '%s' in raw:
        print('---', l['file'].split('/')[-1], 'line', l['line'], repr(raw)[:120])
