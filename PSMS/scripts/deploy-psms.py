"""Upload PSMS-only artifacts and run staged deployment checks."""
from pathlib import Path
import sys
import shlex
from server_connection import ROOT, connect, run, read_env

RELEASE = '20261013-1240'
STAGE = '/home/lrz/psms-release-' + RELEASE
phase = sys.argv[1] if len(sys.argv) > 1 else 'stage'
jump, remote, cfg = connect()
password = cfg.get('NODE6_SUDO_PASSWORD') or cfg.get('NODE6_PASSWORD')
def sudo(command):
    return run(remote, 'sudo -S -p "" bash -c ' + shlex.quote(command), password)
try:
    if phase == 'stage':
        run(remote, 'mkdir -p ' + STAGE)
        sftp = remote.open_sftp()
        for name in ['psms-frontend-20261012.tar.gz', 'psms-backend-20261012.tar.gz']:
            sftp.put(str(ROOT / '.deploy' / name), STAGE + '/' + name)
        for name in ['deploy-db-check.mjs']:
            sftp.put(str(ROOT / 'scripts' / name), STAGE + '/' + name)
        sftp.put(str(ROOT / 'deploy' / 'psms-s1-simulator.service'), STAGE + '/psms-s1-simulator.service')
        ai = read_env(ROOT / 'backend' / '.env.ai')
        content = '\n'.join(key + '=' + ai.get(key, '') for key in ['AI_BASE_URL', 'AI_MODEL', 'DASHSCOPE_API_KEY']) + '\nAI_DEMO_AUTO_LOGIN=true\n'
        with sftp.open(STAGE + '/ai.env', 'w') as f:
            sftp.chmod(STAGE + '/ai.env', 0o600)
            f.write(content)
        sftp.close()
        print(sudo('set -e; umask 077; mkdir -p /opt/project-b/PSMS/.deploy/' + RELEASE + '/backend /opt/project-b/PSMS/.deploy/' + RELEASE + '/frontend; '
          + 'tar -xzf ' + STAGE + '/psms-backend-20261012.tar.gz -C /opt/project-b/PSMS/.deploy/' + RELEASE + '/backend; '
          + 'tar -xzf ' + STAGE + '/psms-frontend-20261012.tar.gz -C /opt/project-b/PSMS/.deploy/' + RELEASE + '/frontend; '
          + 'rm -rf /opt/project-b/PSMS/.deploy/' + RELEASE + '/backend/node_modules; ln -s /opt/project-b/PSMS/backend/node_modules /opt/project-b/PSMS/.deploy/' + RELEASE + '/backend/node_modules; '
          + '/opt/node-24.18.0/bin/node ' + STAGE + '/deploy-db-check.mjs /opt/project-b/PSMS/.deploy/' + RELEASE + '/backend /opt/project-b/PSMS/.deploy/' + RELEASE + '/database-before.json'))
    elif phase == 'inspect':
        print(sudo("python3 -c \"from pathlib import Path; print('Existing AI variable names:', [x.split('=',1)[0] for x in Path('/etc/project-b/PSMS/service.env').read_text().splitlines() if x.startswith(('AI_', 'DASHSCOPE_'))])\""))
    elif phase == 'model-check':
        wrapper = "import fs from 'node:fs'; import { createRequire } from 'node:module'; const require=createRequire('/opt/project-b/PSMS/backend/package.json'); const dotenv=require('dotenv'); Object.assign(process.env, dotenv.parse(fs.readFileSync('/etc/project-b/PSMS/service.env')), dotenv.parse(fs.readFileSync('" + STAGE + "/ai.env'))); await import('/opt/project-b/PSMS/.deploy/" + RELEASE + "/backend/dist/scripts/ai-check.js');"
        print(sudo('/opt/node-24.18.0/bin/node --input-type=module -e ' + shlex.quote(wrapper)))
    elif phase == 'publish':
        sftp = remote.open_sftp()
        sftp.put(str(ROOT / 'scripts' / 'deploy-publish.sh'), STAGE + '/deploy-publish.sh')
        sftp.put(str(ROOT / 'deploy' / 'psms-s1-simulator.service'), STAGE + '/psms-s1-simulator.service')
        sftp.put(str(ROOT / 'scripts' / 'deploy-db-check.mjs'), STAGE + '/deploy-db-check.mjs')
        sftp.close()
        print(sudo('bash ' + STAGE + '/deploy-publish.sh'))
    elif phase == 'frontend-fix':
        sftp = remote.open_sftp()
        sftp.put(str(ROOT / '.deploy' / 'psms-frontend-20261012.tar.gz'), STAGE + '/frontend-fix.tar.gz')
        sftp.close()
        front = '/data/app/project-b-20260921/PSMS/production-dispatch'
        print(sudo('set -e; test ! -e ' + front + '.backup-20261012-1829; mkdir ' + front + '.new-20261012-1829; tar -xzf ' + STAGE + '/frontend-fix.tar.gz -C ' + front + '.new-20261012-1829; chmod -R a+rX ' + front + '.new-20261012-1829; mv ' + front + ' ' + front + '.backup-20261012-1829; mv ' + front + '.new-20261012-1829 ' + front + '; curl -kfsS https://127.0.0.1:18443/production-dispatch/ > /opt/project-b/PSMS/.deploy/' + RELEASE + '/served-index-final.html; cmp ' + front + '/index.html /opt/project-b/PSMS/.deploy/' + RELEASE + '/served-index-final.html; echo PSMS-frontend-fallback-published'))
    elif phase == 'final-check':
        check = "import fs from 'node:fs'; import { createRequire } from 'node:module'; const require=createRequire('/opt/project-b/PSMS/backend/package.json'); const cfg=require('dotenv').parse(fs.readFileSync('/etc/project-b/PSMS/service.env')); const {Client}=require('pg'); const client=new Client({host:cfg.OPENGAUSS_HOST,port:Number(cfg.OPENGAUSS_PORT||5432),database:cfg.OPENGAUSS_DATABASE,user:cfg.OPENGAUSS_USERNAME,password:cfg.OPENGAUSS_PASSWORD}); await client.connect(); const columns=await client.query(\"SELECT COUNT(*) AS tables FROM information_schema.columns WHERE table_schema='public' AND column_name='valid'\"); console.log('PSMS tables with valid column:', columns.rows[0].tables); const trial=await client.query(\"SELECT COUNT(*) AS count FROM information_schema.schemata WHERE schema_name='psms_release_trial_20261012'\"); console.log('Retained trial schemas:', trial.rows[0].count); await client.end();"
        print(sudo('/opt/node-24.18.0/bin/node --input-type=module -e ' + shlex.quote(check)))
        print(sudo('systemctl is-active project-b-psms.service; stat -c "%a %U %G" /opt/project-b/PSMS/backend/.env.ai; rm -f -- ' + STAGE + '/ai.env; sha256sum /data/app/project-b-20260921/PSMS/production-dispatch/index.html /opt/project-b/PSMS/backend/dist/index.js'))
    elif phase == 'valid-check':
        check = "import fs from 'node:fs'; import { createRequire } from 'node:module'; const require=createRequire('/opt/project-b/PSMS/backend/package.json'); const cfg=require('dotenv').parse(fs.readFileSync('/etc/project-b/PSMS/service.env')); const {Client}=require('pg'); const client=new Client({host:cfg.OPENGAUSS_HOST,port:Number(cfg.OPENGAUSS_PORT||5432),database:cfg.OPENGAUSS_DATABASE,user:cfg.OPENGAUSS_USERNAME,password:cfg.OPENGAUSS_PASSWORD}); await client.connect(); const tables=['users','plans','work_orders','tasks','equipment','exceptions','interlocks','appointments','offline_packets','config_versions','audit_logs','user_data_scopes','plan_cargo_items','work_order_crew','task_crew','task_dependencies','exception_evidence','interlock_input_signals','appointment_documents','offline_packet_conflict_fields','config_change_history']; const out={}; for(const t of tables){const r=await client.query('SELECT COUNT(*)::int AS total, COUNT(*) FILTER (WHERE valid=1)::int AS active, COUNT(*) FILTER (WHERE valid=0)::int AS invalid FROM '+t); out[t]=r.rows[0];} console.log(JSON.stringify(out)); await client.end();"
        print(sudo('/opt/node-24.18.0/bin/node --input-type=module -e ' + shlex.quote(check)))
    elif phase == 'sim-check':
        print(sudo("systemctl is-active project-b-psms-simulator.service; journalctl -u project-b-psms-simulator.service -n 3 --no-pager; curl -sS --max-time 10 http://127.0.0.1:8086/query?db=psms_telemetry --data-urlencode 'q=SELECT * FROM equipment_telemetry ORDER BY time DESC LIMIT 4'"))
    elif phase == 'audit-check':
        check = "import fs from 'node:fs'; import { createRequire } from 'node:module'; const require=createRequire('/opt/project-b/PSMS/backend/package.json'); const cfg=require('dotenv').parse(fs.readFileSync('/etc/project-b/PSMS/service.env')); const {Client}=require('pg'); const client=new Client({host:cfg.OPENGAUSS_HOST,port:Number(cfg.OPENGAUSS_PORT||5432),database:cfg.OPENGAUSS_DATABASE,user:cfg.OPENGAUSS_USERNAME,password:cfg.OPENGAUSS_PASSWORD}); await client.connect(); const r=await client.query(\"SELECT action,writelog_state,chain_state,chain_error FROM audit_logs ORDER BY created_at DESC LIMIT 3\"); console.log(JSON.stringify(r.rows)); await client.end();"
        print(sudo('/opt/node-24.18.0/bin/node --input-type=module -e ' + shlex.quote(check)))
    else: raise ValueError('Unknown phase')
finally: remote.close(); jump.close()
