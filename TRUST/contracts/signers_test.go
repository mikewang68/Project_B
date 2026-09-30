package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"github.com/hyperledger/fabric-chaincode-go/v2/shim"
	msp "github.com/hyperledger/fabric-protos-go-apiv2/msp"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"
	"math/big"
	"strings"
	"testing"
	"time"
)

type signerStub struct {
	shim.ChaincodeStubInterface
	creator []byte
	state   map[string][]byte
	fn      string
	args    []string
}

func (s *signerStub) GetCreator() ([]byte, error) { return s.creator, nil }
func (s *signerStub) GetTxTimestamp() (*timestamppb.Timestamp, error) {
	return timestamppb.New(time.Now()), nil
}
func (s *signerStub) GetState(k string) ([]byte, error) { return s.state[k], nil }
func (s *signerStub) PutState(k string, b []byte) error { s.state[k] = b; return nil }
func (s *signerStub) SetEvent(string, []byte) error     { return nil }
func (s *signerStub) CreateCompositeKey(kind string, attrs []string) (string, error) {
	return kind + ":" + strings.Join(attrs, ":"), nil
}
func (s *signerStub) GetFunctionAndParameters() (string, []string) { return s.fn, s.args }
func (s *signerStub) GetTxID() string                              { return "test-transaction" }
func testIdentity(t *testing.T, cn, mspID string, expired bool) ([]byte, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	until := time.Now().Add(time.Hour)
	if expired {
		until = time.Now().Add(-time.Hour)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: cn}, NotBefore: time.Now().Add(-24 * time.Hour), NotAfter: until, KeyUsage: x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	creator, _ := proto.Marshal(&msp.SerializedIdentity{Mspid: mspID, IdBytes: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})})
	sum := sha256.Sum256(der)
	return creator, hex.EncodeToString(sum[:])
}
func TestManagedSignerScopeAndRevocation(t *testing.T) {
	creator, fp := testIdentity(t, "wms-service", "Org2MSP", false)
	s := &signerStub{creator: creator, state: map[string][]byte{}}
	r := Record{OrgID: "B-PROJECT", SourceSystem: "WMS", WalletID: "00000000-0000-0000-0000-000000000001", SignerFingerprint: fp, ContractVersion: "2", EventType: "WAREHOUSE_IN"}
	if authorizeSigner(s, r) == nil {
		t.Fatal("accepted unregistered identity")
	}
	k, _ := policyKey(s, r.OrgID, r.SourceSystem, fp)
	p := SignerPolicy{OrgID: r.OrgID, SourceSystem: r.SourceSystem, MSPID: "Org2MSP", Fingerprint: fp, Enabled: true, Revision: 1}
	s.state[k], _ = json.Marshal(p)
	if err := authorizeSigner(s, r); err != nil {
		t.Fatal(err)
	}
	r.EventType = "SAFETY_ALERT"
	if authorizeSigner(s, r) == nil {
		t.Fatal("unapproved WMS event type allowed")
	}
	r.EventType = "WAREHOUSE_IN"
	r.SourceSystem = "OTHER"
	if authorizeSigner(s, r) == nil {
		t.Fatal("cross-source allowed")
	}
	r.SourceSystem = "WMS"
	r.OrgID = "OTHER"
	if authorizeSigner(s, r) == nil {
		t.Fatal("cross-org allowed")
	}
	r.OrgID = "B-PROJECT"
	r.SignerFingerprint = strings.Repeat("0", 64)
	if authorizeSigner(s, r) == nil {
		t.Fatal("forged fingerprint allowed")
	}
	r.SignerFingerprint = fp
	p.Enabled = false
	s.state[k], _ = json.Marshal(p)
	if authorizeSigner(s, r) == nil {
		t.Fatal("disabled policy allowed")
	}
}
func TestPolicyRequiresNetworkAdminAndSequentialRevision(t *testing.T) {
	creator, fp := testIdentity(t, "wms-service", "Org1MSP", false)
	s := &signerStub{creator: creator, state: map[string][]byte{}}
	p := SignerPolicy{OrgID: "B-PROJECT", SourceSystem: "WMS", MSPID: "Org1MSP", Fingerprint: fp, Enabled: true, Revision: 1, ChangeID: "00000000-0000-0000-0000-000000000001"}
	b, _ := json.Marshal(p)
	if setSigner(s, []string{string(b)}).Status == 200 {
		t.Fatal("non-admin changed policy")
	}
	s.creator, _ = testIdentity(t, "Admin@org1.trust", "Org1MSP", false)
	if response := setSigner(s, []string{string(b)}); response.Status != 200 {
		t.Fatal(response.Message)
	}
	if setSigner(s, []string{string(b)}).Status == 200 {
		t.Fatal("stale policy revision accepted")
	}
}
func TestLegacyReadCompatibilityAndRestrictedNewWrites(t *testing.T) {
	creator, _ := testIdentity(t, "User1@org1.trust", "Org1MSP", false)
	s := &signerStub{creator: creator, state: map[string][]byte{}}
	r := Record{OrgID: "B-PROJECT"}
	if err := authorizeSigner(s, r); err != nil {
		t.Fatal(err)
	}
	r.OrgID = "OTHER"
	if authorizeSigner(s, r) == nil {
		t.Fatal("legacy scope expanded")
	}
	id := "00000000-0000-0000-0000-000000000001"
	k, _ := key(s, "B-PROJECT", id)
	old := []byte(`{"id":"old","eventSha256":"historical-digest"}`)
	s.state[k] = old
	s.fn = "GetEvent"
	s.args = []string{"B-PROJECT", id}
	result := (&EvidenceContract{}).Invoke(s)
	if string(result.Payload) != string(old) {
		t.Fatal("historical payload changed")
	}
	s.creator, _ = testIdentity(t, "User1@org1.trust", "Org1MSP", true)
	r.OrgID = "B-PROJECT"
	if authorizeSigner(s, r) == nil {
		t.Fatal("expired certificate accepted")
	}
}

func TestAlternateSourcePolicyRestrictsManagedEventTypes(t *testing.T) {
	creator, fp := testIdentity(t, "alternate-wms-service", "Org2MSP", false)
	s := &signerStub{creator: creator, state: map[string][]byte{}}
	r := Record{OrgID: "B-PROJECT", SourceSystem: "WMS-SIM", WalletID: "00000000-0000-0000-0000-000000000001", SignerFingerprint: fp, ContractVersion: "2", EventType: "WAREHOUSE_IN"}
	k, _ := policyKey(s, r.OrgID, r.SourceSystem, fp)
	p := SignerPolicy{OrgID: r.OrgID, SourceSystem: r.SourceSystem, MSPID: "Org2MSP", Fingerprint: fp, Enabled: true, Revision: 1, EventTypes: []string{"WAREHOUSE_IN"}}
	s.state[k], _ = json.Marshal(p)
	if err := authorizeSigner(s, r); err != nil {
		t.Fatal(err)
	}
	for _, kind := range []string{"DISPATCH", "SAFETY_ALERT"} {
		r.EventType = kind
		if authorizeSigner(s, r) == nil {
			t.Fatal("alternate source escaped explicit event type policy:", kind)
		}
	}
}

func TestMissingPolicyTypesKeepOnlyReceiptAndDispatchForEverySource(t *testing.T) {
	creator, fp := testIdentity(t, "alternate-wms-service", "Org2MSP", false)
	s := &signerStub{creator: creator, state: map[string][]byte{}}
	r := Record{OrgID: "B-PROJECT", SourceSystem: "WMS-SIM", WalletID: "00000000-0000-0000-0000-000000000001", SignerFingerprint: fp, ContractVersion: "2"}
	k, _ := policyKey(s, r.OrgID, r.SourceSystem, fp)
	// Simulate old ledger JSON where eventTypes did not exist, without rewriting the state.
	old := map[string]interface{}{"orgId": r.OrgID, "sourceSystem": r.SourceSystem, "mspId": "Org2MSP", "fingerprint": fp, "enabled": true, "revision": 1}
	original, _ := json.Marshal(old)
	s.state[k] = original
	for _, kind := range []string{"WAREHOUSE_IN", "DISPATCH"} {
		r.EventType = kind
		if err := authorizeSigner(s, r); err != nil {
			t.Fatal(err)
		}
	}
	r.EventType = "SAFETY_ALERT"
	if authorizeSigner(s, r) == nil {
		t.Fatal("old policy missing types became unrestricted")
	}
	if string(s.state[k]) != string(original) {
		t.Fatal("legacy policy was rewritten during authorization")
	}
	old["eventTypes"] = []string{}
	s.state[k], _ = json.Marshal(old)
	r.EventType = "WAREHOUSE_IN"
	if authorizeSigner(s, r) == nil {
		t.Fatal("explicit empty allowlist accepted")
	}
}

func TestSetSignerValidatesNormalizesAndDefaultsEventTypes(t *testing.T) {
	creator, fp := testIdentity(t, "Admin@org1.trust", "Org1MSP", false)
	s := &signerStub{creator: creator, state: map[string][]byte{}}
	p := SignerPolicy{OrgID: "B-PROJECT", SourceSystem: "WMS-SIM", MSPID: "Org1MSP", Fingerprint: fp, Enabled: true, Revision: 1, ChangeID: "00000000-0000-0000-0000-000000000001"}
	for _, types := range [][]string{{}, {""}, {"lower_case"}, {"WAREHOUSE_IN", "INVALID-TYPE"}} {
		p.EventTypes = types
		b, _ := json.Marshal(p)
		if setSigner(s, []string{string(b)}).Status == 200 {
			t.Fatal("invalid event types accepted:", types)
		}
	}
	p.EventTypes = []string{"WAREHOUSE_IN", "DISPATCH", "WAREHOUSE_IN"}
	b, _ := json.Marshal(p)
	response := setSigner(s, []string{string(b)})
	if response.Status != 200 {
		t.Fatal(response.Message)
	}
	var saved SignerPolicy
	if json.Unmarshal(response.Payload, &saved) != nil {
		t.Fatal("invalid response")
	}
	if strings.Join(saved.EventTypes, ",") != "DISPATCH,WAREHOUSE_IN" {
		t.Fatal("policy types not deduplicated and sorted")
	}
	p.SourceSystem = "WMS-OLD"
	p.EventTypes = nil
	b, _ = json.Marshal(p)
	response = setSigner(s, []string{string(b)})
	if response.Status != 200 {
		t.Fatal(response.Message)
	}
	if json.Unmarshal(response.Payload, &saved) != nil || strings.Join(saved.EventTypes, ",") != "DISPATCH,WAREHOUSE_IN" {
		t.Fatal("missing policy types did not receive bounded default")
	}
}
