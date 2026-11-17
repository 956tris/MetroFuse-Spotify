"""Check the packaged Windows login helper without opening a browser or signing in."""
import json
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def check(path):
    if sys.platform != 'win32':
        print('Windows login helper check requires Windows.')
        return
    request = {
        'url': 'https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F',
        'cookieUrl': 'https://open.spotify.com/',
        'cookieField': 'cookie',
        'requiredCookies': ['sp_dc'],
        'allowedHosts': ['accounts.spotify.com', 'open.spotify.com'],
        'completionPath': '/',
    }
    with tempfile.TemporaryDirectory(prefix='spotify-browser-check-') as directory:
        directory = Path(directory)
        executable = directory / 'spotify-browser.exe'
        with zipfile.ZipFile(path) as package:
            executable.write_bytes(package.read('assets/windows/spotify-browser.exe'))
        config = directory / 'request.json'
        config.write_text(json.dumps(request), encoding='utf-8')
        environment = dict(os.environ, DOTNET_BUNDLE_EXTRACT_BASE_DIR=str(directory / 'runtime'))
        result = subprocess.run(
            [str(executable), str(config), 'Spotify login check', str(directory / 'profile'), '--validate-request'],
            capture_output=True, text=True, check=True, timeout=30, env=environment,
        )
        assert json.loads(result.stdout) == request, 'Packaged helper lost the completion-page restriction'
    print('Packaged Windows login helper preserves the completion origin and path.')


if __name__ == '__main__':
    check(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent.parent / 'dist/spotify.jar')
