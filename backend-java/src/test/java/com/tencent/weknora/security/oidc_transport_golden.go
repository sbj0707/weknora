//go:build ignore

// Production OIDC service transport, real HTTP redirects/cancellation, no provider mock client.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	log "github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/types"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"time"
)

func main() {
	log.SetOutput(io.Discard)
	os.Setenv("JWT_SECRET", "oidc-migration-state-secret")
	os.Setenv("SSRF_WHITELIST", "127.0.0.1")
	os.Setenv("SSRF_DNS_WHITELIST_ONLY", "true")
	cases := []string{"token-same-302", "token-same-303", "token-same-307", "token-same-308", "token-cross-302", "token-cross-303", "token-cross-307", "token-cross-308", "userinfo-cross-302", "userinfo-cross-307", "userinfo-same-302", "discovery-cross-302", "discovery-private-302", "token-redirect-loop", "token-cancel-before", "token-cancel-during", "token-deadline", "userinfo-cancel-during"}
	rows := []map[string]any{}
	for _, name := range cases {
		var lock sync.Mutex
		seen := []map[string]any{}
		started := make(chan struct{}, 1)
		ctx, cancel := context.WithCancel(context.Background())
		if name == "token-deadline" {
			ctx, cancel = context.WithTimeout(context.Background(), 100*time.Millisecond)
		}
		if name == "token-cancel-before" {
			cancel()
		}
		var source, target *httptest.Server
		handler := func(origin string) http.HandlerFunc {
			return func(w http.ResponseWriter, r *http.Request) {
				body, _ := io.ReadAll(r.Body)
				lock.Lock()
				seen = append(seen, map[string]any{"origin": origin, "method": r.Method, "path": r.URL.Path, "body": string(body), "authorization": r.Header.Get("Authorization"), "accept": r.Header.Get("Accept"), "content_type": r.Header.Get("Content-Type")})
				lock.Unlock()
				path := r.URL.Path
				if (strings.HasPrefix(name, "token-") && path == "/token" || name == "userinfo-cancel-during" && path == "/userinfo") && (strings.Contains(name, "cancel-during") || name == "token-deadline") {
					select {
					case started <- struct{}{}:
					default:
					}
					<-r.Context().Done()
					return
				}
				redirect := strings.HasPrefix(name, "token-") && path == "/token" && (strings.Contains(name, "same-") || strings.Contains(name, "cross-") || name == "token-redirect-loop") || path == "/userinfo" && strings.HasPrefix(name, "userinfo-") && !strings.Contains(name, "cancel") || path == "/discovery" && strings.HasPrefix(name, "discovery-")
				if redirect {
					status := 302
					if strings.HasSuffix(name, "303") {
						status = 303
					}
					if strings.HasSuffix(name, "307") {
						status = 307
					}
					if strings.HasSuffix(name, "308") {
						status = 308
					}
					location := source.URL + path + "-final"
					if strings.Contains(name, "cross-") {
						location = target.URL + path + "-final"
					}
					if name == "discovery-private-302" {
						location = "http://169.254.169.254/private"
					}
					if name == "token-redirect-loop" {
						location = source.URL + "/token"
					}
					w.Header().Set("Location", location)
					w.WriteHeader(status)
					return
				}
				w.Header().Set("Content-Type", "application/json")
				if strings.HasPrefix(path, "/token") {
					io.WriteString(w, `{"access_token":"transport-access"}`)
				} else if strings.HasPrefix(path, "/userinfo") {
					io.WriteString(w, `{"name":"No Email"}`)
				} else {
					json.NewEncoder(w).Encode(map[string]any{"authorization_endpoint": source.URL + "/authorize", "token_endpoint": source.URL + "/token", "userinfo_endpoint": source.URL + "/userinfo", "issuer": "https://issuer.example.test"})
				}
			}
		}
		source = httptest.NewServer(handler("source"))
		target = httptest.NewServer(handler("target"))
		cfg := &config.Config{OIDCAuth: &config.OIDCAuthConfig{Enable: true, ClientID: "client", ClientSecret: "SECRET_CLIENT", AuthorizationEndpoint: source.URL + "/authorize", TokenEndpoint: source.URL + "/token", UserInfoEndpoint: source.URL + "/userinfo", IssuerURL: "https://issuer.example.test"}}
		if strings.HasPrefix(name, "discovery-") {
			cfg.OIDCAuth.DiscoveryURL = source.URL + "/discovery"
			cfg.OIDCAuth.AuthorizationEndpoint = ""
			cfg.OIDCAuth.TokenEndpoint = ""
		}
		users := service.NewUserService(cfg, nil, nil, nil, nil, nil)
		if strings.Contains(name, "cancel-during") {
			go func() { <-started; cancel() }()
		}
		var err error
		success := false
		if strings.HasPrefix(name, "discovery-") {
			_, err = users.GetOIDCAuthorizationURL(ctx, "https://frontend.test/callback")
			success = err == nil
		} else {
			_, err = users.LoginWithOIDC(ctx, "code", "https://frontend.test/callback", types.TenantProvisioningTenantless)
		}
		cancel()
		source.Close()
		target.Close()
		raw := ""
		if err != nil {
			raw = err.Error()
		}
		lock.Lock()
		calls := seen
		lock.Unlock()
		rows = append(rows, map[string]any{"name": name, "success": success, "error": strings.ReplaceAll(strings.ReplaceAll(raw, source.URL, "<SOURCE>"), target.URL, "<TARGET>"), "calls": calls})
	}
	json.NewEncoder(os.Stdout).Encode(rows)
	fmt.Fprintln(os.Stderr, "actualGoOIDCTransportCases", len(rows))
}
