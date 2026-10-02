import re, io, glob, json, sys

KT_FILES = sorted(glob.glob('app/src/main/java/com/example/cadpocketviewer/*.kt'))

def scan_literals(text):
    """Yield (start, end, full_literal_content) for each top-level double-quoted string,
    honoring nested template expressions ${...} with balanced braces and nested strings."""
    res = []
    i = 0; n = len(text)
    in_line_comment = False; in_block_comment = False
    while i < n:
        c = text[i]
        if in_line_comment:
            if c == '\n': in_line_comment = False
            i += 1; continue
        if in_block_comment:
            if text.startswith('*/', i): in_block_comment = False; i += 2; continue
            i += 1; continue
        if text.startswith('//', i): in_line_comment = True; i += 2; continue
        if text.startswith('/*', i): in_block_comment = True; i += 2; continue
        if c == '"':
            # parse string literal
            j = i + 1
            depth = 0
            while j < n:
                ch = text[j]
                if ch == '\\':
                    j += 2; continue
                if depth == 0 and ch == '"':
                    break
                if ch == '$' and j + 1 < n and text[j+1] == '{':
                    depth += 1; j += 2; continue
                if depth > 0 and ch == '{':
                    depth += 1; j += 1; continue
                if depth > 0 and ch == '}':
                    depth -= 1; j += 1; continue
                if depth > 0 and ch == '"':
                    # nested string inside template: skip it
                    k = j + 1
                    while k < n:
                        if text[k] == '\\': k += 2; continue
                        if text[k] == '"': break
                        k += 1
                    j = k + 1; continue
                j += 1
            res.append((i, j + 1, text[i+1:j]))
            i = j + 1; continue
        i += 1
    return res

def split_template(s):
    """Split literal content into segments: ('txt', str) or ('expr', str)."""
    segs = []; buf = ''; i = 0; n = len(s)
    while i < n:
        c = s[i]
        if c == '\\':
            buf += s[i:i+2]; i += 2; continue
        if c == '$' and i + 1 < n and s[i+1] == '{':
            if buf: segs.append(('txt', buf)); buf = ''
            depth = 1; j = i + 2
            while j < n and depth > 0:
                ch = s[j]
                if ch == '\\': j += 2; continue
                if ch == '{': depth += 1
                elif ch == '}': depth -= 1
                elif ch == '"':
                    k = j + 1
                    while k < n:
                        if s[k] == '\\': k += 2; continue
                        if s[k] == '"': break
                        k += 1
                    j = k
                j += 1
            segs.append(('expr', s[i+2:j-1])); i = j; continue
        if c == '$' and i + 1 < n and (s[i+1].isalpha() or s[i+1] == '_'):
            if buf: segs.append(('txt', buf)); buf = ''
            m = re.match(r'\$[A-Za-z_][A-Za-z0-9_]*', s[i:])
            segs.append(('expr', m.group(0)[1:])); i += len(m.group(0)); continue
        buf += c; i += 1
    if buf: segs.append(('txt', buf))
    return segs

def has_cjk(s):
    return re.search(r'[\u4e00-\u9fff]', s) is not None

out = []
for f in KT_FILES:
    text = io.open(f, encoding='utf-8').read()
    for (a, b, content) in scan_literals(text):
        if not has_cjk(content):
            continue
        segs = split_template(content)
        out.append({
            'file': f.split('/')[-1],
            'offset': a,
            'end': b,
            'raw': content,
            'segments': [[t, v] for (t, v) in segs],
            'exprs': [v for (t, v) in segs if t == 'expr'],
            'line': text[:a].count('\n') + 1,
        })

json.dump(out, io.open('tools/i18n/literals.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print('literals with CJK:', len(out))
print('with exprs:', sum(1 for o in out if o['exprs']))
print('pure text:', sum(1 for o in out if not o['exprs']))
# any with % chars
print('with % in text:', sum(1 for o in out if any(t == 'txt' and '%' in v for t, v in [tuple(x) for x in o['segments']])))
