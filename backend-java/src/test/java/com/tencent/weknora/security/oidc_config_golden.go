//go:build ignore

// Executes production config.LoadConfig, including Viper binding and OIDC env/defaults.
package main

import (
	"encoding/json"
	"fmt"
	"github.com/Tencent/WeKnora/internal/config"
	log "github.com/Tencent/WeKnora/internal/logger"
	"github.com/spf13/viper"
	"io"
	"os"
	"path/filepath"
)

func main() {
	log.SetOutput(io.Discard)
	envKeys := []string{"OIDC_AUTH_ENABLE", "OIDC_AUTH_ISSUER_URL", "OIDC_AUTH_DISCOVERY_URL", "OIDC_AUTH_PROVIDER_DISPLAY_NAME", "OIDC_AUTH_CLIENT_ID", "OIDC_AUTH_CLIENT_SECRET", "OIDC_AUTH_AUTHORIZATION_ENDPOINT", "OIDC_AUTH_TOKEN_ENDPOINT", "OIDC_AUTH_USER_INFO_ENDPOINT", "OIDC_AUTH_JWKS_URI", "OIDC_AUTH_SCOPES", "OIDC_USER_INFO_MAPPING_USER_NAME", "OIDC_USER_INFO_MAPPING_EMAIL"}
	for _, k := range envKeys {
		os.Unsetenv(k)
	}
	folder, e := os.MkdirTemp("", "weknora-oidc-loadconfig-")
	must(e)
	defer os.RemoveAll(folder)
	original, e := os.Getwd()
	must(e)
	must(os.Chdir(folder))
	defer os.Chdir(original)
	rows := []map[string]any{}
	inputs := []map[string]any{{"name": "nil-config", "oidc": nil}, {"name": "empty-config", "oidc": map[string]any{}}, {"name": "disabled-named", "oidc": map[string]any{"enable": false, "provider_display_name": "  Provider \u3000 "}}, {"name": "enabled-incomplete", "oidc": map[string]any{"enable": true}}, {"name": "enabled-explicit", "oidc": complete()}, {"name": "issuer-autodiscovery", "oidc": map[string]any{"enable": true, "issuer_url": "https://issuer.test///", "client_id": "client", "client_secret": "secret"}}, {"name": "spaces-config", "oidc": map[string]any{"enable": true, "provider_display_name": "   ", "scopes": []string{" "}, "user_info_mapping": map[string]any{"username": " ", "email": " "}, "authorization_endpoint": " ", "token_endpoint": " ", "client_id": " ", "client_secret": " "}}, {"name": "empty-config-mapping", "oidc": map[string]any{"scopes": []string{}, "user_info_mapping": map[string]any{}}}, {"name": "weak-strings", "oidc": map[string]any{"provider_display_name": 123, "client_id": true, "client_secret": 42, "scopes": []any{123, true, nil}, "user_info_mapping": map[string]any{"username": 123}}}, {"name": "weak-bool", "oidc": map[string]any{"enable": "false"}}, {"name": "invalid-enable", "oidc": map[string]any{"enable": "garbage"}}, {"name": "invalid-map", "oidc": map[string]any{"user_info_mapping": "garbage"}}, {"name": "scalar-scopes", "oidc": map[string]any{"scopes": "openid, email profile"}}, {"name": "unknown-fields", "oidc": map[string]any{"future": true}}, {"name": "root-scalar", "oidc": "scalar"}}
	for _, value := range []string{"true", "True", "TRUE", "false", "FALSE", "1", "0", "garbage", " ", "\u3000true\u3000"} {
		inputs = append(inputs, map[string]any{"name": "env-enable-" + value, "oidc": complete(), "env": map[string]string{"OIDC_AUTH_ENABLE": value}})
	}
	for _, key := range envKeys[1:] {
		inputs = append(inputs, map[string]any{"name": "env-" + key, "oidc": complete(), "env": map[string]string{key: " \u3000env value\u3000 "}})
		inputs = append(inputs, map[string]any{"name": "blank-" + key, "oidc": complete(), "env": map[string]string{key: " \u3000 "}})
	}
	for _, value := range []string{"openid,profile email", " , ,\t ", "openid\u3000email\n profile"} {
		inputs = append(inputs, map[string]any{"name": "scopes-" + value, "oidc": complete(), "env": map[string]string{"OIDC_AUTH_SCOPES": value}})
	}
	output := os.Stdout
	discard, e := os.OpenFile(os.DevNull, os.O_WRONLY, 0)
	must(e)
	defer discard.Close()
	for _, input := range inputs {
		for _, key := range envKeys {
			os.Unsetenv(key)
		}
		environment := map[string]string{}
		if value, ok := input["env"].(map[string]string); ok {
			environment = value
		}
		for k, v := range environment {
			must(os.Setenv(k, v))
		}
		cfg := map[string]any{"oidc_auth": input["oidc"], "knowledge_base": map[string]any{"chunk_size": 512, "chunk_overlap": 50}}
		bytes, e := json.Marshal(cfg)
		must(e)
		must(os.WriteFile(filepath.Join(folder, "config.yaml"), bytes, 0600))
		viper.Reset()
		os.Stdout = discard
		result, err := config.LoadConfig()
		os.Stdout = output
		row := map[string]any{"name": input["name"], "input": cfg, "env": environment}
		if err != nil {
			row["error"] = err.Error()
		} else {
			encoded, e := json.Marshal(result.OIDCAuth)
			must(e)
			var body map[string]any
			must(json.Unmarshal(encoded, &body))
			body["client_secret_fixture"] = result.OIDCAuth.ClientSecret
			row["result"] = body
		}
		rows = append(rows, row)
	}
	must(json.NewEncoder(output).Encode(rows))
	fmt.Fprintln(os.Stderr, "actualGoLoadConfigCases", len(rows))
}
func complete() map[string]any {
	return map[string]any{"enable": true, "client_id": "yaml-client", "client_secret": "yaml-secret", "provider_display_name": " YAML Provider ", "authorization_endpoint": "https://provider.test/authorize", "token_endpoint": "https://provider.test/token", "scopes": []string{"yaml", "email"}, "user_info_mapping": map[string]any{"username": "yaml_name", "email": "yaml_email"}}
}
func must(e error) {
	if e != nil {
		panic(e)
	}
}
