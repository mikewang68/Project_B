"""Build the C/JNI Android terminal using official NDK/SDK command-line tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
VERSION = '0.5.0'


def run(args):
    subprocess.run([str(x) for x in args], check=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', 'D:/anddroidSDK')))
    p.add_argument('--ndk', type=Path, required=True)
    p.add_argument('--output', type=Path, default=ROOT / 'outputs')
    args = p.parse_args()
    sdk, ndk, output = args.sdk.resolve(), args.ndk.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    # Older Windows aapt2 does not accept non-ASCII resource paths.
    build = Path(tempfile.gettempdir()) / 'project-b-wms-android-build'
    build.mkdir(exist_ok=True)
    shutil.copytree(ROOT / 'res', build / 'res', dirs_exist_ok=True)
    shutil.copyfile(ROOT / 'AndroidManifest.xml', build / 'AndroidManifest.xml')
    tools = sdk / 'build-tools/32.0.0'
    platform = sdk / 'platforms/android-34/android.jar'
    compiler = ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
    for file in (platform, compiler, tools / 'aapt2.exe'):
        if not file.is_file():
            raise SystemExit(f'Missing required build tool: {file}')
    native = build / 'native'
    abis = {'arm64-v8a': 'aarch64-linux-android26',
            'armeabi-v7a': 'armv7a-linux-androideabi26',
            'x86': 'i686-linux-android26', 'x86_64': 'x86_64-linux-android26'}
    for abi, target in abis.items():
        dest = native / abi
        dest.mkdir(parents=True, exist_ok=True)
        own_sources = [ROOT / 'native/protocol_core.c', ROOT / 'native/ee600.c', ROOT / 'native/vega.c', ROOT / 'native/scanner_reference.c', ROOT / 'native/android_bridge.c', ROOT / 'native/qr_decode.c']
        vendor_sources = sorted((ROOT / 'native/third_party/quirc/lib').glob('*.c'))
        objects = []
        for source in own_sources + vendor_sources:
            obj = dest / (source.stem + '.o')
            warnings = ['-Wall', '-Wextra', '-Werror']
            if source in vendor_sources:
                # Keep upstream v1.2 byte-for-byte; these two warning classes
                # originate in its identify.c, not in our application code.
                warnings += ['-Wno-unused-parameter', '-Wno-sign-compare']
            run([compiler, f'--target={target}', '-std=c11', '-D_DEFAULT_SOURCE', '-O2', '-fPIC',
                 *warnings, '-c', source, '-o', obj])
            objects.append(obj)
        run([compiler, f'--target={target}', '-shared', '-Wl,-z,max-page-size=16384',
             '-Wl,-z,relro,-z,now', *objects, '-o', dest / 'libwms_protocol.so', '-lm', '-pthread'])
    resources = build / 'resources.zip'
    run([tools / 'aapt2.exe', 'compile', '--dir', build / 'res', '-o', resources])
    generated = build / 'generated'
    generated.mkdir(exist_ok=True)
    shutil.copytree(ROOT / 'assets', build / 'assets', dirs_exist_ok=True)
    base = build / 'resources.apk'
    run([tools / 'aapt2.exe', 'link', '-I', platform, '--manifest', build / 'AndroidManifest.xml',
         '--java', generated, '--min-sdk-version', '26', '--target-sdk-version', '34',
         '-A', build / 'assets', '-o', base, resources])
    classes = build / 'classes'
    classes.mkdir(exist_ok=True)
    sources = list((ROOT / 'java').rglob('*.java')) + list(generated.rglob('*.java'))
    run(['javac', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath',
         str(platform) + os.pathsep + str(tools / 'core-lambda-stubs.jar'), '-d', classes, *sources])
    dex = build / 'dex'
    dex.mkdir(exist_ok=True)
    run(['java', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', platform,
         '--min-api', '26', '--output', dex, *classes.rglob('*.class')])
    unsigned = build / 'unsigned.apk'
    with zipfile.ZipFile(base) as original, zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as package:
        for name in original.namelist():
            package.writestr(name, original.read(name), compress_type=zipfile.ZIP_STORED if name == 'resources.arsc' else zipfile.ZIP_DEFLATED)
        for file in dex.glob('*.dex'):
            package.write(file, file.name)
        for file in native.rglob('*.so'):
            package.write(file, 'lib/' + file.relative_to(native).as_posix())
    aligned = build / 'aligned.apk'
    run([tools / 'zipalign.exe', '-f', '4', unsigned, aligned])
    private = ROOT / '.private'
    private.mkdir(exist_ok=True)
    key = private / 'personal-debug.keystore'
    if not key.exists():
        run(['keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
             '-alias', 'wms-terminal', '-dname', 'CN=Zhou Xujing Protocol Lab', '-validity', '3650',
             '-keyalg', 'RSA', '-keysize', '3072'])
    apk = output / f'WMS-Android-C-Terminal-{VERSION}.apk'
    signer = tools / 'lib/apksigner.jar'
    run(['java', '-jar', signer, 'sign', '--ks', key, '--ks-pass', 'pass:android',
         '--key-pass', 'pass:android', '--out', apk, aligned])
    run(['java', '-jar', signer, 'verify', '--verbose', '--print-certs', apk])
    metadata = {'apk': apk.name, 'version': VERSION, 'min_android': '8.0 (API26)',
                'target_api': 34, 'abis': list(abis), 'native_page_alignment': 16384,
                'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'bytes': apk.stat().st_size}
    (output / f'apk-build-{VERSION}.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(metadata, indent=2))


if __name__ == '__main__':
    main()
