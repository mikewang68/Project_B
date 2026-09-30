"""Read-only verification of all eight isolated CA/CCAAS systemd services.

Do not use the aggregate exit code of `systemctl is-active unit1 unit2 ...`:
it can succeed when only some of the requested units are active.
"""
import argparse
import json
import subprocess
import time


UNITS = tuple(
    f'b-project-identity-{mode}-org{org}-{kind}.service'
    for mode in ('dev', 'test')
    for org in (1, 2)
    for kind in ('ca', 'chaincode')
)
PROPERTIES = ('ActiveState', 'SubState', 'MainPID', 'UnitFileState', 'NRestarts')


def snapshot():
    result = {}
    for unit in UNITS:
        command = ['systemctl', 'show', unit]
        for prop in PROPERTIES:
            command.extend(('-p', prop))
        output = subprocess.check_output(command, text=True, timeout=10)
        state = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
        if (state.get('ActiveState') != 'active'
                or state.get('SubState') != 'running'
                or state.get('UnitFileState') != 'enabled'
                or not state.get('MainPID', '').isdigit()
                or int(state['MainPID']) <= 0):
            raise RuntimeError(f'{unit} is not active/running/enabled: {state}')
        result[unit] = state
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stable-seconds', type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.stable_seconds <= 30:
        parser.error('--stable-seconds must be between 1 and 30')
    before = snapshot()
    time.sleep(args.stable_seconds)
    after = snapshot()
    if before != after:
        raise RuntimeError('PID, restart count or state changed during the stability window')
    print(json.dumps({'allEightActiveEnabled': True,
                      'stableSeconds': args.stable_seconds, 'units': after}))


if __name__ == '__main__':
    main()
