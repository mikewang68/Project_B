// TRUST Fabric 网关样例客户端：用于在隔离通道上执行 读取/提交/签名人策略 操作。
// 仅用于运维与验收取证，不参与服务进程；身份来自独立 runtime/secrets/fabric 目录。
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"time"

	"github.com/hyperledger/fabric-gateway/pkg/client"
	"github.com/hyperledger/fabric-gateway/pkg/identity"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
)

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func fatal(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "fabric-sample:", err)
		os.Exit(1)
	}
}

func validateArgs(args []string) error {
	if len(args) < 2 {
		return fmt.Errorf("command and channel are required")
	}
	expected := map[string]int{"get": 4, "history": 4, "submit": 3, "set-signer": 3, "get-signer": 5}
	n, known := expected[args[0]]
	if !known || len(args) != n {
		return fmt.Errorf("invalid command or argument count")
	}
	for _, arg := range args {
		if arg == "" {
			return fmt.Errorf("empty arguments are forbidden")
		}
	}
	if args[0] == "submit" || args[0] == "set-signer" {
		if !regexp.MustCompile(`^trust-iam-[a-z0-9][a-z0-9-]{0,31}$`).MatchString(args[1]) {
			return fmt.Errorf("writes require a new trust-iam-* channel; published channels are forbidden")
		}
	}
	return nil
}

func main() {
	args := os.Args[1:]
	if err := validateArgs(args); err != nil {
		fmt.Fprintln(os.Stderr, err)
		fmt.Fprintln(os.Stderr, `usage:
  fabric-sample get <channel> <org> <id>
  fabric-sample history <channel> <org> <rootId>
  fabric-sample submit <channel> <record.json-file>
  fabric-sample set-signer <channel> <policy.json-file>
  fabric-sample get-signer <channel> <org> <source-system> <fingerprint>
env: FABRIC_ENDPOINT (default 127.0.0.1:27051), FABRIC_SERVER_NAME (default peer0.org1.trust),
     FABRIC_IDENTITY_DIR (directory containing tls-ca.crt/user.crt/user.key)`)
		os.Exit(2)
	}
	cmd, ch := args[0], args[1]
	idDir := envOr("FABRIC_IDENTITY_DIR", "runtime/secrets/fabric")
	caPEM, err := os.ReadFile(filepath.Join(idDir, "tls-ca.crt"))
	fatal(err)
	certPEM, err := os.ReadFile(filepath.Join(idDir, "user.crt"))
	fatal(err)
	keyPEM, err := os.ReadFile(filepath.Join(idDir, "user.key"))
	fatal(err)

	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		fatal(fmt.Errorf("invalid CA PEM"))
	}
	tlsConfig := &tls.Config{
		ServerName: envOr("FABRIC_SERVER_NAME", "peer0.org1.trust"),
		RootCAs:    pool,
	}
	conn, err := grpc.Dial(envOr("FABRIC_ENDPOINT", "127.0.0.1:27051"),
		grpc.WithTransportCredentials(credentials.NewTLS(tlsConfig)))
	fatal(err)
	defer conn.Close()

	cert, err := identity.CertificateFromPEM(certPEM)
	fatal(err)
	id, err := identity.NewX509Identity("Org1MSP", cert)
	fatal(err)
	key, err := identity.PrivateKeyFromPEM(keyPEM)
	fatal(err)
	sign, err := identity.NewPrivateKeySign(key)
	fatal(err)

	gateway, err := client.Connect(id, client.WithSign(sign), client.WithClientConnection(conn),
		client.WithEvaluateTimeout(15*time.Second), client.WithEndorseTimeout(20*time.Second),
		client.WithSubmitTimeout(15*time.Second), client.WithCommitStatusTimeout(30*time.Second))
	fatal(err)
	defer gateway.Close()
	contract := gateway.GetNetwork(ch).GetContract("evidence")
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	switch cmd {
	case "get":
		payload, err := contract.EvaluateTransaction("GetEvent", args[2], args[3])
		fatal(err)
		fmt.Println(string(payload))
	case "history":
		payload, err := contract.EvaluateTransaction("GetEventHistory", args[2], args[3])
		fatal(err)
		fmt.Println(string(payload))
	case "get-signer":
		payload, err := contract.EvaluateTransaction("GetSigner", args[2], args[3], args[4])
		fatal(err)
		fmt.Println(string(payload))
	case "submit":
		raw, err := os.ReadFile(args[2])
		fatal(err)
		var rec map[string]interface{}
		if json.Unmarshal(raw, &rec) != nil {
			fatal(fmt.Errorf("invalid record JSON"))
		}
		fn := "RegisterEvent"
		if s, _ := rec["supersedesId"].(string); s != "" {
			fn = "AppendCorrection"
		}
		_, commit, err := contract.SubmitAsync(fn, client.WithArguments(string(raw)))
		fatal(err)
		status, err := commit.StatusWithContext(ctx)
		fatal(err)
		if !status.Successful {
			fatal(fmt.Errorf("transaction failed: code=%v txid=%s", status.Code, status.TransactionID))
		}
		orgID, _ := rec["orgId"].(string)
		id, _ := rec["id"].(string)
		payload, err := contract.EvaluateTransaction("GetEvent", orgID, id)
		fatal(err)
		out := struct {
			TxID        string          `json:"txId"`
			BlockNumber uint64          `json:"blockNumber"`
			Record      json.RawMessage `json:"record"`
		}{status.TransactionID, status.BlockNumber, payload}
		b, _ := json.MarshalIndent(out, "", "  ")
		fmt.Println(string(b))
	case "set-signer":
		raw, err := os.ReadFile(args[2])
		fatal(err)
		var pol map[string]interface{}
		if json.Unmarshal(raw, &pol) != nil {
			fatal(fmt.Errorf("invalid policy JSON"))
		}
		_, commit, err := contract.SubmitAsync("SetSigner", client.WithArguments(string(raw)))
		fatal(err)
		status, err := commit.StatusWithContext(ctx)
		fatal(err)
		if !status.Successful {
			fatal(fmt.Errorf("set-signer failed: code=%v txid=%s", status.Code, status.TransactionID))
		}
		b, _ := json.MarshalIndent(struct {
			BlockNumber uint64          `json:"blockNumber"`
			Policy      json.RawMessage `json:"policy"`
		}{status.BlockNumber, raw}, "", "  ")
		fmt.Println(string(b))
	default:
		fatal(fmt.Errorf("unknown command: %s", cmd))
	}
}
