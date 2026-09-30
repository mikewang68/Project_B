#!/usr/bin/env python3
"""Read-only preservation check. All hosts, paths and baselines come from private JSON.

Usage: python tests/protect-existing.py --config .local/protection-config.json --output
.local/test-results/protection.json. No status script is sourced; no chain/DB write occurs.
"""
import argparse
import concurrent.futures
import csv
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import subprocess


COMMON = "import os,json,subprocess,hashlib,urllib.request,urllib.parse\n"
DB = r'''
root=c['root']; env=os.environ.copy()
env['GAUSSHOME']=root+'/tools/opengauss'
env['LD_LIBRARY_PATH']=root+'/tools/opengauss/lib:'+env.get('LD_LIBRARY_PATH','')
columns=['id','org_id','source_system','source_event_id','event_type','batch_id','event_sha256','manifest_cid','tx_id','chain_state','file_state','received_at','manifest_sha256']
query="BEGIN READ ONLY;\nSET TIME ZONE 'Asia/Shanghai';\nSELECT "+','.join("COALESCE("+x+"::text,'')" for x in columns)+" FROM trust_data.events ORDER BY id;\nROLLBACK;\n"
r=subprocess.run([root+'/tools/opengauss/bin/gsql','-h',root+'/runtime','-p',str(c['port']),'-U',c['user'],'-d','trust','-X','-q','-A','-t','-F','\t','-v','ON_ERROR_STOP=1'],input=query,text=True,capture_output=True,env=env,timeout=30)
if r.returncode: raise RuntimeError(r.stderr)
rows=[dict(zip(columns,line.split('\t'))) for line in r.stdout.splitlines() if len(line.split('\t'))==len(columns)]
print(json.dumps({'events':rows,'readOnly':True,'query':query}))
'''
IPFS = r'''
def post(path,cid):
 url=c['url']+'/api/v0/'+path+'?arg='+urllib.parse.quote(cid,safe='')
 return urllib.request.urlopen(urllib.request.Request(url,data=b''),timeout=20).read()
results=[]
for e in c['events']:
 try:
  cid=e['manifest_cid'];raw=post('cat',cid);m=json.loads(raw);s=raw.decode()
  start=s.index('"event":')+len('"event":');start+=len(s[start:])-len(s[start:].lstrip())
  _,end=json.JSONDecoder().raw_decode(s,start)
  event_digest=hashlib.sha256(s[start:end].encode()).hexdigest()
  pin=json.loads(post('pin/ls',cid))['Keys'].get(cid,{})
  results.append({'id':e['id'],'cid':cid,'manifestDigestMatches':hashlib.sha256(raw).hexdigest()==e['manifest_sha256'],
   'eventDigestMatches':event_digest==e['event_sha256']==m['eventSha256'],
   'eventIdMatches':m['event']['id']==e['id'],'recursivePin':pin.get('Type')=='recursive'})
 except Exception as error:results.append({'id':e['id'],'error':str(error)})
print(json.dumps({'results':results}))
'''
FABRIC = r'''
root=c['root'];results={}
for org,port in [('1',27051),('2',29051)]:
 env=os.environ.copy();base=root+'/runtime/secrets/crypto/peerOrganizations/org'+org+'.trust'
 env.update({'FABRIC_CFG_PATH':root+'/runtime/fabric-config','FABRIC_LOGGING_SPEC':'error','CORE_PEER_TLS_ENABLED':'true',
 'CORE_PEER_LOCALMSPID':'Org'+org+'MSP','CORE_PEER_ADDRESS':'127.0.0.1:'+str(port),
 'CORE_PEER_MSPCONFIGPATH':base+'/users/Admin@org'+org+'.trust/msp',
 'CORE_PEER_TLS_ROOTCERT_FILE':base+'/peers/peer0.org'+org+'.trust/tls/ca.crt'})
 def run(args):
  p=subprocess.run([root+'/tools/fabric/bin/peer']+args,env=env,text=True,capture_output=True,timeout=30)
  if p.returncode:raise RuntimeError(p.stderr)
  out=p.stdout.strip();return json.loads(out.split(': ',1)[1] if out.startswith('Blockchain info: ') else out)
 results[org]={'info':run(['channel','getinfo','-c','trust']),
 'definition':run(['lifecycle','chaincode','querycommitted','-C','trust','-n','evidence','--output','json'])}
 results[org]['isolatedDefinition']=run(['lifecycle','chaincode','querycommitted','-C','trust-wallet-dev','-n','evidence','--output','json'])
 results[org]['isolatedLegacySamples']=[{'args':args,'result':run(['chaincode','query','-C','trust-wallet-dev','-n','evidence','-c',json.dumps({'Args':args})])} for args in c['sampleQueries']]
print(json.dumps({'peers':results}))
'''
APP = r'''
with open(c['jar'],'rb') as f: digest=hashlib.sha256(f.read()).hexdigest()
with urllib.request.urlopen(c['health'],timeout=10) as r: status=r.status;health=json.load(r)
print(json.dumps({'jarSha256':digest,'httpStatus':status,'health':health}))
'''


def remote(config, code):
    source = COMMON + "c=" + repr(config) + "\n" + code
    result = subprocess.run(["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                             "-o", "ConnectTimeout=10", config["host"], "python3", "-"],
                            input=source, text=True, encoding="utf-8", capture_output=True, timeout=240)
    if result.returncode:
        raise RuntimeError(result.stderr[-4000:])
    return json.loads(result.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if ".local" not in args.output.resolve().parts:
        parser.error("Raw evidence must be saved under an ignored .local directory")
    config = json.loads(args.config.read_text(encoding="utf-8-sig"))
    previous = json.loads(Path(config["previousEvidence"]).read_text(encoding="utf-8-sig"))
    with Path(config["baselineTsv"]).open(encoding="utf-8-sig", newline="") as stream:
        baseline = list(csv.DictReader(stream, delimiter="\t"))
    if len(baseline) != 29 or len(previous["events"]) != 35:
        raise RuntimeError("Expected 29 explicit baseline IDs and 35 prior manifests")
    config["ipfs"]["events"] = previous["events"]
    config["fabric"]["sampleQueries"] = [q["args"] for q in previous["newChannelSamples"]["queries"]]
    result = {"startedAt": datetime.now(timezone.utc).isoformat(), "readOnly": True,
              "baselineNote": "Remediation baseline; original pre-development baseline is unavailable",
              "baselineSha256": hashlib.sha256(Path(config["baselineTsv"]).read_bytes()).hexdigest()}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        jobs = {pool.submit(remote, config[key], script): key
                for key, script in [("database", DB), ("ipfs", IPFS), ("fabric", FABRIC), ("application", APP)]}
        for future in concurrent.futures.as_completed(jobs):
            key = jobs[future]
            try: result[key] = future.result()
            except Exception as error: result[key] = {"error": str(error)}
    checks = {}
    current = {e["id"]: e for e in result.get("database", {}).get("events", [])}
    checks["old29RowsAll12FieldsUnchanged"] = all(e["id"] in current and all(current[e["id"]][k] == v for k,v in e.items()) for e in baseline)
    checks["all35PriorRowsUnchanged"] = all(e["id"] in current and current[e["id"]] == e for e in previous["events"])
    checks["all35ManifestBytesEventBytesAndPins"] = len(result.get("ipfs", {}).get("results", [])) == 35 and all(
        all(e.get(k) is True for k in ["manifestDigestMatches", "eventDigestMatches", "eventIdMatches", "recursivePin"])
        for e in result.get("ipfs", {}).get("results", []))
    peers = result.get("fabric", {}).get("peers", {})
    checks["oldChannelBothPeersUnchanged"] = len(peers) == 2 and all(
        peer["info"] == json.loads(previous["node5"]["peers"][org]["ledgers"]["trust"]["info"]["out"].split(": ",1)[1])
        and peer["definition"] == json.loads(previous["node5"]["peers"][org]["ledgers"]["trust"]["definition"]["out"])
        for org,peer in peers.items())
    checks["isolatedBothPeersReadLegacyRecordsUnchanged"] = len(peers) == 2 and all(
        peer["isolatedDefinition"]["version"] == config.get("expectedIsolatedVersion", "2.0")
        and peer["isolatedDefinition"]["sequence"] == config.get("expectedIsolatedSequence", 2)
        and all(actual["args"] == expected["args"] and actual["result"] == json.loads(expected["out"])
                for actual, expected in zip(peer["isolatedLegacySamples"], previous["newChannelSamples"]["queries"]))
        for peer in peers.values())
    app = result.get("application", {})
    checks["oldApplicationHealthyAndJarUnchanged"] = app.get("httpStatus") == 200 and app.get("health", {}).get("status") == "UP" and app.get("jarSha256") == config["expectedJarSha256"]
    result.update({"checks": checks, "passed": all(checks.values()), "finishedAt": datetime.now(timezone.utc).isoformat()})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "checks": checks},ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
