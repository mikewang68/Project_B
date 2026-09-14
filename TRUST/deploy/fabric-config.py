import json,pathlib,sys
r=pathlib.Path(__file__).resolve().parent.parent
c=r/'runtime/fabric-config'; c.mkdir(parents=True,exist_ok=True)
crypto=r/'runtime/secrets/crypto'
def sig(rule):return {'Type':'Signature','Rule':rule}
def implicit(rule):return {'Type':'ImplicitMeta','Rule':rule}
orgs=[]
for i in [1,2]:
    m=f'Org{i}MSP'
    orgs.append({'Name':m,'ID':m,'MSPDir':str(crypto/f'peerOrganizations/org{i}.trust/msp'),
      'Policies':{'Readers':sig(f"OR('{m}.admin', '{m}.peer', '{m}.client')"),'Writers':sig(f"OR('{m}.admin', '{m}.client')"),'Admins':sig(f"OR('{m}.admin')"),'Endorsement':sig(f"OR('{m}.peer')")},
      'AnchorPeers':[{'Host':'127.0.0.1','Port':27051 if i==1 else 29051}]})
orderer_org={'Name':'OrdererMSP','ID':'OrdererMSP','MSPDir':str(crypto/'ordererOrganizations/orderer.trust/msp'),
 'Policies':{'Readers':sig("OR('OrdererMSP.member')"),'Writers':sig("OR('OrdererMSP.member')"),'Admins':sig("OR('OrdererMSP.admin')")},'OrdererEndpoints':['127.0.0.1:27050']}
policies={'Readers':implicit('ANY Readers'),'Writers':implicit('ANY Writers'),'Admins':implicit('MAJORITY Admins')}
tls=str(crypto/'ordererOrganizations/orderer.trust/orderers/orderer.orderer.trust/tls/server.crt')
profile={'Policies':policies,'Capabilities':{'V2_0':True},
 'Orderer':{'OrdererType':'etcdraft','Addresses':['127.0.0.1:27050'],'BatchTimeout':'1s','BatchSize':{'MaxMessageCount':20,'AbsoluteMaxBytes':'10 MB','PreferredMaxBytes':'512 KB'},
 'EtcdRaft':{'Consenters':[{'Host':'127.0.0.1','Port':27050,'ClientTLSCert':tls,'ServerTLSCert':tls}]},'Organizations':[orderer_org],'Policies':dict(policies,BlockValidation=implicit('ANY Writers')),'Capabilities':{'V2_0':True}},
 'Application':{'Organizations':orgs,'Policies':dict(policies,LifecycleEndorsement=implicit('MAJORITY Endorsement'),Endorsement=implicit('MAJORITY Endorsement')),'Capabilities':{'V2_5':True}}}
(c/'configtx.yaml').write_text(json.dumps({'Profiles':{'Trust':profile}},indent=2))
core=(r/'tools/fabric/config/core.yaml').read_text().replace('/opt/hyperledger/ccaas_builder',str(r/'tools/fabric/builders/ccaas'))
(c/'core.yaml').write_text(core)
(c/'orderer.yaml').write_text((r/'tools/fabric/config/orderer.yaml').read_text())
