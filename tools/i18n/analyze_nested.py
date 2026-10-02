import json, re, io, os
BASE = os.path.dirname(os.path.abspath(__file__))
lits = json.load(io.open(os.path.join(BASE, 'literals.json'), encoding='utf-8'))
cjk = re.compile(r'[\u4e00-\u9fff]')
nested = []
for l in lits:
    for kind, val in l['segments']:
        if kind == 'expr' and cjk.search(val):
            nested.append((l['file'].split('/')[-1], l['line'], val))
print('expr segments with CJK:', len(nested))
for e in nested[:15]:
    print(e)
# is 未知错误 a standalone literal?
print('standalone 未知错误 exists:', any(l['raw'] == '未知错误' for l in lits))
# which top-level literals contain nested CJK in exprs
