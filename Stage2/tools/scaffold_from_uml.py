"""One-time scaffold generator; refuses to overwrite source files.

Reads the verified local UML; not needed for Maven builds. Do not rerun after edits.
"""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent.parent  # EECS3311; course evidence stays outside ProjectGit.
SOURCE = WORKSPACE / 'Project/Stage1/PhotoPilot-ClassDiagram.uxf'
elements = ET.parse(SOURCE).getroot().findall('element')
classes = {}
boxes = []
for index, e in enumerate(elements):
    if e.findtext('id') != 'UMLClass':
        continue
    text = e.findtext('panel_attributes')
    lines = [s for s in text.splitlines() if not re.match(r'(bg|fg|fontsize)=', s)]
    kind = 'interface' if lines[0] == '<<interface>>' else 'enum' if lines[0] == '<<enumeration>>' else 'class'
    title = lines[1] if lines[0].startswith('<<') else lines[0]
    name = title.strip('/').split('<')[0]
    c = e.find('coordinates')
    boxes.append((name, *[int(c.findtext(k)) for k in ['x','y','w','h']]))
    if 'reference to definition above' in text:
        continue
    parts = [p.strip() for p in re.split(r'^--$', '\n'.join(lines), flags=re.M)]
    fields = []
    for s in parts[1].splitlines():
        m = re.fullmatch(r'- (\w+): (.+)', s)
        if m: fields.append({'name': m[1], 'type': m[2]})
    methods = []
    if kind != 'enum':
        for s in parts[2].splitlines():
            s = s.strip('/')
            m = re.fullmatch(r'([+~\-]) (\w+)\((.*)\)(?:: (.+))?', s)
            if not m: continue
            params = []
            for param in re.split(r', (?![^<]*>)', m[3]) if m[3] else []:
                pn, pt = param.split(': ', 1)
                params.append({'name': pn, 'type': pt})
            methods.append({'visibility': m[1], 'name': m[2], 'parameters': params, 'return': m[4] or 'void'})
    classes[name] = dict(name=name, title=title.strip('/'), kind=kind,
        abstract=title.startswith('/') and kind == 'class', fields=fields, methods=methods,
        constants=parts[2].splitlines() if kind == 'enum' else [], element=index,
        notes=' '.join(s for s in lines if s.startswith('{')), relations=[])

def nearest(x,y):
    return min(boxes, key=lambda b: max(b[1]-x,0,x-b[1]-b[3])**2 + max(b[2]-y,0,y-b[2]-b[4])**2)[0]

relations = []
for e in elements:
    if e.findtext('id') != 'Relation': continue
    x,y = [int(e.findtext('coordinates/'+k)) for k in ['x','y']]
    a = list(map(float,e.findtext('additional_attributes').split(';')))
    src,dst = nearest(x+a[0],y+a[1]),nearest(x+a[-2],y+a[-1])
    labels = [s for s in e.findtext('panel_attributes').splitlines() if not re.match(r'(bg|fg|fontsize)=',s)]
    relations.append(dict(source=src,target=dst,notation=labels))
    if labels[0] == 'lt=.>>': classes[src]['implements'] = dst
    if labels[0] == 'lt=->>': classes[src]['extends'] = dst

groups = {
 'application': 'PhotoPilotFacade',
 'ui.gui': 'PhotoPilotGUI', 'ui.cli': 'PhotoPilotCLI',
 'common': 'Result',
 'agent': 'PhotoPilotAgent ToolRegistry AgentTool HistoryRetriever AgentContext ModelReply ToolCall CalculationTool GearMatchingTool EnvironmentTool SpecificationTool',
 'service': 'SessionService PlanningService InventoryService DiscoveryService ReviewService RequirementService GearRecognitionService GearMatchingService EnvironmentService LocationService TechnicalCalculator StatisticsCalculator',
 'state': 'SessionState DraftState PlannedState CompletedState ReviewedState SessionEvent',
 'strategy': 'LoadoutRankingStrategy PortabilityRanking CoverageRanking',
 'command': 'PlanEditor PlanCommand ReplaceLoadoutCommand ReviseScheduleCommand ReviseStrategyCommand ShootPlan',
 'integration': 'LLMClient GeminiAdapter EquipmentSpecificationProvider EquipmentCatalogAdapter EnvironmentProvider EnvironmentApiAdapter DeviceLocationProvider MetadataExtractor ExifLibraryAdapter',
 'repository': 'SessionRepository JsonSessionRepository InventoryRepository JsonInventoryRepository',
 'validation': 'CompatibilityValidator FeasibilityValidator PlanValidator',
 'ui.privacy': 'ImagePrivacyBoundary SanitizedImage',
}
for c in classes.values(): c['package'] = next((p for p,names in groups.items() if c['name'] in names.split()), 'model')

def write(path,text):
    p=ROOT/path; p.parent.mkdir(parents=True, exist_ok=True)
    if p.exists(): raise RuntimeError('Refusing to overwrite '+str(p))
    p.write_text(text,encoding='utf-8')

def assign(field):
    n,t=field['name'],field['type']
    if t.startswith(('List<','Set<','Map<')): return t.split('<')[0]+'.copyOf('+n+')'
    if t == 'byte[]': return n+'.clone()'
    if t in ['int','double','boolean']: return n
    return 'Objects.requireNonNull('+n+', "'+n+'")'

for name,c in classes.items():
    text='package com.photopilot.'+c['package']+';\n\n'
    imports=['java.util.*','java.time.*','java.nio.file.Path','java.net.URI','java.net.http.HttpClient']
    used=' '.join(f['type'] for f in c['fields'])+' '+str(c['methods'])+' '+c.get('implements','')+' '+c.get('extends','')
    for other,oc in classes.items():
        if other!=name and oc['package']!=c['package'] and re.search(r'\b'+other+r'\b',used): imports.append('com.photopilot.'+oc['package']+'.'+other)
    text+=''.join('import '+i+';\n' for i in sorted(set(imports)))
    text+='\n/**\n * Stage 1 UML element '+str(c['element'])+' (zero-based): '+c['notes'].replace('<','&lt;').replace('>','&gt;')+'\n * SCAFFOLDED: business behavior is NOT IMPLEMENTED.\n */\n'
    text+='public '+('abstract ' if c['abstract'] else '')+c['kind']+' '+c['title']
    if 'extends' in c:text+=' extends '+c['extends']
    if 'implements' in c:text+=' implements '+c['implements']
    text+=' {\n'
    if c['kind']=='enum': text+='    '+', '.join(c['constants'])+'\n'
    else:
        for f in c['fields']:
            text+='    private '+('final ' if name in ['Loadout','GearSnapshot'] else '')+f['type']+' '+f['name']+';\n'
        if c['kind']=='class':
            inherited=classes[c['extends']]['fields'] if 'extends' in c else []
            fs=inherited+c['fields']
            explicit=next((m for m in c['methods'] if m['name']==name),None)
            args=explicit['parameters'] if explicit else fs
            access='' if explicit and explicit['visibility']=='~' else 'protected ' if c['abstract'] else 'public '
            text+='\n    '+access+name+'('+', '.join(f['type']+' '+f['name'] for f in args)+') {\n'
            if inherited: text+='        super('+', '.join(f['name'] for f in inherited)+');\n'
            for f in c['fields']:
                if f['name'] in [a['name'] for a in args]: text+='        this.'+f['name']+' = '+assign(f)+';\n'
            text+='    }\n'
        for m in c['methods']:
            if m['name']==name:continue
            access={'+':'public ','-':'private ','~':''}[m['visibility']]
            generic='<T> ' if 'T' in re.findall(r'\b\w+\b',m['return']) and name!='Result' else ''
            signature=access+generic+m['return']+' '+m['name']+'('+', '.join(p['type']+' '+p['name'] for p in m['parameters'])+')'
            if c['kind']=='interface':text+='\n    '+signature+';\n';continue
            text+='\n    '+signature+' {\n'
            message='NOT IMPLEMENTED: '+name+'.'+m['name']
            if m['return'].startswith('Result<'):text+='        return Result.failure("'+message+'");\n'
            else:text+='        throw new UnsupportedOperationException("'+message+'");\n'
            text+='    }\n'
        if name=='Result':
            text=text.replace('throw new UnsupportedOperationException("NOT IMPLEMENTED: Result.isSuccess");','return errors.isEmpty() && value.isPresent();')
            text+='\n    public static <T> Result<T> failure(String message) {\n        return new Result<>(Optional.empty(), List.of(Objects.requireNonNull(message)), List.of());\n    }\n\n    public Optional<T> value() { return value; }\n    public List<String> errors() { return errors; }\n    public List<String> warnings() { return warnings; }\n'
    text+='}\n'
    path='src/main/java/com/photopilot/'+c['package'].replace('.','/')+'/'+name+'.java'
    c['file']=path
    write(path,text)

write('docs/uml-baseline.json',json.dumps(dict(source=str(SOURCE.relative_to(WORKSPACE)),sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),classes=classes,relations=relations),indent=2))
sources=list((WORKSPACE/'Project').rglob('*.pdf'))+list((WORKSPACE/'Project/Stage1').glob('*.docx'))+list((WORKSPACE/'Project/Stage1').glob('*.uxf'))+list((WORKSPACE/'Project/Stage1').glob('*.png'))
write('docs/source-hashes.json',json.dumps({str(p.relative_to(WORKSPACE)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},indent=2))
print('Generated',len(classes),'UML classifiers and recorded',len(relations),'relationships.')
