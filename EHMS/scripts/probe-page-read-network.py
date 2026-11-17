"""Read-only TCP/HTTP stage probe. Does not inspect keys or network policy secrets."""
import subprocess
source=r'''
import socket,time,json,urllib.request,urllib.error,pathlib
out=[]
for port,path in [(20983,'/actuator/health'),(18090,'/ehm/'),(18090,'/api/ehm/v1/auth/me')]:
 row={'port':port,'path':path};start=time.monotonic()
 try:
  with socket.create_connection(('127.0.0.1',port),3) as conn:
   row['tcpConnected']=True;row['tcpElapsedMs']=round((time.monotonic()-start)*1000)
   conn.settimeout(6);conn.sendall(bytes([13,10]).join([('GET '+path+' HTTP/1.1').encode(),b'Host: 127.0.0.1',b'Connection: close',b'',b'']))
   data=conn.recv(300);line=data.splitlines()[0].decode('ascii',errors='replace') if data else 'EOF';row['httpStatusLine']=line if line.startswith('HTTP/') else 'NON_HTTP_RESPONSE'
 except Exception as e:row['errorType']=type(e).__name__;row['stage']='HTTP_READ' if row.get('tcpConnected') else 'TCP_CONNECT'
 row['elapsedMs']=round((time.monotonic()-start)*1000);out.append(row)
print(json.dumps({'schema':'EHMS_NETWORK_STAGE_READONLY_V1','probes':out,'policyOrServiceChanged':False}))
'''
r=subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=60','b-node6','python3 -'],input=source.encode(),capture_output=True,timeout=120)
if r.returncode:raise SystemExit('NETWORK_STAGE_CHECK_FAILED')
print(r.stdout.decode())
