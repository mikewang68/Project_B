"""Disable Kubo remote autoconfiguration for this offline evidence repository."""
import argparse
import datetime
import json
import os
import pathlib
import shutil


def configure(repo):
    repo = pathlib.Path(repo).resolve()
    file = repo / 'config'
    config = json.loads(file.read_text())
    config['AutoConf'] = dict(config.get('AutoConf', {}), Enabled=False)
    config['Bootstrap'] = []
    config.setdefault('DNS', {})['Resolvers'] = {}
    config.setdefault('Routing', {})['DelegatedRouters'] = []
    config.setdefault('Ipns', {})['DelegatedPublishers'] = []
    if json.loads(file.read_text()) == config:
        return False
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    backup = repo.parent / ('ipfs-config-before-offline-' + stamp + '.json')
    shutil.copyfile(file, backup)
    os.chmod(backup, 0o600)
    temp = repo / 'config.offline.tmp'
    with temp.open('w', encoding='utf-8') as stream:
        os.chmod(temp, 0o600)
        json.dump(config, stream, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    temp.replace(file)
    return True


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repo', help='Project Kubo repository; restart the daemon to apply')
    args = parser.parse_args()
    print('Offline configuration updated; restart to apply' if configure(args.repo) else 'Offline configuration already applied')
