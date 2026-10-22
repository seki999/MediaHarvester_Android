"""Report native ELF load alignment, including shared objects nested in runtime ZIP assets.
This inspects binaries without executing them. It is not a 16KB device compatibility test.
"""
from pathlib import Path
import hashlib, io, json, struct, sys, zipfile
apk=Path(sys.argv[1] if len(sys.argv)>1 else 'app/build/outputs/apk/debug/app-debug.apk')
report={'apk':apk.name,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'elf':[]}
def inspect(name,data):
    if data[:4]==b'\x7fELF':
        wide=data[4]==2;end='<' if data[5]==1 else '>'
        off=struct.unpack_from(end+('Q' if wide else 'I'),data,32 if wide else 28)[0]
        size,count=struct.unpack_from(end+'HH',data,54 if wide else 42)
        aligns=[]
        for i in range(count):
            pos=off+i*size
            if struct.unpack_from(end+'I',data,pos)[0]==1:
                aligns.append(struct.unpack_from(end+('Q' if wide else 'I'),data,pos+(48 if wide else 28))[0])
        report['elf'].append({'path':name,'load_alignment':aligns,'compatible_alignment_16kb':bool(aligns) and all(a>=16384 for a in aligns)})
    elif data[:4]==b'PK\x03\x04':
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for entry in z.namelist():
                if '.so' in entry:inspect(name+'!/'+entry,z.read(entry))
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if name.startswith('lib/'):inspect(name,z.read(name))
print(json.dumps(report,indent=2))
