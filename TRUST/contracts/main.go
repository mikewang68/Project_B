package main

import (
 "encoding/json"
 "fmt"
 "os"
 "regexp"
 "strconv"

 "github.com/hyperledger/fabric-chaincode-go/v2/pkg/cid"
 "github.com/hyperledger/fabric-chaincode-go/v2/shim"
 pb "github.com/hyperledger/fabric-protos-go-apiv2/peer"
)

type Record struct {
 ID string `json:"id"`
 OrgID string `json:"orgId"`
 RootID string `json:"rootId"`
 Version int `json:"version"`
 SupersedesID string `json:"supersedesId"`
 EventSHA256 string `json:"eventSha256"`
 ManifestCID string `json:"manifestCid"`
 ManifestSHA256 string `json:"manifestSha256"`
 SubmittedBy string `json:"submittedBy"`
 TxID string `json:"txId"`
 LedgerIdentity string `json:"ledgerIdentity"`
}
type EvidenceContract struct{}
var digest = regexp.MustCompile(`^[a-f0-9]{64}$`)
var identifier = regexp.MustCompile(`^[a-f0-9-]{36}$`)
var organization = regexp.MustCompile(`^[A-Za-z0-9._-]{1,80}$`)
var contentID = regexp.MustCompile(`^[A-Za-z0-9]{20,120}$`)

func key(stub shim.ChaincodeStubInterface,org,id string)(string,error){
 if !organization.MatchString(org)||!identifier.MatchString(id){return "",fmt.Errorf("invalid event identity")}
 return stub.CreateCompositeKey("event",[]string{org,id})
}
func validate(r Record)error{
 if !identifier.MatchString(r.ID)||!identifier.MatchString(r.RootID)||!organization.MatchString(r.OrgID)||r.Version<1||r.Version>100000{return fmt.Errorf("invalid identity/version")}
 if !digest.MatchString(r.EventSHA256)||!digest.MatchString(r.ManifestSHA256)||!contentID.MatchString(r.ManifestCID){return fmt.Errorf("invalid evidence digest")}
 if len(r.SubmittedBy)==0||len(r.SubmittedBy)>80{return fmt.Errorf("invalid business identity")}
 return nil
}
func (c *EvidenceContract) Init(stub shim.ChaincodeStubInterface)*pb.Response{return shim.Success(nil)}
func (c *EvidenceContract) Invoke(stub shim.ChaincodeStubInterface)*pb.Response{
 fn,args:=stub.GetFunctionAndParameters()
 switch fn{
 case "RegisterEvent","AppendCorrection":
  if len(args)!=1||len(args[0])>16384{return shim.Error("one bounded record required")}
  msp,err:=cid.GetMSPID(stub);if err!=nil||msp!="Org1MSP"{return shim.Error("registrar organization required")}
  cert,err:=cid.GetX509Certificate(stub);if err!=nil||cert==nil||cert.Subject.CommonName!="User1@org1.trust"{return shim.Error("registrar certificate required")}
  var r Record; if err=json.Unmarshal([]byte(args[0]),&r);err!=nil{return shim.Error("invalid JSON")}
  if err=validate(r);err!=nil{return shim.Error(err.Error())}
  k,err:=key(stub,r.OrgID,r.ID);if err!=nil{return shim.Error(err.Error())}
  old,err:=stub.GetState(k);if err!=nil{return shim.Error(err.Error())}
  if old!=nil{
   var existing Record;if err=json.Unmarshal(old,&existing);err!=nil{return shim.Error("invalid stored record")}
   cmp:=existing;cmp.TxID="";cmp.LedgerIdentity="";r.TxID="";r.LedgerIdentity=""
   if cmp!=r{return shim.Error("event already exists with different content")};return shim.Success(old)
  }
  headKey,_:=stub.CreateCompositeKey("head",[]string{r.OrgID,r.RootID})
  if fn=="RegisterEvent"{
   if r.Version!=1||r.RootID!=r.ID||r.SupersedesID!=""{return shim.Error("invalid initial version")}
  }else{
   pk,err:=key(stub,r.OrgID,r.SupersedesID);if err!=nil{return shim.Error(err.Error())}
   prev,err:=stub.GetState(pk);if err!=nil||prev==nil{return shim.Error("previous event not registered")}
   var p Record;if json.Unmarshal(prev,&p)!=nil{return shim.Error("invalid previous record")}
   head,err:=stub.GetState(headKey);if err!=nil{return shim.Error(err.Error())}
   if r.RootID!=p.RootID||r.Version!=p.Version+1||string(head)!=p.ID{return shim.Error("correction must append to current version")}
  }
  r.TxID=stub.GetTxID();r.LedgerIdentity,err=cid.GetID(stub);if err!=nil{return shim.Error(err.Error())}
  bytes,err:=json.Marshal(r);if err!=nil{return shim.Error(err.Error())}
  if err=stub.PutState(k,bytes);err!=nil{return shim.Error(err.Error())}
  if err=stub.PutState(headKey,[]byte(r.ID));err!=nil{return shim.Error(err.Error())}
  vk,_:=stub.CreateCompositeKey("version",[]string{r.OrgID,r.RootID,fmt.Sprintf("%09d",r.Version)})
  if err=stub.PutState(vk,bytes);err!=nil{return shim.Error(err.Error())}
  if err=stub.SetEvent("EvidenceRegistered",bytes);err!=nil{return shim.Error(err.Error())}
  return shim.Success(bytes)
 case "GetEvent":
  if len(args)!=2{return shim.Error("org and id required")};k,err:=key(stub,args[0],args[1]);if err!=nil{return shim.Error(err.Error())}
  bytes,err:=stub.GetState(k);if err!=nil{return shim.Error(err.Error())};if bytes==nil{return shim.Success([]byte("null"))};return shim.Success(bytes)
 case "GetEventHistory":
  if len(args)!=2{return shim.Error("org and root required")};if _,err:=key(stub,args[0],args[1]);err!=nil{return shim.Error(err.Error())}
  iter,err:=stub.GetStateByPartialCompositeKey("version",args);if err!=nil{return shim.Error(err.Error())};defer iter.Close()
  records:=[]Record{};for iter.HasNext(){kv,e:=iter.Next();if e!=nil{return shim.Error(e.Error())};var r Record;if json.Unmarshal(kv.Value,&r)!=nil{return shim.Error("invalid history")};records=append(records,r)}
  bytes,err:=json.Marshal(records);if err!=nil{return shim.Error(err.Error())};return shim.Success(bytes)
 default:return shim.Error("unknown function")
 }
}
func main(){
 disabled,_:=strconv.ParseBool(os.Getenv("CHAINCODE_TLS_DISABLED"))
 server:=&shim.ChaincodeServer{CCID:os.Getenv("CHAINCODE_ID"),Address:os.Getenv("CHAINCODE_ADDRESS"),CC:&EvidenceContract{},TLSProps:shim.TLSProperties{Disabled:disabled}}
 if !disabled {var err error;server.TLSProps.Key,err=os.ReadFile(os.Getenv("CHAINCODE_TLS_KEY"));if err!=nil{panic(err)};server.TLSProps.Cert,err=os.ReadFile(os.Getenv("CHAINCODE_TLS_CERT"));if err!=nil{panic(err)}}
 if err:=server.Start();err!=nil{panic(err)}
}
