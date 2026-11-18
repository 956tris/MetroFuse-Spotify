"""Build a reproducible integrity-protected MFP and gather native outputs."""
import argparse
import re
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / 'dist'
DIST.mkdir(exist_ok=True)
files = {p.relative_to(ROOT / 'javascript').as_posix(): p.read_bytes()
         for p in sorted((ROOT / 'javascript').rglob('*')) if p.is_file() and p.name != 'manifest.json'}
files['LICENSE'] = (ROOT / 'LICENSE').read_bytes()
manifest = json.loads((ROOT / 'javascript/manifest.json').read_text(encoding='utf-8-sig'))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--version', default=manifest['version'])
version = parser.parse_args().version
if not re.fullmatch(r'\d+\.\d+\.\d+', version):
    raise SystemExit('Use a stable semantic plugin version')
manifest['version'] = version
manifest['integrity'] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
files['manifest.json'] = json.dumps(manifest, ensure_ascii=False, separators=(',', ':')).encode()
with zipfile.ZipFile(DIST / 'spotify.mfp', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, data in sorted(files.items()):
        entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, data)
for extension in ('jar', 'apk'):
    source = ROOT / f'native/build/outputs/plugins/spotify.{extension}'
    with zipfile.ZipFile(source) as archive:
        location = 'manifest.json' if extension == 'jar' else 'assets/metrofuse/manifest.json'
        packaged = json.loads(archive.read(location))
        if packaged['id'] != manifest['id'] or packaged['version'] != version:
            raise SystemExit(f'Rebuild the outdated {source.name}')
        if archive.testzip() is not None:
            raise SystemExit(f'Invalid archive: {source.name}')
    shutil.copy2(source, DIST)
(DIST / 'SHA256SUMS').write_text(''.join(
    f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'
    for path in sorted(DIST.glob('spotify.*'))), encoding='ascii')
print(DIST)
