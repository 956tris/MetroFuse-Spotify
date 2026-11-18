"""Validate the native plugin boundary without requiring a running host."""
import json
import hashlib
import struct
import sys
import zipfile
from pathlib import Path


def dex_classes(dex):
    string_count, string_offset = struct.unpack_from('<II', dex, 56)
    type_count, type_offset = struct.unpack_from('<II', dex, 64)
    class_count, class_offset = struct.unpack_from('<II', dex, 96)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from('<I', dex, string_offset + index * 4)[0]
        while dex[offset] & 128:
            offset += 1
        offset += 1
        strings.append(dex[offset:dex.index(b'\0', offset)].decode('utf-8', errors='replace'))
    types = [strings[struct.unpack_from('<I', dex, type_offset + index * 4)[0]] for index in range(type_count)]
    return types, [types[struct.unpack_from('<I', dex, class_offset + index * 32)[0]] for index in range(class_count)]


def check(apk):
    with zipfile.ZipFile(apk) as package:
        names = package.namelist()
        assert len(names) == len(set(names)), 'Duplicate APK entries'
        manifest = json.loads(package.read('assets/metrofuse/manifest.json'))
        assert manifest['runtime'] == 'kotlin'
        assert manifest['entryClass'] == 'dev.metrofuse.spotify.SpotifyPlugin'
        assert 'AndroidManifest.xml' in names and 'classes.dex' in names
        assert not any(name.endswith(('.so', '.class', '.jar')) for name in names), 'Host or desktop dependencies bundled'
        dex = [dex_classes(package.read(entry)) for entry in names if entry.endswith('.dex')]
        references = [name for types, _ in dex for name in types]
        desktop_references = [name for name in references if name.endswith('_skikoKt;') or name.startswith('Lorg/jetbrains/skiko/')]
        assert not desktop_references, f'Desktop-only API references in Android plugin: {desktop_references}'
        classes = [name for _, definitions in dex for name in definitions]
        assert classes
        unexpected = [name for name in classes if not name.startswith('Ldev/metrofuse/spotify/') and name != 'Lcom/android/tools/r8/annotations/LambdaMethod;']
        assert not unexpected, f'Non-plugin class definitions bundled: {unexpected}'
        assert 'Ldev/metrofuse/spotify/SpotifyPlugin;' in classes
        assert any('$$serializer;' in name for name in classes), 'Legacy JSON serializers absent'
        assert not any('browser.exe' in name for name in names), 'Desktop helper bundled in Android'
        print(f'{apk.name}: {len(classes)} plugin classes; no bundled host/runtime classes.')


def check_jar(path):
    with zipfile.ZipFile(path) as package:
        names = package.namelist()
        assert len(names) == len(set(names)), 'Duplicate JAR entries'
        manifest = json.loads(package.read('manifest.json'))
        assert manifest['entryClass'] == 'dev.metrofuse.spotify.SpotifyPlugin'
        classes = [name for name in names if name.endswith('.class')]
        assert classes and all(name.startswith('dev/metrofuse/spotify/') for name in classes), 'Bundled host/runtime classes'
        assert any('$$serializer.class' in name for name in classes), 'Legacy JSON serializers absent'
        assert not any(name.endswith('.exe') for name in names), 'Browser must be supplied by the host SDK'
        assert 'LICENSE' in names and 'SOURCE' in names
        print(f'{path.name}: {len(classes)} plugin classes; uses the shared SDK browser.')


def check_mfp(path):
    with zipfile.ZipFile(path) as package:
        names = package.namelist()
        assert len(names) == len(set(names)), 'Duplicate MFP entries'
        manifest = json.loads(package.read('manifest.json'))
        assert manifest['runtime'] == 'javascript' and manifest['id'] == 'metrofuse.spotify'
        assert manifest['script'] in names
        assert set(manifest['integrity']) == set(names) - {'manifest.json'}
        for name, digest in manifest['integrity'].items():
            assert hashlib.sha256(package.read(name)).hexdigest() == digest, f'Integrity mismatch: {name}'
        assert not any(name.endswith(('.class', '.jar', '.dex', '.so')) for name in names)
        assert 'LICENSE' in names and 'SOURCE' in names
        print(f'{path.name}: JavaScript integrity verified for {len(manifest["integrity"])} entries.')


if __name__ == '__main__':
    dist = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent.parent / 'dist'
    check(dist / 'spotify.apk')
    check_jar(dist / 'spotify.jar')
    check_mfp(dist / 'spotify.mfp')
