package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/hyperledger/fabric-chaincode-go/v2/pkg/cid"
	"github.com/hyperledger/fabric-chaincode-go/v2/shim"
	pb "github.com/hyperledger/fabric-protos-go-apiv2/peer"
	"regexp"
	"sort"
)

type SignerPolicy struct {
	OrgID        string   `json:"orgId"`
	SourceSystem string   `json:"sourceSystem"`
	MSPID        string   `json:"mspId"`
	Fingerprint  string   `json:"fingerprint"`
	Enabled      bool     `json:"enabled"`
	Revision     int      `json:"revision"`
	ChangeID     string   `json:"changeId"`
	EventTypes   []string `json:"eventTypes"`
}

var managedEventType = regexp.MustCompile(`^[A-Z_]{1,40}$`)

func normalizedEventTypes(types []string) ([]string, error) {
	// Missing fields in pre-2.1 policies keep only the original receipt/dispatch scope.
	if types == nil {
		return []string{"DISPATCH", "WAREHOUSE_IN"}, nil
	}
	if len(types) == 0 || len(types) > 80 {
		return nil, fmt.Errorf("nonempty bounded event type allowlist required")
	}
	seen := map[string]bool{}
	result := []string{}
	for _, kind := range types {
		if !managedEventType.MatchString(kind) {
			return nil, fmt.Errorf("invalid allowed event type")
		}
		if !seen[kind] {
			seen[kind] = true
			result = append(result, kind)
		}
	}
	sort.Strings(result)
	return result, nil
}
func policyKey(stub shim.ChaincodeStubInterface, org, source, fingerprint string) (string, error) {
	if !organization.MatchString(org) || !organization.MatchString(source) || !digest.MatchString(fingerprint) {
		return "", fmt.Errorf("invalid signer scope")
	}
	return stub.CreateCompositeKey("signer", []string{org, source, fingerprint})
}
func setSigner(stub shim.ChaincodeStubInterface, args []string) *pb.Response {
	msp, err := cid.GetMSPID(stub)
	if err != nil || msp != "Org1MSP" {
		return shim.Error("network administrator required")
	}
	cert, err := cid.GetX509Certificate(stub)
	if err != nil || cert == nil || cert.Subject.CommonName != "Admin@org1.trust" {
		return shim.Error("network administrator required")
	}
	if len(args) != 1 || len(args[0]) > 4096 {
		return shim.Error("one bounded policy required")
	}
	var p SignerPolicy
	if json.Unmarshal([]byte(args[0]), &p) != nil || !organization.MatchString(p.MSPID) || !identifier.MatchString(p.ChangeID) {
		return shim.Error("invalid signer policy")
	}
	p.EventTypes, err = normalizedEventTypes(p.EventTypes)
	if err != nil {
		return shim.Error(err.Error())
	}
	k, err := policyKey(stub, p.OrgID, p.SourceSystem, p.Fingerprint)
	if err != nil {
		return shim.Error(err.Error())
	}
	prev, err := stub.GetState(k)
	if err != nil {
		return shim.Error(err.Error())
	}
	var old SignerPolicy
	if prev != nil {
		if json.Unmarshal(prev, &old) != nil {
			return shim.Error("invalid previous signer policy")
		}
	}
	if p.Revision != old.Revision+1 {
		return shim.Error("signer policy revision conflict")
	}
	data, _ := json.Marshal(p)
	if err = stub.PutState(k, data); err != nil {
		return shim.Error(err.Error())
	}
	if err = stub.SetEvent("SignerPolicyChanged", data); err != nil {
		return shim.Error(err.Error())
	}
	return shim.Success(data)
}
func getSigner(stub shim.ChaincodeStubInterface, args []string) *pb.Response {
	if len(args) != 3 {
		return shim.Error("org, source and fingerprint required")
	}
	k, err := policyKey(stub, args[0], args[1], args[2])
	if err != nil {
		return shim.Error(err.Error())
	}
	data, err := stub.GetState(k)
	if err != nil {
		return shim.Error(err.Error())
	}
	if data == nil {
		data = []byte("null")
	}
	return shim.Success(data)
}
func authorizeSigner(stub shim.ChaincodeStubInterface, r Record) error {
	msp, err := cid.GetMSPID(stub)
	if err != nil {
		return err
	}
	cert, err := cid.GetX509Certificate(stub)
	if err != nil || cert == nil {
		return fmt.Errorf("X509 registrar required")
	}
	stamp, err := stub.GetTxTimestamp()
	if err != nil {
		return err
	}
	at := stamp.AsTime()
	if at.Before(cert.NotBefore) || at.After(cert.NotAfter) {
		return fmt.Errorf("registrar certificate expired or not yet valid")
	}
	if r.WalletID == "" {
		if r.SourceSystem != "" || r.SignerFingerprint != "" || r.ContractVersion != "" || r.EventType != "" {
			return fmt.Errorf("incomplete managed identity")
		}
		// Compatibility for the existing development submitter is deliberately scoped.
		if msp != "Org1MSP" || cert.Subject.CommonName != "User1@org1.trust" || r.OrgID != "B-PROJECT" {
			return fmt.Errorf("legacy registrar scope denied")
		}
		return nil
	}
	if !identifier.MatchString(r.WalletID) || r.ContractVersion != "2" {
		return fmt.Errorf("invalid managed identity version")
	}
	if !managedEventType.MatchString(r.EventType) {
		return fmt.Errorf("invalid managed event type")
	}
	sum := sha256.Sum256(cert.Raw)
	actual := hex.EncodeToString(sum[:])
	if r.SignerFingerprint != actual {
		return fmt.Errorf("signer fingerprint mismatch")
	}
	k, err := policyKey(stub, r.OrgID, r.SourceSystem, actual)
	if err != nil {
		return err
	}
	raw, err := stub.GetState(k)
	if err != nil {
		return err
	}
	var p SignerPolicy
	if raw == nil || json.Unmarshal(raw, &p) != nil || !p.Enabled || p.MSPID != msp {
		return fmt.Errorf("signer not authorized for organization/source")
	}
	allowed, err := normalizedEventTypes(p.EventTypes)
	if err != nil {
		return err
	}
	for _, kind := range allowed {
		if kind == r.EventType {
			return nil
		}
	}
	return fmt.Errorf("event type not authorized for organization/source")
}
