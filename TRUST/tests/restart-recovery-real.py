"""Fail only this application's IPFS adapter endpoint, then restart and resume.

Never stops IPFS, Fabric, databases, tunnels, or the old demonstration application.
"""
import datetime, json, pathlib, runpy, socket, subprocess, time, uuid

ROOT=pathlib.Path(__file__).resolve().parent.parent
client=runpy.run_path(str(ROOT/'tests/managed-wallet-real.py'))['Client']
config=ROOT/'runtime/secrets/isolated.env'
original=config.read_text()
assert 'TRUST_IPFS_URL=http://127.0.0.1:25001' in original
assert 'TRUST_DB_URL=jdbc:opengauss://127.0.0.1:25432/trust_wallet_dev\n' in original
with socket.socket() as s:s.bind(('127.0.0.1',25999))
out=ROOT/'.local/test-results/restart-recovery.json'
result={'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'fault':'only isolated application IPFS URL points to an unused loopback port'}
def app(action):
    p=subprocess.run(['bash',str(ROOT/'deploy/isolated-app.sh'),action],capture_output=True,text=True,timeout=80)
    if p.returncode:raise AssertionError(p.stdout+p.stderr)
    return p.stdout
try:
    app('stop')
    config.write_text(original.replace('TRUST_IPFS_URL=http://127.0.0.1:25001','TRUST_IPFS_URL=http://127.0.0.1:25999'))
    app('start')
    result['faultPid']=(ROOT/'runtime/pids/isolated-app.pid').read_text().strip()
    service=client(service=True)
    user=client('business-operator','fixture-operator')
    suffix=uuid.uuid4().hex
    data={'sourceSystem':'WMS','sourceEventId':'RESTART-'+suffix,'eventType':'WAREHOUSE_IN','businessObjectId':'SIMULATED-RESTART','batchId':'RESTART-'+suffix,'occurredAt':'2026-09-24T01:00:00Z','quantity':1,'unit':'吨','details':{'companyCode':'C01','warehouseCode':'WH01','ownerCode':'O01','operatorId':'simulated-restart-operator'}}
    id=service.call('POST','/integrations/wms/events',data,202)['id']
    end=time.monotonic()+30
    while time.monotonic()<end:
        failed=user.call('GET','/events/'+id)
        if failed['chain_state']=='FAILED':break
        time.sleep(.25)
    assert failed['chain_state']=='FAILED' and failed['manifest_cid'] is None,failed
    result['beforeRestart']=failed
finally:
    app('stop')
    config.write_text(original)
    app('start')
result['recoveredPid']=(ROOT/'runtime/pids/isolated-app.pid').read_text().strip()
assert result['faultPid']!=result['recoveredPid']
user=client('business-operator','fixture-operator')
end=time.monotonic()+100
while time.monotonic()<end:
    recovered=user.call('GET','/events/'+id)
    if recovered['chain_state']=='COMMITTED':break
    time.sleep(.5)
assert recovered['chain_state']=='COMMITTED',recovered
assert user.call('POST','/events/'+id+'/verify',{})['ok']
result.update(status='PASS',afterRestart=recovered)
out.write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('RESTART-DURABLE-RESUME PASS')
