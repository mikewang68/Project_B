#!/usr/bin/env python3
"""Apply an independently approved OPERATION_LOG policy on an explicit isolated channel.

Default: validate local inputs only. --check queries all peers. --apply also submits
the exact approved change, only if every peer agrees with its expected prior state.
Run with the network maintainer's existing peer environment, never an app key.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys


class Refused(Exception):
    pass


def require(ok, code):
    if not ok:
        raise Refused(code)


def read(path):
    raw = Path(path).read_bytes()
    require(len(raw) <= 262144, "INPUT_TOO_LARGE")
    def unique(pairs):
        value = {}
        for k, v in pairs:
            require(k not in value, "DUPLICATE_KEY")
            value[k] = v
        return value
    return json.loads(raw, object_pairs_hook=unique)


def validate(allow, plan):
    require(allow['schemaVersion'] == plan['schemaVersion'] == 1, "INVALID_VERSION")
    target = allow['targets'][plan['targetId']]
    require(target == plan['target'] and target['enabled'] is True
            and target['purpose'] == 'isolated-operation-log-policy', "TARGET_NOT_APPROVED")
    require(re.fullmatch(r'trust-iam-[a-z0-9-]{1,32}', target['channel'])
            and target['contract'] == 'evidence', "ISOLATED_CHANNEL_REQUIRED")
    require(2 <= len(target['peers']) <= 8, "PEER_COVERAGE_REQUIRED")
    addresses = [p['address'] for p in target['peers']] + [target['orderer']]
    require(len(set(addresses)) == len(addresses), "DUPLICATE_ENDPOINT")
    for address in addresses:
        require(re.fullmatch(r'127\.0\.0\.1:[0-9]{4,5}', address), "LOOPBACK_REQUIRED")
        require(1024 <= int(address.split(':')[1]) <= 65535, "INVALID_PORT")
    for value in [target['ordererTlsCa']] + [p['tlsCa'] for p in target['peers']]:
        p = Path(value)
        require(p.is_absolute() and p.is_file() and not p.is_symlink(), "TLS_FILE_REQUIRED")
    approved = allow['changes'][plan['changeId']]
    require(approved == plan['change'], "CHANGE_NOT_APPROVED")
    policy, binding, previous = approved['policy'], approved['binding'], approved['expectedPrevious']
    require(set(policy) == {'orgId','sourceSystem','mspId','fingerprint','enabled','revision','changeId','eventTypes'}, "INVALID_POLICY")
    for key in ['orgId','sourceSystem','mspId']:
        require(re.fullmatch(r'[A-Za-z0-9._-]{1,80}', policy[key]), "INVALID_POLICY")
    require(re.fullmatch(r'[a-f0-9]{64}', policy['fingerprint'])
            and policy['eventTypes'] == ['OPERATION_LOG'] and policy['enabled'] is True
            and re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', policy['changeId']), "INVALID_POLICY")
    require(type(policy['revision']) is int and policy['revision'] == (1 if previous is None else previous['revision'] + 1), "INVALID_REVISION")
    require(binding['state'] == 'APPROVED' and binding['action'] == 'BIND'
            and binding['walletState'] == 'ACTIVE'
            and binding['proposedBy'] and binding['reviewedBy']
            and binding['proposedBy'] != binding['reviewedBy']
            and binding['requestId'] == binding['currentBindingRequestId'] == policy['changeId'], "BINDING_NOT_APPROVED")
    for key in ['orgId','sourceSystem','mspId','fingerprint']:
        require(binding[key] == policy[key], "BINDING_CHANGED")
    observed = datetime.datetime.fromisoformat(binding['observedAt'])
    require(observed.tzinfo is not None and 0 <= (datetime.datetime.now(datetime.timezone.utc) - observed).total_seconds() <= 900, "REFRESH_BINDING_OBSERVATION")
    return target, policy, previous


def execute(target, policy, previous, apply, runner=subprocess.run, history_revision=None):
    def call(args, peer):
        env = dict(os.environ, CORE_PEER_ADDRESS=peer['address'], CORE_PEER_TLS_ROOTCERT_FILE=peer['tlsCa'], CORE_PEER_TLS_ENABLED='true')
        return runner(['peer', 'chaincode', *args], env=env, capture_output=True, text=True, timeout=60)
    def query():
        values = []
        for peer in target['peers']:
            args = ['GetSigner',policy['orgId'],policy['sourceSystem'],policy['fingerprint']]
            if history_revision is not None:
                require(type(history_revision) is int and history_revision > 0 and not apply, 'INVALID_HISTORY_QUERY')
                args[0] = 'GetSignerVersion'
                args.append(str(history_revision))
            result = call(['query','-C',target['channel'],'-n',target['contract'],'-c',json.dumps({'Args':args})], peer)
            require(result.returncode == 0, "POLICY_QUERY_FAILED")
            values.append(json.loads(result.stdout))
        return values
    before = query()
    if history_revision is not None:
        require(all(p == before[0] for p in before), 'PEER_HISTORY_DISAGREEMENT')
        return {'status':'HISTORY','submitted':False,'revision':history_revision,'policy':before[0]}
    if all(p == policy for p in before):
        return {'status':'ALREADY_MATCHED','submitted':False,'policy':policy}
    require(all(p == previous for p in before), "POLICY_CONFLICT")
    if not apply:
        return {'status':'READY','submitted':False,'before':previous,'after':policy}
    args = ['invoke','-C',target['channel'],'-n',target['contract'],'-c',json.dumps({'Args':['SetSigner',json.dumps(policy,separators=(',',':'))]}),'-o',target['orderer'],'--tls','--cafile',target['ordererTlsCa']]
    for peer in target['peers']:
        args += ['--peerAddresses',peer['address'],'--tlsRootCertFiles',peer['tlsCa']]
    args += ['--waitForEvent','--waitForEventTimeout','30s']
    # Never retry invoke after a lost response. A later run queries before acting.
    try:
        call(args, target['peers'][0])
    except subprocess.TimeoutExpired:
        raise Refused('SUBMISSION_UNKNOWN_QUERY_BEFORE_RETRY') from None
    require(all(p == policy for p in query()), 'SUBMISSION_UNCONFIRMED_QUERY_BEFORE_RETRY')
    return {'status':'VERIFIED','submitted':True,'policy':policy}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allowlist', required=True)
    parser.add_argument('--plan', required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--apply', action='store_true')
    mode.add_argument('--history-revision', type=int)
    args = parser.parse_args()
    try:
        require(Path(args.allowlist).resolve() != Path(args.plan).resolve(), 'INDEPENDENT_ALLOWLIST_REQUIRED')
        target, policy, previous = validate(read(args.allowlist), read(args.plan))
        result = execute(target, policy, previous, args.apply, history_revision=args.history_revision) if args.check or args.apply or args.history_revision is not None else {'status':'DRY_RUN','networkAccess':False,'policy':policy}
        print(json.dumps(result))
        return 0
    except Exception as error:
        print(json.dumps({'status':'FAILED','code':str(error) if isinstance(error, Refused) else 'OPERATION_UNAVAILABLE'}))
        return 1


if __name__ == '__main__':
    sys.exit(main())
