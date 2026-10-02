import json, re, io, os
BASE = os.path.dirname(os.path.abspath(__file__))
lits = json.load(io.open(os.path.join(BASE, 'literals.json'), encoding='utf-8'))
res = json.load(io.open(os.path.join(BASE, 'res_map.json'), encoding='utf-8'))
bare = re.compile(r'\$[A-Za-z_][A-Za-z0-9_]*')
full = re.compile(r'\$\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}')
print('=== literals with bare $var in raw ===')
seen = set()
for l in lits:
    raw = l['raw']
    if raw in seen: continue
    seen.add(raw)
    # strip ${...} first
    stripped = full.sub('', raw)
    b = bare.findall(stripped)
    if b:
        key = next((k for k,v in res.items() if v['zh']==raw), None)
        print('key=%s line=%d %s' % (key, l['line'], l['file'].split('/')[-1]))
        print('   raw:', raw[:100])
        print('   bare vars:', b)
        print('   exprs recorded:', res[key]['exprs'] if key else '??')
print()
print('=== literals whose TEXT has literal % (format-danger when placeholders present) ===')
for l in lits:
    raw = l['raw']
    for kind, val in l['segments']:
        if kind == 'txt':
            # remove %.Nf / %s style specs (used with .format())
            t = re.sub(r'%[.0-9]*[sfdx]', '', val)
            if '%' in t:
                key = next((k for k,v in res.items() if v['zh']==raw), None)
                print('key=%s line=%d %s' % (key, l['line'], l['file'].split('/')[-1]))
                print('   txt seg:', repr(val)[:120])
                break
