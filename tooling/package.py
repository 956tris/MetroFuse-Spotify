"""Build a reproducible integrity-protected MFP and gather native outputs."""
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
manifest['integrity'] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
files['manifest.json'] = json.dumps(manifest, ensure_ascii=False, separators=(',', ':')).encode()
with zipfile.ZipFile(DIST / 'spotify.mfp', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, data in sorted(files.items()):
        entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, data)
for extension in ('jar', 'apk'):
    shutil.copy2(ROOT / f'native/build/outputs/plugins/spotify.{extension}', DIST)
(DIST / 'SHA256SUMS').write_text(''.join(
    f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'
    for path in sorted(DIST.glob('spotify.*'))), encoding='ascii')
print(DIST)
