package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"encoding/json"
	"encoding/pem"
	msp "github.com/hyperledger/fabric-protos-go-apiv2/msp"
	"google.golang.org/protobuf/proto"
	"math/big"
	"testing"
	"time"
)

func TestIdentityProbeRequiresCertifiedBusinessAttributesAndWritesNothing(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	for _, valid := range []bool{false, true} {
		template := &x509.Certificate{SerialNumber: big.NewInt(42), Subject: pkix.Name{CommonName: "stable-user", OrganizationalUnit: []string{"client"}}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
		if valid {
			template.ExtraExtensions = []pkix.Extension{{Id: asn1.ObjectIdentifier{1, 2, 3, 4, 5, 6, 7, 8, 1}, Value: []byte(`{"attrs":{"app.org":"ORG1","app.userId":"stable-user"}}`)}}
		}
		der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
		if err != nil {
			t.Fatal(err)
		}
		creator, _ := proto.Marshal(&msp.SerializedIdentity{Mspid: "Org1MSP", IdBytes: pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})})
		stub := &signerStub{creator: creator, state: map[string][]byte{}, fn: "IdentityProbe"}
		response := (&EvidenceContract{}).Invoke(stub)
		if valid {
			if response.Status != 200 {
				t.Fatalf("valid certified identity denied: %s", response.Message)
			}
			var result map[string]string
			if err := json.Unmarshal(response.Payload, &result); err != nil {
				t.Fatal(err)
			}
			if result["mspId"] != "Org1MSP" || result["userId"] != "stable-user" || result["orgId"] != "ORG1" {
				t.Fatal(result)
			}
		} else if response.Status == 200 {
			t.Fatal("uncertified identity accepted")
		}
		if len(stub.state) != 0 {
			t.Fatal("read-only probe changed ledger")
		}
		stub.args = []string{"forged-user"}
		if (&EvidenceContract{}).Invoke(stub).Status == 200 {
			t.Fatal("client input accepted")
		}
	}
}
