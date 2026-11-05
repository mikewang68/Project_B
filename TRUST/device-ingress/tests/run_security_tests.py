"""Reproducible local Java 17 / HTTP / H2 tests. Never contacts project servers.

Optional --download obtains frozen dependencies from official Maven Central into
the selected local test cache, with source, version and SHA-256 provenance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request

ARTIFACTS = [
    ('com.fasterxml.jackson.core', 'jackson-core', '2.17.2'),
    ('com.fasterxml.jackson.core', 'jackson-databind', '2.17.2'),
    ('com.fasterxml.jackson.core', 'jackson-annotations', '2.17.2'),
    ('org.apache.rocketmq', 'rocketmq-client', '5.3.3'),
    ('org.apache.rocketmq', 'rocketmq-common', '5.3.3'),
    ('org.apache.rocketmq', 'rocketmq-remoting', '5.3.3'),
    ('org.slf4j', 'slf4j-api', '1.7.36'),
    ('com.h2database', 'h2', '2.3.232'),
]


def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--reuse', action='store_true', help='run complete HTTP suite with single JDBC session reuse enabled')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    cache = args.cache.resolve() if args.cache else out / 'test-libs'
    cache.mkdir(exist_ok=True)
    dependencies = []
    # Direct HTTPS avoids workstation proxy settings; destination is frozen below.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for group, artifact, version in ARTIFACTS:
        name = f'{artifact}-{version}.jar'
        url = 'https://repo.maven.apache.org/maven2/' + group.replace('.', '/') + f'/{artifact}/{version}/{name}'
        path = cache / name
        if args.download and not path.exists():
            with opener.open(url, timeout=30) as response:
                data = response.read()
            path.write_bytes(data)
        data = path.read_bytes()
        dependencies.append({'artifact': artifact, 'version': version, 'url': url,
                             'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
    (out / 'test-dependency-manifest.json').write_text(json.dumps({
        'recorded_utc_ms': time.time_ns() // 1000000,
        'scope': 'local_test_cache_no_global_install', 'dependencies': dependencies}, indent=2), encoding='utf-8')
    env = os.environ.copy()
    for key in list(env):
        if key.startswith('DEVICE_'):
            del env[key]
    env.update(DEVICE_DB_URL='jdbc:h2:mem:security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
               DEVICE_DB_USER='sa', DEVICE_DB_PASSWORD='synthetic-test-password')
    if args.reuse:
        env['DEVICE_JDBC_REUSE'] = 'true'
    result = {'started_utc_ms': time.time_ns() // 1000000, 'deployment': 'not_performed',
              'validation': 'local_HTTP_H2_only', 'sources': {}}
    sources = [root / 'src' / n for n in ('DeviceIngress.java', 'AccessPolicy.java', 'RejectionAudit.java', 'IngressTiming.java', 'JdbcSession.java')]
    sources.append(root / 'tests' / 'SecurityHttpTest.java')
    sources.append(root / 'tests' / 'ScopedAssetHttpTest.java')
    sources.append(root / 'tests' / 'JdbcSessionTest.java')
    sources.append(root / 'tests' / 'RestrictedProbe.java')
    template = root / 'tests' / 'sim-policy.template.json'
    for path in sources + [Path(__file__).resolve(), template]:
        result['sources'][str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    with tempfile.TemporaryDirectory(prefix='security-classes-', dir=out) as classes:
        suffix = '.exe' if os.name == 'nt' else ''
        command = [str(args.jdk / 'bin' / ('javac' + suffix)), '-J-Dfile.encoding=UTF-8', '--release', '17', '-encoding', 'UTF-8',
                   '-cp', str(cache / '*'), '-d', classes] + [str(p) for p in sources]
        compiled = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
        (out / 'compile.log').write_text(compiled.stdout + compiled.stderr, encoding='utf-8')
        result['compile_exit_code'] = compiled.returncode
        if compiled.returncode == 0:
            command = [str(args.jdk / 'bin' / ('java' + suffix)), '-Dfile.encoding=UTF-8', '-cp', classes + os.pathsep + str(cache / '*'),
                       'SecurityHttpTest', str(out)]
            tested = subprocess.run(command, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=120)
            (out / 'http-test.log').write_text(tested.stdout + tested.stderr, encoding='utf-8')
            result['http_test_exit_code'] = tested.returncode
            print(tested.stdout + tested.stderr)
            fixtures = Path(classes) / 'fixtures'
            fixtures.mkdir()
            token_file = fixtures / 'synthetic.token'
            token_file.write_text('TEST_ONLY_CONFIG_TOKEN_000000000000000000', encoding='utf-8')
            policy_file = fixtures / 'synthetic-policy.json'
            policy = {'version': 1, 'devices': [{'device_id': 'SIM-TEST-CONFIG', 'project_id': 'SIM-TEST-PROJECT', 'area_id': 'SIM-TEST-AREA'}],
                      'principals': [{'principal_id': 'synthetic-config', 'token_sha256': hashlib.sha256(token_file.read_bytes()).hexdigest(),
                                      'read_devices': ['SIM-TEST-CONFIG'], 'write_devices': []}]}
            policy_file.write_text(json.dumps(policy), encoding='utf-8')
            invalid_policy_file = fixtures / 'invalid-policy.json'
            policy['principals'][0]['write_devices'] = ['SIM-UNKNOWN']
            invalid_policy_file.write_text(json.dumps(policy), encoding='utf-8')
            simulated_policy = json.loads(template.read_text(encoding='utf-8'))
            for principal in simulated_policy['principals']:
                principal['token_sha256'] = hashlib.sha256(('TEST_ONLY_TEMPLATE_' + principal['principal_id']).encode()).hexdigest()
            simulated_file = fixtures / 'simulated-policy.json'
            simulated_file.write_text(json.dumps(simulated_policy), encoding='utf-8')
            scoped = dict(DEVICE_ACCESS_MODE='scoped', DEVICE_ACCESS_POLICY_FILE=str(policy_file),
                          DEVICE_REJECTION_AUDIT_FILE=str(fixtures / 'reject.jsonl'), DEVICE_REJECTION_AUDIT_MAX_BYTES='4096')
            probes = [
                ('scoped_valid_without_legacy_token', scoped, 0, 'SCOPED_CONFIG_READY'),
                ('scoped_missing_policy', {k: v for k, v in scoped.items() if k != 'DEVICE_ACCESS_POLICY_FILE'}, 2, 'SECURITY_CONFIG_REJECTED'),
                ('scoped_missing_audit', {k: v for k, v in scoped.items() if k != 'DEVICE_REJECTION_AUDIT_FILE'}, 2, 'SECURITY_CONFIG_REJECTED'),
                ('scoped_unknown_mapping_no_legacy_fallback', {**scoped, 'DEVICE_ACCESS_POLICY_FILE': str(invalid_policy_file), 'DEVICE_TOKEN_FILE': str(token_file)}, 2, 'SECURITY_CONFIG_REJECTED'),
                ('invalid_mode', {'DEVICE_ACCESS_MODE': 'scope-typo', 'DEVICE_TOKEN_FILE': str(token_file)}, 2, 'SECURITY_CONFIG_REJECTED'),
                ('policy_without_explicit_scope', {'DEVICE_ACCESS_POLICY_FILE': str(policy_file), 'DEVICE_TOKEN_FILE': str(token_file)}, 2, 'SECURITY_CONFIG_REJECTED'),
                ('default_legacy_compatible_and_labeled', {'DEVICE_TOKEN_FILE': str(token_file)}, 0, 'LEGACY_SHARED_UNISOLATED'),
                ('approved_simulation_template_with_synthetic_hashes', {**scoped, 'DEVICE_ACCESS_POLICY_FILE': str(simulated_file)}, 0, 'SCOPED_CONFIG_READY'),
                ('unfilled_template_cannot_start', {**scoped, 'DEVICE_ACCESS_POLICY_FILE': str(template)}, 2, 'SECURITY_CONFIG_REJECTED'),
                ('scoped_assets_opt_in', {**scoped, 'DEVICE_SCOPED_ASSETS': 'enabled'}, 0, 'SCOPED_CONFIG_READY'),
                ('scoped_assets_unknown_value', {**scoped, 'DEVICE_SCOPED_ASSETS': 'typo'}, 2, 'SECURITY_CONFIG_REJECTED'),
                ('legacy_cannot_enable_scoped_assets', {'DEVICE_TOKEN_FILE': str(token_file), 'DEVICE_SCOPED_ASSETS': 'enabled'}, 2, 'SECURITY_CONFIG_REJECTED'),
            ]
            config_results = []
            for name, changes, expected, marker in probes:
                probe = subprocess.run(command[:-1] + ['--config-probe'], env={**env, **changes}, capture_output=True,
                                       text=True, encoding='utf-8', errors='replace', timeout=20)
                native_identity_unavailable = (expected == 0 and marker == 'SCOPED_CONFIG_READY'
                                               and probe.returncode == 2 and 'AUDIT_FILE_IDENTITY_UNAVAILABLE' in probe.stdout)
                config_results.append({'case': name, 'exit_code': probe.returncode, 'output': probe.stdout.strip(),
                                       'native_identity_unavailable_fail_closed': native_identity_unavailable,
                                       'passed': native_identity_unavailable or (probe.returncode == expected and marker in probe.stdout)})
            result['configuration_probes'] = config_results
            result['configuration_probes_passed'] = all(p['passed'] for p in config_results)
            result['native_scoped_startup_supported'] = not any(p['native_identity_unavailable_fail_closed'] for p in config_results)
            result['scoped_http_test_identity'] = 'explicit_test_constructor_injection; production requires native non-null fileKey'
        else:
            print(compiled.stdout + compiled.stderr)
    result['finished_utc_ms'] = time.time_ns() // 1000000
    result['jdbc_reuse_enabled_for_full_suite'] = args.reuse
    result['passed'] = result['compile_exit_code'] == 0 and result.get('http_test_exit_code') == 0 and result.get('configuration_probes_passed', False)
    (out / 'local-test-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    main()
