import copy
import datetime
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

spec=importlib.util.spec_from_file_location('policy',Path(__file__).resolve().parents[1]/'scripts/generic-signer-policy.py')
policy=importlib.util.module_from_spec(spec);spec.loader.exec_module(policy)


class PolicyTest(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        ca=Path(self.directory.name)/'ca.crt';ca.write_text('synthetic')
        self.target={'enabled':True,'purpose':'isolated-operation-log-policy','channel':'trust-iam-synthetic','contract':'evidence','peers':[{'address':'127.0.0.1:27051','tlsCa':str(ca)},{'address':'127.0.0.1:29051','tlsCa':str(ca)}],'orderer':'127.0.0.1:27050','ordererTlsCa':str(ca)}
        self.value={'orgId':'ORG','sourceSystem':'SYNTHETIC','mspId':'Org1MSP','fingerprint':'a'*64,'enabled':True,'revision':1,'changeId':'00000000-0000-0000-0000-000000000001','eventTypes':['OPERATION_LOG']}
        binding={k:self.value[k] for k in ['orgId','sourceSystem','mspId','fingerprint']}
        binding.update(state='APPROVED',action='BIND',walletState='ACTIVE',proposedBy='requester',reviewedBy='reviewer',requestId=self.value['changeId'],currentBindingRequestId=self.value['changeId'],observedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
        change={'policy':self.value,'binding':binding,'expectedPrevious':None}
        self.allow={'schemaVersion':1,'targets':{'synthetic':self.target},'changes':{'change':change}}
        self.plan={'schemaVersion':1,'targetId':'synthetic','target':copy.deepcopy(self.target),'changeId':'change','change':copy.deepcopy(change)}

    def test_independent_approval_and_exact_type(self):
        policy.validate(self.allow,self.plan)
        self.plan['change']['policy']['eventTypes']=['WAREHOUSE_IN','OPERATION_LOG']
        with self.assertRaisesRegex(policy.Refused,'CHANGE_NOT_APPROVED'):policy.validate(self.allow,self.plan)
        self.allow['changes']['change']=copy.deepcopy(self.plan['change'])
        with self.assertRaisesRegex(policy.Refused,'INVALID_POLICY'):policy.validate(self.allow,self.plan)

    def test_binding_self_review_and_stale_observation_rejected(self):
        for field,value in [('reviewedBy','requester'),('currentBindingRequestId','other'),('observedAt','2020-01-01T00:00:00+00:00')]:
            a=copy.deepcopy(self.allow);p=copy.deepcopy(self.plan)
            a['changes']['change']['binding'][field]=value;p['change']['binding'][field]=value
            with self.assertRaises(policy.Refused):policy.validate(a,p)

    def test_existing_policy_never_submits_and_conflict_fails(self):
        runner=Mock(return_value=Mock(returncode=0,stdout=json.dumps(self.value)))
        self.assertFalse(policy.execute(self.target,self.value,None,True,runner)['submitted'])
        self.assertTrue(all(c.args[0][2]=='query' for c in runner.call_args_list))
        runner=Mock(side_effect=[Mock(returncode=0,stdout='null'),Mock(returncode=0,stdout=json.dumps(self.value))])
        with self.assertRaisesRegex(policy.Refused,'POLICY_CONFLICT'):policy.execute(self.target,self.value,None,True,runner)
        self.assertEqual(2,runner.call_count)

    def test_apply_once_then_verify_both_peers(self):
        runner=Mock(side_effect=[Mock(returncode=0,stdout='null'),Mock(returncode=0,stdout='null'),Mock(returncode=0,stdout=''),Mock(returncode=0,stdout=json.dumps(self.value)),Mock(returncode=0,stdout=json.dumps(self.value))])
        self.assertEqual('VERIFIED',policy.execute(self.target,self.value,None,True,runner)['status'])
        self.assertEqual(['query','query','invoke','query','query'],[c.args[0][2] for c in runner.call_args_list])

    def test_history_is_read_only_and_checks_all_peers(self):
        runner=Mock(return_value=Mock(returncode=0,stdout=json.dumps(self.value)))
        result=policy.execute(self.target,self.value,None,False,runner,history_revision=1)
        self.assertFalse(result['submitted']);self.assertEqual('HISTORY',result['status'])
        for call in runner.call_args_list:
            args=call.args[0];self.assertEqual('query',args[2]);self.assertEqual('GetSignerVersion',json.loads(args[-1])['Args'][0])
        inconsistent=Mock(side_effect=[Mock(returncode=0,stdout='null'),Mock(returncode=0,stdout=json.dumps(self.value))])
        with self.assertRaisesRegex(policy.Refused,'PEER_HISTORY_DISAGREEMENT'):
            policy.execute(self.target,self.value,None,False,inconsistent,history_revision=1)
        with self.assertRaisesRegex(policy.Refused,'INVALID_HISTORY_QUERY'):
            policy.execute(self.target,self.value,None,True,runner,history_revision=1)


if __name__=='__main__':unittest.main()
