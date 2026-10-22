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
	"strconv"
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

// Legacy keys are frozen once a versioned head exists. External current reads keep their shape.
func signerVersionKey(stub shim.ChaincodeStubInterface, org, source, fp string, revision int) (string, error) {
	if _, err := policyKey(stub, org, source, fp); err != nil {
		return "", err
	}
	if revision < 1 {
		return "", fmt.Errorf("invalid policy revision")
	}
	return stub.CreateCompositeKey("signer-version", []string{org, source, fp, strconv.Itoa(revision)})
}
func signerHeadKey(stub shim.ChaincodeStubInterface, org, source, fp string) (string, error) {
	if _, err := policyKey(stub, org, source, fp); err != nil {
		return "", err
	}
	return stub.CreateCompositeKey("signer-head", []string{org, source, fp})
}
func currentSigner(stub shim.ChaincodeStubInterface, org, source, fp string) ([]byte, error) {
	head, err := signerHeadKey(stub, org, source, fp)
	if err != nil {
		return nil, err
	}
	pointer, err := stub.GetState(head)
	if err != nil {
		return nil, err
	}
	if pointer == nil {
		legacy, _ := policyKey(stub, org, source, fp)
		return stub.GetState(legacy)
	}
	revision, err := strconv.Atoi(string(pointer))
	if err != nil {
		return nil, fmt.Errorf("invalid policy head")
	}
	key, err := signerVersionKey(stub, org, source, fp, revision)
	if err != nil {
		return nil, err
	}
	data, err := stub.GetState(key)
	if err == nil && data == nil {
		return nil, fmt.Errorf("missing policy version")
	}
	return data, err
}
func getSignerVersion(stub shim.ChaincodeStubInterface, args []string) *pb.Response {
	if len(args) != 4 {
		return shim.Error("org, source, fingerprint and revision required")
	}
	revision, err := strconv.Atoi(args[3])
	if err != nil {
		return shim.Error("invalid policy revision")
	}
	key, err := signerVersionKey(stub, args[0], args[1], args[2], revision)
	if err != nil {
		return shim.Error(err.Error())
	}
	data, err := stub.GetState(key)
	if err != nil {
		return shim.Error(err.Error())
	}
	if data == nil {
		legacy, _ := policyKey(stub, args[0], args[1], args[2])
		raw, e := stub.GetState(legacy)
		if e != nil {
			return shim.Error(e.Error())
		}
		var old SignerPolicy
		if raw != nil && json.Unmarshal(raw, &old) == nil && old.Revision == revision {
			data = raw
		}
	}
	if data == nil {
		data = []byte("null")
	}
	return shim.Success(data)
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
	_, err = policyKey(stub, p.OrgID, p.SourceSystem, p.Fingerprint)
	if err != nil {
		return shim.Error(err.Error())
	}
	prev, err := currentSigner(stub, p.OrgID, p.SourceSystem, p.Fingerprint)
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
	if prev != nil {
		oldKey, e := signerVersionKey(stub, p.OrgID, p.SourceSystem, p.Fingerprint, old.Revision)
		if e != nil {
			return shim.Error(e.Error())
		}
		saved, e := stub.GetState(oldKey)
		if e != nil {
			return shim.Error(e.Error())
		}
		if saved == nil {
			if e = stub.PutState(oldKey, prev); e != nil {
				return shim.Error(e.Error())
			}
		}
	}
	versionKey, _ := signerVersionKey(stub, p.OrgID, p.SourceSystem, p.Fingerprint, p.Revision)
	if existing, e := stub.GetState(versionKey); e != nil || existing != nil {
		return shim.Error("policy version already exists or unavailable")
	}
	if err = stub.PutState(versionKey, data); err != nil {
		return shim.Error(err.Error())
	}
	head, _ := signerHeadKey(stub, p.OrgID, p.SourceSystem, p.Fingerprint)
	if err = stub.PutState(head, []byte(strconv.Itoa(p.Revision))); err != nil {
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
	_, err := policyKey(stub, args[0], args[1], args[2])
	if err != nil {
		return shim.Error(err.Error())
	}
	data, err := currentSigner(stub, args[0], args[1], args[2])
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
	_, err = policyKey(stub, r.OrgID, r.SourceSystem, actual)
	if err != nil {
		return err
	}
	raw, err := currentSigner(stub, r.OrgID, r.SourceSystem, actual)
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
