import io,json,pathlib,tarfile,sys
r=pathlib.Path(__file__).resolve().parent.parent;i=int(sys.argv[1])
ca=(r/f'runtime/secrets/crypto/peerOrganizations/org{i}.trust/peers/peer0.org{i}.trust/tls/ca.crt').read_text()
connection={'address':f'127.0.0.1:{27059+(i-1)*2000}','dial_timeout':'10s','tls_required':True,'client_auth_required':False,'root_cert':ca}
def tar_bytes(files):
    stream=io.BytesIO()
    with tarfile.open(fileobj=stream,mode='w:gz') as t:
        for name,value in files.items():
            info=tarfile.TarInfo(name);info.size=len(value);info.mode=0o644;t.addfile(info,io.BytesIO(value))
    return stream.getvalue()
code=tar_bytes({'connection.json':json.dumps(connection,sort_keys=True).encode()})
(r/f'artifacts/evidence{i}.tar.gz').write_bytes(tar_bytes({'metadata.json':json.dumps({'type':'ccaas','label':f'evidence-org{i}'}).encode(),'code.tar.gz':code}))
