import json, re, io, os
BASE = os.path.dirname(os.path.abspath(__file__))
res = json.load(io.open(os.path.join(BASE, 'res_map.json'), encoding='utf-8'))
lits = json.load(io.open(os.path.join(BASE, 'literals.json'), encoding='utf-8'))
# find literals whose TEXT segments contain %.Nf (not inside exprs)
for l in lits:
    specs = []
    for kind, val in l['segments']:
        if kind == 'txt':
            specs += re.findall(r'%[.0-9]*[sfdx]%', val.replace('%%','')) if False else re.findall(r'%(?!\d\$)[.0-9]*[sfdx]', val)
    if specs:
        key = next((k for k,v in res.items() if v['zh']==l['raw']), None)
        if not key: continue
        print('---', l['file'].split('/')[-1], l['line'], 'specs:', specs, 'key:', key)
        print('  zh:', l['raw'])
        print('  en:', res[key]['en'])
        print('  ru:', res[key]['ru'])
        print('  zh_res:', res[key].get('zh'))
        # check translated specs count (excluding %N$s)
        for lang in ('en','ru'):
            t2 = re.sub(r'%\d+\$s', '', res[key][lang])
            ns = re.findall(r'%(?!\d\$)[.0-9]*[sfdx]', t2)
            if len(ns) != len(specs):
                print('  !! MISMATCH', lang, ns)
