import json, re, io, os
BASE = os.path.dirname(os.path.abspath(__file__))
lits = json.load(io.open(os.path.join(BASE, 'literals.json'), encoding='utf-8'))
res = json.load(io.open(os.path.join(BASE, 'res_map.json'), encoding='utf-8'))
# original format-spec tokens in each zh literal
spec = re.compile(r'%[.0-9]*[sfdx]')
problems = []
seen = set()
for l in lits:
    raw = l['raw']
    if raw in seen: continue
    seen.add(raw)
    key = None
    for k, v in res.items():
        if v['zh'] == raw:
            key = k; break
    if key is None: continue
    orig_specs = spec.findall(raw.replace('%1$s','').replace('%2$s',''))
    for lang in ('en','ru'):
        t = res[key][lang]
        # strip android positional placeholders %1$s
        t2 = re.sub(r'%\d+\$s', '', t)
        new_specs = spec.findall(t2)
        if len(orig_specs) != len(new_specs):
            problems.append((key, lang, raw[:60], orig_specs, new_specs, t[:80]))
print('=== spec count mismatches ===', len(problems))
for p in problems[:25]:
    print(p[0], p[1], '| zh:', p[2], '| orig specs:', p[3], '| new:', p[4])
    print('    ->', p[5])
