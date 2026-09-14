package main

import "testing"

func TestValidation(t *testing.T){
 r:=Record{ID:"00000000-0000-0000-0000-000000000001",RootID:"00000000-0000-0000-0000-000000000001",OrgID:"B-PROJECT",Version:1,EventSHA256:"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",ManifestSHA256:"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",ManifestCID:"bafkreiaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",SubmittedBy:"editor"}
 if err:=validate(r);err!=nil{t.Fatal(err)}
 r.EventSHA256="bad";if validate(r)==nil{t.Fatal("accepted invalid digest")}
 r.OrgID="\x00other";if validate(r)==nil{t.Fatal("accepted invalid namespace")}
}
