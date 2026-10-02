#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Step 1: translate unique Chinese strings/comments to EN+RU (cached),
generate Android string resources (values/, values-ru/, values-zh/),
and emit tools/i18n/res_map.json for the code transformer."""
import re, io, json, os, time, urllib.request, urllib.parse

BASE = os.path.dirname(os.path.abspath(__file__))
SENT = '\uE000'

literals = json.load(io.open(os.path.join(BASE, 'literals.json'), encoding='utf-8'))
comments = json.load(io.open(os.path.join(BASE, 'comments.json'), encoding='utf-8'))

OVERRIDE = {
    "FFD (首次适配递减)": ("FFD (First Fit Decreasing)", "FFD (первый подходящий, по убыванию)"),
    "按零件面积从大到小排列，对每个零件尝试4个旋转角度": (
        "Sort parts by area (largest first); try 4 rotation angles for each part",
        "Сортировка деталей по площади (от больших к меньшим); 4 угла поворота на деталь"),
    "BestFit (最优适配)": ("Best Fit", "Наилучшее заполнение"),
    "对每个零件选择浪费空间最少的放置位置": (
        "Choose the placement with the least wasted space for each part",
        "Выбор позиции с наименьшими потерями места для каждой детали"),
    "条纹排列": ("Stripe", "Полосами"),
    "按行排列，每行尽可能紧凑，适合长条形零件": (
        "Pack row by row as compactly as possible; good for long parts",
        "Укладка рядами, каждый ряд как можно плотнее; подходит для длинных деталей"),
    "未知版本": ("Unknown version", "Неизвестная версия"),
    "未知": ("Unknown", "Неизвестно"),
    "未命名": ("Untitled", "Без имени"),
    "未命名文件": ("Untitled file", "Файл без имени"),
    "未指定": ("Unspecified", "Не указано"),
    "英寸": ("Inches", "Дюймы"),
    "英尺": ("Feet", "Футы"),
    "英里": ("Miles", "Мили"),
    "厘米": ("Centimeters", "Сантиметры"),
    "千米": ("Kilometers", "Километры"),
    "微英寸": ("Microinches", "Микродюймы"),
    "密耳": ("Mils", "Милы"),
    "码": ("Yards", "Ярды"),
    "埃": ("Angstroms", "Ангстремы"),
    "纳米": ("Nanometers", "Нанометры"),
    "微米": ("Micrometers", "Микрометры"),
    "分米": ("Decimeters", "Дециметры"),
    "十米": ("Decameters", "Декаметры"),
    "百米": ("Hectometers", "Гектометры"),
    "吉米": ("Gigameters", "Гигаметры"),
    "天文单位": ("Astronomical units", "Астрономические единицы"),
    "光年": ("Light years", "Световые годы"),
    "秒差距": ("Parsecs", "Парсеки"),
    "外轮廓": ("Outer bounds", "Внешние границы"),
    "类型": ("Type", "Тип"),
    "文件": ("File", "Файл"),
    "信息": ("Info", "Информация"),
    "导出": ("Export", "Экспорт"),
    "关闭面板": ("Close panel", "Закрыть панель"),
    "待选择路径": ("Path not selected", "Путь не выбран"),
}

def mt(text, tl, sl='zh-CN'):
    if not text.strip():
        return text
    url = ('https://translate.googleapis.com/translate_a/single?client=gtx&sl=%s&tl=%s&dt=t&q=%s'
           % (urllib.parse.quote(sl), urllib.parse.quote(tl), urllib.parse.quote(text)))
    for attempt in range(4):
        try:
            r = urllib.request.urlopen(url, timeout=15).read().decode('utf-8')
            d = json.loads(r)
            return ''.join(x[0] for x in d[0])
        except Exception:
            time.sleep(1.0 + attempt)
    raise RuntimeError('translate failed: %r' % text[:40])

def template_to_masked(raw, segs):
    result = ''
    exprs = []
    idx = 0
    for t, v in segs:
        if t == 'txt':
            result += v
        else:
            result += '%s%d%s' % (SENT, idx, SENT)
            exprs.append(v)
            idx += 1
    return result, exprs

def sentinel_to_android(masked):
    return re.sub(re.escape(SENT) + r'(\d+)' + re.escape(SENT),
                  lambda m: '%%%d$s' % (int(m.group(1)) + 1), masked)

def esc(s):
    return (s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
             .replace('"', '\\"').replace("'", "\\'").replace('\n', '\\n'))

jobs = []
lit_map = {}
seen = {}
for l in literals:
    raw = l['raw']
    if raw in seen:
        l['key'] = seen[raw]
        continue
    masked, exprs = template_to_masked(raw, l['segments'])
    key = 's%04d' % (len(seen) + 1)
    seen[raw] = key
    l['key'] = key
    lit_map[key] = {'masked': masked, 'exprs': exprs, 'zh': raw}
    jobs.append(('lit', key, raw, masked))
for i, c in enumerate(comments):
    jobs.append(('cmt', 'c%03d' % (i + 1), c, c))

print('unique literals: %d | comment texts: %d | jobs: %d' % (len(lit_map), len(comments), len(jobs)))

# ---- translate with cache ----
cache_path = os.path.join(BASE, 'translations.json')
cache = json.load(io.open(cache_path, encoding='utf-8')) if os.path.exists(cache_path) else {}

done = 0
for kind, key, zh, masked in jobs:
    bucket = cache.setdefault(kind, {}).setdefault(key, {})
    for tl in ('en', 'ru'):
        if bucket.get(tl):
            continue
        if zh in OVERRIDE:
            val = OVERRIDE[zh][0 if tl == 'en' else 1]
        else:
            val = mt(masked, tl)
        bucket[tl] = val
        done += 1
        if done % 40 == 0:
            json.dump(cache, io.open(cache_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
            print('  ... translated %d strings' % done)
json.dump(cache, io.open(cache_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print('translation cache complete (%d new this run)' % done)

# ---- generate string resources ----
def is_resourceable(key):
    zh = lit_map[key]['zh']
    if re.search(r'\\s|\(\?i\)|\(\?:|\\d|\\w|\[A-Z\]', zh):
        return False
    return True

res_keys = [k for k in lit_map if is_resourceable(k)]
print('resource strings: %d (inline keeps: %d)' % (len(res_keys), len(lit_map) - len(res_keys)))

SPEC_RE = re.compile(r'%[.0-9]*[sfdx]')
def escape_literal_percent(val):
    """Escape bare % (not part of %N$s or old-style %.2f/%d/%s spec) to %%.
    Walks chars so no regex backslash-escaping pitfalls."""
    out = []
    i = 0
    n = len(val)
    while i < n:
        c = val[i]
        if c == '%':
            if i + 1 < n and val[i + 1] == '%':
                out.append('%%')
                i += 2
                continue
            j = i + 1
            while j < n and val[j].isdigit():
                j += 1
            if j > i + 1 and j < n and val[j] == '$' and j + 1 < n and val[j + 1] in 'sfdx':
                out.append(val[i:j + 2])
                i = j + 2
                continue
            k = i + 1
            while k < n and (val[k].isdigit() or val[k] == '.'):
                k += 1
            if k > i + 1 and k < n and val[k] in 'sfdx':
                out.append(val[i:k + 1])
                i = k + 1
                continue
            out.append('%%')
            i += 1
        else:
            out.append(c)
            i += 1
    return ''.join(out)

def build_xml(lang):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for k in sorted(res_keys):
        entry = lit_map[k]
        if lang == 'zh':
            val = sentinel_to_android(entry['masked'])
        else:
            val = sentinel_to_android(cache['lit'][k][lang])
        n = len(entry['exprs'])
        if n > 0:
            # Used via getString(R.string.k, args) -> String.format runs: escape bare %.
            val = escape_literal_percent(val)
            lines.append('    <string name="%s" formatted="true">%s</string>' % (k, esc(val)))
        elif SPEC_RE.search(val):
            # Old-style specs (e.g. %.2f) used via Kotlin .format() or getString(id, args):
            # escape bare % and disable aapt2 positional check.
            val = escape_literal_percent(val)
            lines.append('    <string name="%s" formatted="false">%s</string>' % (k, esc(val)))
        else:
            # Plain text (may contain a literal %% like '10%%'); never run through String.format.
            lines.append('    <string name="%s">%s</string>' % (k, esc(val)))
    lines.append('</resources>')
    return '\n'.join(lines) + '\n'
res = os.path.join(BASE, '..', '..', 'app', 'src', 'main', 'res')
for lang, sub in (('en', 'values'), ('ru', 'values-ru'), ('zh', 'values-zh')):
    d = os.path.join(res, sub)
    os.makedirs(d, exist_ok=True)
    io.open(os.path.join(d, 'strings_i18n.xml'), 'w', encoding='utf-8').write(build_xml(lang))
    print('wrote', os.path.join(d, 'strings_i18n.xml'))

# ---- emit map for transformer ----
out = {}
for k, entry in lit_map.items():
    out[k] = {
        'zh': entry['zh'],
        'masked': entry['masked'],
        'exprs': entry['exprs'],
        'en': cache['lit'][k]['en'],
        'ru': cache['lit'][k]['ru'],
        'resourceable': k in res_keys,
    }
json.dump(out, io.open(os.path.join(BASE, 'res_map.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=0)
cmt_out = {('c%03d' % (i + 1)): {'zh': c, 'en': cache['cmt']['c%03d' % (i + 1)]['en']}
           for i, c in enumerate(comments)}
json.dump(cmt_out, io.open(os.path.join(BASE, 'cmt_map.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=0)
print('wrote res_map.json (%d entries) and cmt_map.json (%d entries)' % (len(out), len(cmt_out)))
