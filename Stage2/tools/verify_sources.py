"""Read-only structural audit against the frozen UML and original-document hashes.

Run from any directory with Python 3. No third-party dependencies required.
This checks declarations, not feature behavior or ownership enforcement.
"""
from pathlib import Path
import hashlib
import json
import re

root = Path(__file__).resolve().parents[1]
baseline = json.loads((root / 'docs/uml-baseline.json').read_text(encoding='utf-8'))
failures = []
field_count = method_count = 0
compact = lambda s: re.sub(r'\s+', '', s)
for name, c in baseline['classes'].items():
    path = root / c['file']
    if not path.exists():
        failures.append('Missing '+name)
        continue
    source = path.read_text(encoding='utf-8')
    text = compact(source)
    declaration = 'public '+('abstract ' if c['abstract'] else '')+c['kind']+' '+c['title']
    for relation in ['extends', 'implements']:
        if relation in c: declaration += ' '+relation+' '+c[relation]
    if name == 'PhotoPilotGUI': declaration += ' extends Application'
    if compact(declaration)+'{' not in text: failures.append(name+' declaration differs')
    for field in c['fields']:
        field_count += 1
        if not re.search(r'private\s+(?:final\s+)?'+re.escape(field['type'])+r'\s+'+field['name']+r'\s*[;=]',source):
            failures.append(name+'.'+field['name']+' field differs')
    for method in c['methods']:
        method_count += 1
        access={'+':'public ','~':'','-':'private '}[method['visibility']]
        constructor = method['name']==name
        generic='<T> ' if re.search(r'\bT\b',method['return']) and name!='Result' else ''
        signature=access+('' if constructor else generic+method['return']+' ')+method['name']+'('+', '.join(p['type']+' '+p['name'] for p in method['parameters'])+')'
        if compact(signature) not in text: failures.append(name+'.'+method['name']+' signature differs')
    for constant in c['constants']:
        if not re.search(r'\b'+constant+r'\b',source): failures.append(name+' missing '+constant)

hashes=json.loads((root/'docs/source-hashes.json').read_text(encoding='utf-8'))
for name, expected in hashes.items():
    path=root.parent/name
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:
        failures.append('Source missing or changed: '+name)

for path in (root/'src/main/java').rglob('*.java'):
    source=path.read_text(encoding='utf-8')
    if re.search(r'\breturn\s+null\s*;',source): failures.append('Null result in '+str(path))
    if re.search(r'\.send(?:Async)?\s*\(',source): failures.append('External HTTP execution in '+str(path))
    if re.search(r'AIza[0-9A-Za-z_-]{30,}|-----BEGIN .*PRIVATE KEY-----',source): failures.append('Potential secret in '+str(path))

if failures:
    print('\n'.join(failures))
    raise SystemExit(1)
print(f'STRUCTURAL AUDIT PASS: {len(baseline["classes"])} UML types, {field_count} fields, {method_count} operations; {len(baseline["relations"])} relationships recorded.')
print(f'{len(hashes)} original source files unchanged by SHA-256. No null returns, HTTP execution, or matching secret patterns found.')
print('Association fields and Java inheritance checked; ownership, multiplicities and business behavior remain NOT IMPLEMENTED.')
