#!/usr/bin/env python3
"""Скачивает примеры из MANIFEST.json в текущую папку и проверяет sha256.
Запуск:  python3 fetch_samples.py [папка_назначения]
Файл ref_ifc/AC20-FZK-Haus.ifc лежит внутри архива крейта ifc_rs; скрипт достаёт его оттуда."""
import hashlib, io, json, os, sys, tarfile, urllib.request
dest = sys.argv[1] if len(sys.argv) > 1 else '.'
here = os.path.dirname(os.path.abspath(__file__))
for e in json.load(open(os.path.join(here, 'MANIFEST.json'), encoding='utf-8')):
    target = os.path.join(dest, e['path'])
    if os.path.exists(target) and hashlib.sha256(open(target, 'rb').read()).hexdigest() == e['sha256']:
        print('ok  ', e['path']); continue
    os.makedirs(os.path.dirname(target), exist_ok=True)
    url = e['url']
    if '#' in url:
        crate, member = url.split('#')
        tf = tarfile.open(fileobj=io.BytesIO(urllib.request.urlopen(crate).read()), mode='r:gz')
        data = next(tf.extractfile(m).read() for m in tf.getmembers() if m.name.endswith(member))
    else:
        data = urllib.request.urlopen(url).read()
    if hashlib.sha256(data).hexdigest() != e['sha256']:
        sys.exit('SHA256 MISMATCH ' + e['path'])
    open(target, 'wb').write(data)
    print('got ', e['path'], len(data))
