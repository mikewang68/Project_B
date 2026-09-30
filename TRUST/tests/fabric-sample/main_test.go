package main

import "testing"

func TestArgumentGuardBeforeCredentialsOrNetwork(t *testing.T) {
	for _, args := range [][]string{
		{"submit", "trust", "sample.json"}, {"set-signer", "trust", "policy.json"},
		{"submit", "trust-wallet-dev", "sample.json"}, {"set-signer", "trust-wallet-compat-20260924", "policy.json"}, {"submit", "other-channel", "sample.json"}, {"submit", "trust-../bad", "sample.json"},
		{"get", "trust", "org"}, {"get-signer", "trust", "org", "WMS"},
		{"history", "trust", "org", "id", "extra"}, {"submit", "trust-dev", ""},
		{}, {"unknown", "trust", "id"},
	} {
		if validateArgs(args) == nil {
			t.Errorf("unsafe arguments accepted: %v", args)
		}
	}
	for _, args := range [][]string{
		{"get", "trust", "org", "id"}, {"history", "trust", "org", "root"},
		{"get-signer", "trust", "org", "WMS", "fingerprint"},
		{"submit", "trust-iam-dev", "sample.json"}, {"set-signer", "trust-iam-test", "policy.json"},
	} {
		if err := validateArgs(args); err != nil {
			t.Errorf("valid arguments rejected: %v: %v", args, err)
		}
	}
}
