"""Collect cached Maven artifacts, POM license metadata and embedded notices after dependencyInventory.
Does not claim to reconstruct native corresponding source or missing notices.
"""
from pathlib import Path
import io,zipfile,hashlib,json,xml.etree.ElementTree as ET
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
out=Path('licenses/managed');out.mkdir(parents=True,exist_ok=True)
records=[];artifacts=[]
def extract_notices(data,folder):
 try:
  with zipfile.ZipFile(io.BytesIO(data)) as z:
   for name in z.namelist():
    leaf=Path(name).name.upper()
    if name.startswith('META-INF/') and leaf.startswith(('LICENSE','NOTICE','COPYING')) and not name.endswith('/'):
     folder.mkdir(parents=True,exist_ok=True);(folder/Path(name).name).write_bytes(z.read(name))
    if name=='classes.jar': extract_notices(z.read(name),folder)
 except zipfile.BadZipFile:pass
for coord in Path('docs/dependencies.tsv').read_text().splitlines()[1:]:
 parts=coord.split(':')
 if len(parts)!=3:continue
 group,module,version=parts
 base=cache/group/module/version
 if not base.exists():continue
 licenses=[]
 for pom in base.rglob('*.pom'):
  root=ET.parse(pom).getroot();ns={'m':'http://maven.apache.org/POM/4.0.0'}
  licenses+=[{'name':x.findtext('m:name',namespaces=ns),'url':x.findtext('m:url',namespaces=ns)} for x in root.findall('m:licenses/m:license',ns)]
 for f in base.rglob('*'):
  if f.suffix in ('.aar','.jar') and not f.name.endswith(('-sources.jar','-javadoc.jar')):
   data=f.read_bytes();artifacts.append({'coordinate':coord,'file':f.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()});extract_notices(data,out/(group+'_'+module+'_'+version))
 records.append({'coordinate':coord,'declared_licenses':licenses})
Path('docs/dependency-license-metadata.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
Path('docs/dependency-artifacts.json').write_text(json.dumps(artifacts,indent=2)+'\n',encoding='utf-8')
print(f'{len(artifacts)} cached artifacts; {len(list(out.rglob("*")))} notice files/directories. Native source audit remains separate.')
