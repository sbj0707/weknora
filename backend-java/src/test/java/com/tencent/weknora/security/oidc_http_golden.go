//go:build ignore

// Production Go OIDC handlers/services/repositories, production SQLite migrations,
// real local provider HTTP; no user-service or identity persistence mocks.
package main

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/handler"
	log "github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	utils "github.com/Tencent/WeKnora/internal/utils"
	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	glog "gorm.io/gorm/logger"
)

func main() {
	log.SetOutput(io.Discard)
	gin.SetMode(gin.TestMode)
	os.Setenv("JWT_SECRET", "oidc-migration-state-secret")
	os.Setenv("SSRF_WHITELIST", "127.0.0.1")
	os.Setenv("SSRF_WHITELIST_EXTRA", "")
	os.Setenv("SSRF_DNS_WHITELIST_ONLY", "true")
	os.Setenv("STORAGE_TYPE", "local")
	os.Setenv("LOCAL_STORAGE_BASE_DIR", "/tmp/oidc-fixture-storage")
	key, e := rsa.GenerateKey(rand.Reader, 2048)
	must(e)
	public := map[string]any{"kty": "RSA", "kid": "fixture-key", "use": "sig", "alg": "RS256", "n": base64.RawURLEncoding.EncodeToString(key.N.Bytes()), "e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.E)).Bytes())}
	cases := []string{"config-enabled", "config-disabled", "config-nil", "url-missing-redirect", "url-disabled", "url-success", "url-existing-query", "url-empty-scopes", "url-secure-xfp", "url-spaced-xfp", "url-tls", "url-no-discovery", "url-discovery", "url-discovery-override", "url-discovery-http-error", "url-discovery-malformed", "url-discovery-null", "url-discovery-incomplete", "url-token-insecure", "start-success", "start-secure-xfp", "start-tls", "start-disabled", "callback-provider-error", "callback-provider-error-description", "callback-state-missing", "callback-state-invalid", "callback-state-expired", "callback-state-future", "callback-cookie-missing", "callback-cookie-empty", "callback-cookie-mismatch", "callback-missing-code", "callback-disabled", "callback-token-401", "callback-token-null", "callback-token-missing", "callback-token-malformed", "callback-token-wrongtype", "callback-userinfo-401", "callback-userinfo-null", "callback-userinfo-empty", "callback-userinfo-noemail", "callback-userinfo-malformed", "callback-idtoken-nojwks-userinfo", "callback-idtoken-nojwks-noaccess", "callback-idtoken-nojwks-nouserinfo", "callback-idtoken-rs256", "callback-idtoken-rs384", "callback-idtoken-rs512", "callback-idtoken-expired", "callback-idtoken-leeway", "callback-idtoken-noexp", "callback-idtoken-future-nbf", "callback-idtoken-issuer", "callback-idtoken-audience", "callback-idtoken-nosub", "callback-idtoken-numeric-sub", "callback-idtoken-hs256", "callback-idtoken-kid-missing", "callback-jwks-401", "callback-jwks-empty", "callback-jwks-malformed", "callback-jwks-multiple-no-kid", "callback-jwks-single-no-kid", "callback-userinfo-override", "callback-userinfo-failure-idfallback", "callback-existing", "callback-disabled-user", "callback-tenantless-new", "callback-personal-new", "callback-registration-disabled", "callback-complex-password", "callback-custom-map", "callback-github-map", "callback-azure-map", "callback-unicode-username", "callback-username-collision", "callback-email-type-number", "callback-preference-failure", "callback-create-user-failure", "callback-owner-failure", "callback-owner-cleanup-failure", "callback-tenant-failure", "callback-storage-failure", "callback-access-token-failure", "callback-refresh-token-failure", "callback-email-lookup-failure", "callback-existing-tenantless-member", "callback-preferred-tenant", "callback-no-cookie-replay", "callback-captured-cookie-replay", "callback-storage-link-failure", "callback-storage-link-cleanup-failure", "callback-storage-link-tenant-cleanup-failure", "callback-inactive-tenant", "callback-blank-home-name", "callback-stale-home", "callback-stale-home-write-failure", "callback-first-home-write-failure", "callback-stale-preference", "callback-stale-preference-write-failure", "callback-missing-home-tenant", "callback-member-list-failure", "callback-userinfo-array", "callback-userinfo-string", "callback-userinfo-bool", "callback-userinfo-trailing", "callback-userinfo-duplicate-null", "callback-userinfo-numeric-large", "callback-userinfo-numeric-million", "callback-userinfo-numeric-small", "callback-userinfo-numeric-negative-zero", "callback-userinfo-object-claim", "callback-userinfo-list-claim", "callback-token-duplicate-null", "callback-token-wrong-then-correct", "callback-jwks-null", "callback-jwks-keys-null", "callback-jwks-duplicate-reuse", "callback-jwks-alg-ignored", "callback-jwks-use-enc", "callback-idtoken-exp-zero", "callback-idtoken-exp-string", "callback-idtoken-nbf-string", "callback-idtoken-aud-mixed", "callback-idtoken-issuer-number", "callback-prefs-malformed", "callback-prefs-trailing", "callback-prefs-bool-wrong", "callback-prefs-duplicate-wrong", "callback-prefs-casefold", "callback-prefs-map-null", "callback-prefs-gallery-duplicate", "callback-prefs-empty", "callback-prefs-null", "callback-prefs-gallery-wrong", "callback-prefs-uint-wrong", "callback-prefs-uint-fraction", "callback-prefs-root-array", "callback-prefs-empty-pointer", "callback-cookie-percent-encoded", "callback-cookie-invalid-escape", "callback-frontend-success", "callback-frontend-provider-error", "callback-frontend-disabled", "callback-username-turkish", "callback-empty-username-claim", "callback-empty-email-claim"}
	out := []map[string]any{}
	for _, name := range cases {
		now := time.Now()
		seen := []map[string]any{}
		providerResponses := map[string]any{}
		var provider *httptest.Server
		cfg := &config.Config{Auth: &config.AuthConfig{RegistrationMode: "self_serve", DefaultTenantMode: string(types.TenantProvisioningTenantless)}, OIDCAuth: &config.OIDCAuthConfig{Enable: true, ProviderDisplayName: " Fixture Provider ", ClientID: "fixture-client", ClientSecret: "fixture-client-secret", Scopes: []string{"openid", "profile", "email"}}}
		if strings.Contains(name, "frontend-") {
			cfg.FrontendBaseURL = "https://configured-frontend.example.test/prefix"
			if name == "callback-frontend-disabled" {
				cfg.OIDCAuth.Enable = false
			}
		}
		provider = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			body, _ := io.ReadAll(r.Body)
			row := map[string]any{"method": r.Method, "path": r.URL.RequestURI(), "body": string(body), "authorization": r.Header.Get("Authorization"), "accept": r.Header.Get("Accept"), "content_type": r.Header.Get("Content-Type")}
			seen = append(seen, row)
			status := 200
			var response any
			raw := ""
			switch r.URL.Path {
			case "/discovery":
				response = map[string]any{"authorization_endpoint": provider.URL + "/authorize", "token_endpoint": provider.URL + "/token", "userinfo_endpoint": provider.URL + "/userinfo", "jwks_uri": provider.URL + "/jwks", "issuer": "https://issuer.example.test"}
				switch name {
				case "url-discovery-http-error":
					status = 503
					raw = " discovery unavailable "
				case "url-discovery-malformed":
					raw = "{"
				case "url-discovery-null":
					raw = "null"
				case "url-discovery-incomplete":
					response = map[string]any{"issuer": "https://issuer.example.test"}
				}
			case "/token":
				response = map[string]any{"access_token": "provider-access", "token_type": "Bearer"}
				switch name {
				case "callback-token-401":
					status = 401
					raw = "NEVER_ECHO_CLIENT_SECRET"
				case "callback-token-null":
					raw = "null"
				case "callback-token-missing":
					response = map[string]any{}
				case "callback-token-malformed":
					raw = "{"
				case "callback-token-duplicate-null":
					raw = `{"access_token":"provider-access","access_token":null}`
				case "callback-token-wrong-then-correct":
					raw = `{"access_token":1,"access_token":"provider-access"}`
				case "callback-token-wrongtype":
					raw = `{"access_token":1}`
				}
				if strings.Contains(name, "idtoken") || strings.Contains(name, "jwks") || name == "callback-userinfo-override" || name == "callback-userinfo-failure-idfallback" {
					claims := jwt.MapClaims{"iss": "https://issuer.example.test", "aud": "fixture-client", "exp": now.Add(time.Hour).Unix(), "sub": "subject-fixture", "name": "Verified Name", "email": "verified@example.test"}
					method := jwt.SigningMethodRS256
					switch name {
					case "callback-idtoken-rs384":
						method = jwt.SigningMethodRS384
					case "callback-idtoken-rs512":
						method = jwt.SigningMethodRS512
					case "callback-idtoken-expired":
						claims["exp"] = now.Add(-3 * time.Minute).Unix()
					case "callback-idtoken-leeway":
						claims["exp"] = now.Add(-time.Minute).Unix()
					case "callback-idtoken-noexp":
						delete(claims, "exp")
					case "callback-idtoken-future-nbf":
						claims["nbf"] = now.Add(3 * time.Minute).Unix()
					case "callback-idtoken-issuer":
						claims["iss"] = "https://wrong-issuer.test"
					case "callback-idtoken-audience":
						claims["aud"] = "wrong-client"
					case "callback-idtoken-nosub":
						delete(claims, "sub")
					case "callback-idtoken-exp-zero":
						claims["exp"] = 0
					case "callback-idtoken-exp-string":
						claims["exp"] = "123"
					case "callback-idtoken-nbf-string":
						claims["nbf"] = "123"
					case "callback-idtoken-aud-mixed":
						claims["aud"] = []any{"fixture-client", 123}
					case "callback-idtoken-issuer-number":
						claims["iss"] = 123
					case "callback-idtoken-numeric-sub":
						claims["sub"] = 123
					}
					tok := jwt.NewWithClaims(method, claims)
					tok.Header["kid"] = "fixture-key"
					if name == "callback-idtoken-kid-missing" {
						tok.Header["kid"] = "other"
					}
					if strings.HasSuffix(name, "no-kid") {
						delete(tok.Header, "kid")
					}
					signed, se := tok.SignedString(key)
					must(se)
					if name == "callback-idtoken-hs256" {
						tok = jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
						signed, se = tok.SignedString([]byte("wrong-shared-secret"))
						must(se)
					}
					response = map[string]any{"access_token": "provider-access", "id_token": signed}
					if name == "callback-idtoken-nojwks-noaccess" {
						response = map[string]any{"id_token": signed}
					}
				}
			case "/jwks":
				response = map[string]any{"keys": []any{public}}
				switch name {
				case "callback-jwks-401":
					status = 401
					raw = "PRIVATE_KEY_NEVER_ECHO"
				case "callback-jwks-null":
					raw = "null"
				case "callback-jwks-keys-null":
					raw = `{"keys":null}`
				case "callback-jwks-duplicate-reuse":
					pub, e := json.Marshal(public)
					must(e)
					raw = `{"keys":[` + string(pub) + `],"keys":[{}]}`
				case "callback-jwks-alg-ignored", "callback-jwks-use-enc":
					copy := map[string]any{}
					for k, v := range public {
						copy[k] = v
					}
					copy["alg"] = "ES256"
					if name == "callback-jwks-use-enc" {
						copy["use"] = "enc"
					}
					response = map[string]any{"keys": []any{copy}}
				case "callback-jwks-empty":
					response = map[string]any{"keys": []any{}}
				case "callback-jwks-malformed":
					raw = "{"
				case "callback-jwks-multiple-no-kid":
					response = map[string]any{"keys": []any{public, public}}
				}
			case "/userinfo":
				response = map[string]any{"sub": "subject-userinfo", "name": " Provider User ", "email": "person@example.test"}
				switch name {
				case "callback-userinfo-array":
					raw = `[]`
				case "callback-userinfo-string":
					raw = `"invalid"`
				case "callback-userinfo-bool":
					raw = `true`
				case "callback-userinfo-trailing":
					raw = `{"name":"Provider User","email":"person@example.test"} BAD_TRAILING`
				case "callback-userinfo-duplicate-null":
					raw = `{"name":"Provider User","email":"person@example.test","email":null}`
				case "callback-userinfo-numeric-large":
					raw = `{"name":"Provider User","email":9007199254740993}`
				case "callback-userinfo-numeric-million":
					raw = `{"name":"Provider User","email":1000000}`
				case "callback-userinfo-numeric-small":
					raw = `{"name":"Provider User","email":0.00001}`
				case "callback-userinfo-numeric-negative-zero":
					raw = `{"name":"Provider User","email":-0}`
				case "callback-userinfo-object-claim":
					raw = `{"name":"Provider User","email":{"z":null,"a":true}}`
				case "callback-userinfo-list-claim":
					raw = `{"name":"Provider User","email":["mail",123,null]}`
				case "callback-userinfo-401", "callback-userinfo-failure-idfallback":
					status = 401
					raw = "PRIVATE_ACCESS_NEVER_ECHO"
				case "callback-userinfo-null":
					raw = "null"
				case "callback-userinfo-empty":
					response = map[string]any{}
				case "callback-userinfo-noemail":
					response = map[string]any{"sub": "subject", "name": "No email"}
				case "callback-userinfo-malformed":
					raw = "{"
				case "callback-userinfo-override":
					response = map[string]any{"name": "Override User", "email": "override@example.test"}
				case "callback-custom-map":
					response = map[string]any{"sub": "custom", "login": " Custom Login ", "mail": "person@example.test"}
				case "callback-github-map":
					response = map[string]any{"id": 12345, "login": "GitHub-User", "email": "person@example.test"}
				case "callback-azure-map":
					response = map[string]any{"oid": "azure-object", "preferred_username": " Azure User ", "mail": "person@example.test"}
				case "callback-username-turkish":
					response = map[string]any{"name": "AİB", "email": "person@example.test"}
				case "callback-empty-username-claim", "callback-empty-email-claim":
					response = map[string]any{"": "Must Not Be Used", "preferred_username": "Preferred", "name": "Name", "email": "person@example.test"}
				case "callback-unicode-username":
					response = map[string]any{"name": "中文😀._", "email": "Fallback.Name@example.test"}
				case "callback-email-type-number":
					response = map[string]any{"name": true, "email": 12345}
				}
			default:
				status = 404
				response = map[string]any{}
			}
			if raw == "" {
				b, err := json.Marshal(response)
				must(err)
				raw = string(b)
			}
			providerResponses[r.URL.Path] = map[string]any{"status": status, "body": raw}
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(status)
			io.WriteString(w, raw)
		}))
		cfg.OIDCAuth.AuthorizationEndpoint = provider.URL + "/authorize"
		cfg.OIDCAuth.TokenEndpoint = provider.URL + "/token"
		cfg.OIDCAuth.UserInfoEndpoint = provider.URL + "/userinfo"
		cfg.OIDCAuth.IssuerURL = "https://issuer.example.test"
		if strings.Contains(name, "idtoken") || strings.Contains(name, "jwks") || name == "callback-userinfo-override" || name == "callback-userinfo-failure-idfallback" {
			cfg.OIDCAuth.JwksURI = provider.URL + "/jwks"
		}
		if strings.Contains(name, "nojwks") {
			cfg.OIDCAuth.JwksURI = ""
		}
		if name == "callback-idtoken-nojwks-nouserinfo" {
			cfg.OIDCAuth.UserInfoEndpoint = ""
		}
		if name == "url-disabled" || name == "config-disabled" || name == "start-disabled" || name == "callback-disabled" {
			cfg.OIDCAuth.Enable = false
		}
		if name == "config-nil" {
			cfg.OIDCAuth = nil
		}
		if name == "url-existing-query" {
			cfg.OIDCAuth.AuthorizationEndpoint += "?existing=a%20b"
		}
		if name == "url-empty-scopes" {
			cfg.OIDCAuth.Scopes = []string{}
		}
		if strings.HasPrefix(name, "url-discovery") {
			cfg.OIDCAuth.DiscoveryURL = provider.URL + "/discovery"
			cfg.OIDCAuth.TokenEndpoint = ""
			cfg.OIDCAuth.UserInfoEndpoint = ""
			cfg.OIDCAuth.JwksURI = ""
			cfg.OIDCAuth.IssuerURL = ""
			if name != "url-discovery-override" {
				cfg.OIDCAuth.AuthorizationEndpoint = ""
			}
		}
		if name == "url-no-discovery" {
			cfg.OIDCAuth.AuthorizationEndpoint = ""
		}
		if name == "url-token-insecure" {
			cfg.OIDCAuth.TokenEndpoint = "http://169.254.169.254/token"
		}
		if name == "callback-custom-map" || name == "callback-github-map" {
			cfg.OIDCAuth.UserInfoMapping = &config.OIDCUserInfoMapping{Username: "login", Email: "mail"}
			if name == "callback-github-map" {
				cfg.OIDCAuth.UserInfoMapping.Email = "email"
			}
		}
		if name == "callback-empty-username-claim" {
			cfg.OIDCAuth.UserInfoMapping = &config.OIDCUserInfoMapping{Username: "", Email: "email"}
		}
		if name == "callback-empty-email-claim" {
			cfg.OIDCAuth.UserInfoMapping = &config.OIDCUserInfoMapping{Username: "name", Email: ""}
		}
		if name == "callback-azure-map" {
			cfg.OIDCAuth.UserInfoMapping = &config.OIDCUserInfoMapping{Username: "preferred_username", Email: "mail"}
		}
		if name == "callback-personal-new" || strings.Contains(name, "failure") && !strings.Contains(name, "userinfo") {
			cfg.Auth.DefaultTenantMode = string(types.TenantProvisioningCreatePersonal)
		}
		if name == "callback-registration-disabled" {
			cfg.Auth.RegistrationMode = "disabled"
		}
		if name == "callback-complex-password" {
			cfg.Auth.ComplexPasswordEnabled = true
		}
		db, e := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: glog.Default.LogMode(glog.Silent)})
		must(e)
		sqlDB, e := db.DB()
		must(e)
		sqlDB.SetMaxOpenConns(1)
		for _, migration := range []string{"000000_init", "000007_system_admin_and_settings", "000011_principal_model", "000004_memory"} {
			bytes, e := os.ReadFile("migrations/sqlite/" + migration + ".up.sql")
			must(e)
			must(db.Exec(string(bytes)).Error)
		}
		db.Callback().Create().After("gorm:create").Register("oidc-fixture-diagnostic", func(d *gorm.DB) {
			if d.Error != nil {
				fmt.Fprintln(os.Stderr, name, d.Statement.Table, d.Error)
			}
		})
		tenants := service.NewTenantService(repository.NewTenantRepository(db), repository.NewStorageBackendRepository(db))
		audit := service.NewAuditLogService(repository.NewAuditLogRepository(db))
		members := service.NewTenantMemberService(repository.NewTenantMemberRepository(db), audit, nil, nil)
		users := service.NewUserService(cfg, repository.NewUserRepository(db), repository.NewAuthTokenRepository(db), tenants, members, nil)
		if name == "callback-existing" || name == "callback-disabled-user" || name == "callback-existing-tenantless-member" || name == "callback-preferred-tenant" || strings.Contains(name, "home") || name == "callback-inactive-tenant" || strings.Contains(name, "stale-preference") || name == "callback-member-list-failure" || strings.HasPrefix(name, "callback-prefs-") {
			must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'Home','fixture'),(2,'Preferred','fixture')").Error)
			home := 1
			if name == "callback-existing-tenantless-member" || name == "callback-first-home-write-failure" {
				home = 0
			}
			must(db.Exec("INSERT INTO users(id,username,email,password_hash,tenant_id,is_active) VALUES('existing','Existing','person@example.test','unused',?,true)", home).Error)
			must(db.Exec("INSERT INTO tenant_members(user_id,tenant_id,role,status) VALUES('existing',1,'viewer','active'),('existing',2,'admin','active')").Error)
			if name == "callback-disabled-user" {
				must(db.Exec("UPDATE users SET is_active=false").Error)
			}
			if name == "callback-preferred-tenant" {
				must(db.Exec(`UPDATE users SET preferences='{"last_active_tenant_id":2}'`).Error)
			}
		}

		if name == "callback-inactive-tenant" {
			must(db.Exec("UPDATE tenants SET status='inactive' WHERE id=1").Error)
		}
		if name == "callback-blank-home-name" {
			must(db.Exec("UPDATE tenants SET name='   ' WHERE id=1").Error)
		}
		if strings.Contains(name, "stale-home") {
			must(db.Exec("UPDATE tenant_members SET deleted_at=CURRENT_TIMESTAMP WHERE tenant_id=1").Error)
			must(db.Exec(`UPDATE users SET preferences='{"last_active_tenant_id":1}'`).Error)
		}
		if strings.Contains(name, "stale-preference") {
			must(db.Exec(`UPDATE users SET preferences='{"last_active_tenant_id":99}'`).Error)
		}
		if name == "callback-missing-home-tenant" {
			must(db.Exec("UPDATE tenants SET deleted_at=CURRENT_TIMESTAMP WHERE id=1").Error)
		}
		if name == "callback-member-list-failure" {
			must(db.Exec("ALTER TABLE tenant_members RENAME TO tenant_members_missing").Error)
		}

		prefsRaw := map[string]string{"callback-prefs-malformed": "{", "callback-prefs-trailing": "{} {}", "callback-prefs-bool-wrong": "{\"oidc_only_login\":1}", "callback-prefs-duplicate-wrong": "{\"oidc_only_login\":1,\"oidc_only_login\":true}", "callback-prefs-casefold": "{\"OIDC_ONLY_LOGIN\":false,\"LAST_ACTIVE_TENANT_ID\":2}", "callback-prefs-map-null": "{\"gallery\":{\"status\":{\"a\":null,\"b\":\"on\"}}}", "callback-prefs-gallery-duplicate": "{\"gallery\":{\"mode\":\"custom\",\"status\":{\"a\":\"on\"}},\"gallery\":{\"status\":{\"b\":\"off\"}}}", "callback-prefs-empty": "", "callback-prefs-null": "null", "callback-prefs-gallery-wrong": "{\"gallery\":true}", "callback-prefs-uint-wrong": "{\"last_active_tenant_id\":-1}", "callback-prefs-uint-fraction": "{\"last_active_tenant_id\":1.0}", "callback-prefs-root-array": "[]", "callback-prefs-empty-pointer": "{\"browser_search_instructions\":\"\",\"oidc_only_login\":false,\"last_active_tenant_id\":0}"}
		if raw, ok := prefsRaw[name]; ok {
			must(db.Exec("UPDATE users SET preferences=?", raw).Error)
		}
		if name == "callback-username-collision" {
			must(db.Exec("INSERT INTO users(id,username,email,password_hash) VALUES('collision','provider-user','different@example.test','unused')").Error)
		}
		triggers := []string{}
		switch name {
		case "callback-preference-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_preferences BEFORE UPDATE ON users WHEN NEW.preferences LIKE '%oidc_only_login%' BEGIN SELECT RAISE(ABORT,'oidc preferences failure'); END")
		case "callback-create-user-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_user BEFORE INSERT ON users BEGIN SELECT RAISE(ABORT,'oidc user failure'); END")
		case "callback-owner-failure", "callback-owner-cleanup-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_owner BEFORE INSERT ON tenant_members BEGIN SELECT RAISE(ABORT,'oidc owner failure'); END")
			if name == "callback-owner-cleanup-failure" {
				triggers = append(triggers, "CREATE TRIGGER fail_user_cleanup BEFORE UPDATE ON users WHEN NEW.deleted_at IS NOT NULL BEGIN SELECT RAISE(ABORT,'oidc user cleanup failure'); END", "CREATE TRIGGER fail_tenant_cleanup BEFORE UPDATE ON tenants WHEN NEW.deleted_at IS NOT NULL BEGIN SELECT RAISE(ABORT,'oidc tenant cleanup failure'); END")
			}
		case "callback-tenant-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_tenant BEFORE INSERT ON tenants BEGIN SELECT RAISE(ABORT,'oidc tenant failure'); END")

		case "callback-stale-home-write-failure", "callback-first-home-write-failure", "callback-stale-preference-write-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_user_update BEFORE UPDATE ON users BEGIN SELECT RAISE(ABORT,'oidc user update failure'); END")
		case "callback-storage-link-failure", "callback-storage-link-cleanup-failure", "callback-storage-link-tenant-cleanup-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_storage_link BEFORE UPDATE ON tenants WHEN NEW.default_storage_backend_id IS NOT NULL BEGIN SELECT RAISE(ABORT,'oidc storage link failure'); END")
			if name == "callback-storage-link-cleanup-failure" {
				triggers = append(triggers, "CREATE TRIGGER fail_storage_cleanup BEFORE UPDATE ON storage_backends WHEN NEW.deleted_at IS NOT NULL BEGIN SELECT RAISE(ABORT,'oidc storage cleanup failure'); END")
			}
			if name == "callback-storage-link-tenant-cleanup-failure" {
				triggers = append(triggers, "CREATE TRIGGER fail_tenant_cleanup BEFORE UPDATE ON tenants WHEN NEW.deleted_at IS NOT NULL BEGIN SELECT RAISE(ABORT,'oidc tenant cleanup failure'); END")
			}
		case "callback-storage-failure":
			triggers = append(triggers, "CREATE TRIGGER fail_storage BEFORE INSERT ON storage_backends BEGIN SELECT RAISE(ABORT,'oidc storage failure'); END")
		case "callback-access-token-failure", "callback-refresh-token-failure":
			kind := "access_token"
			if strings.Contains(name, "refresh") {
				kind = "refresh_token"
			}
			triggers = append(triggers, fmt.Sprintf("CREATE TRIGGER fail_token BEFORE INSERT ON auth_tokens WHEN NEW.token_type='%s' BEGIN SELECT RAISE(ABORT,'oidc token failure'); END", kind))
		case "callback-email-lookup-failure":
			must(db.Exec("ALTER TABLE users RENAME TO users_missing").Error)
		}
		for _, statement := range triggers {
			must(db.Exec(statement).Error)
		}
		h := handler.NewAuthHandler(cfg, users, tenants, nil, nil)
		router := gin.New()
		router.Use(middleware.ErrorHandler())
		router.GET("/api/v1/auth/oidc/config", h.GetOIDCConfig)
		router.GET("/api/v1/auth/oidc/url", h.GetOIDCAuthorizationURL)
		router.GET("/api/v1/auth/oidc/start", h.OIDCStart)
		router.GET("/api/v1/auth/oidc/callback", h.OIDCRedirectCallback)
		endpoint := "callback"
		if strings.HasPrefix(name, "config-") {
			endpoint = "config"
		}
		if strings.HasPrefix(name, "url-") {
			endpoint = "url"
		}
		if strings.HasPrefix(name, "start-") {
			endpoint = "start"
		}
		query := url.Values{}
		if endpoint == "url" && name != "url-missing-redirect" {
			query.Set("redirect_uri", "  https://frontend.example.test/api/callback?x=1&y=two words  ")
		}
		nonce := "browser-fixture-nonce"
		cookie := nonce
		issued := now.Unix()
		if name == "callback-state-expired" {
			issued = now.Add(-11 * time.Minute).Unix()
		}
		if name == "callback-state-future" {
			issued = now.Add(2 * time.Minute).Unix()
		}
		state, e := utils.SignOIDCState(&utils.OIDCStatePayload{Nonce: nonce, RedirectURI: "https://frontend.example.test/callback", IssuedAt: issued})
		must(e)
		if endpoint == "callback" {
			query.Set("state", state)
			query.Set("code", " fixture-code ")
		}
		switch name {
		case "callback-frontend-provider-error":
			query.Set("error", "provider_error")
		case "callback-provider-error":
			query.Set("error", " access denied % #&+=? ")
		case "callback-provider-error-description":
			query.Set("error", "error")
			query.Set("error_description", " 提供方 reason % #&+=? ")
		case "callback-state-missing":
			query.Del("state")
		case "callback-state-invalid":
			query.Set("state", "bad-state")
		case "callback-cookie-missing":
			cookie = "<MISSING>"
		case "callback-cookie-empty":
			cookie = " "
		case "callback-cookie-percent-encoded":
			cookie = fmt.Sprintf("%%%02X%s", nonce[0], nonce[1:])
		case "callback-cookie-invalid-escape":
			cookie = "%GG"
		case "callback-cookie-mismatch":
			cookie = "another-browser-nonce"
		case "callback-missing-code":
			query.Set("code", " ")
		}
		request := httptest.NewRequest("GET", "http://backend.example.test/api/v1/auth/oidc/"+endpoint+"?"+query.Encode(), nil)
		if cookie != "<MISSING>" {
			request.AddCookie(&http.Cookie{Name: "weknora_oidc_nonce", Value: cookie})
		}
		if strings.Contains(name, "secure-xfp") {
			request.Header.Set("X-Forwarded-Proto", "HTTPS")
		}
		if name == "url-spaced-xfp" {
			request.Header.Set("X-Forwarded-Proto", " https ")
		}
		if strings.HasSuffix(name, "-tls") {
			request = httptest.NewRequest("GET", strings.Replace(request.URL.String(), "http:", "https:", 1), nil)
		}
		w := httptest.NewRecorder()
		replay := map[string]any{}
		if name == "callback-no-cookie-replay" || name == "callback-captured-cookie-replay" {
			first := httptest.NewRecorder()
			router.ServeHTTP(first, request)
			replay["first_status"] = first.Code
			replay["first_success"] = strings.Contains(first.Header().Get("Location"), "#oidc_result=")
			replay["first_set_cookie"] = first.Header().Values("Set-Cookie")
			var count int64
			must(db.Table("auth_tokens").Count(&count).Error)
			replay["tokens_before_second"] = count
			request = httptest.NewRequest("GET", request.URL.String(), nil)
			if name == "callback-captured-cookie-replay" {
				request.AddCookie(&http.Cookie{Name: "weknora_oidc_nonce", Value: nonce})
			}
		}
		router.ServeHTTP(w, request)
		result := map[string]any{"status": w.Code, "location": w.Header().Get("Location"), "set_cookie": w.Header().Values("Set-Cookie")}
		if endpoint == "config" || endpoint == "url" {
			var body any
			must(json.Unmarshal(w.Body.Bytes(), &body))
			if m, ok := body.(map[string]any); ok && m["state"] != nil {
				original := m["state"].(string)
				p, se := utils.VerifyOIDCState(original)
				must(se)
				m["state_payload"] = map[string]any{"nonce": "<NONCE>", "redirect_uri": p.RedirectURI, "iat_present": p.IssuedAt > 0}
				n := p.Nonce
				m["state"] = "<STATE>"
				m["authorization_url"] = normalizeAuthURL(m["authorization_url"].(string))
				result["nonce_length"] = len(n)
				cookies := result["set_cookie"].([]string)
				for i, c := range cookies {
					cookies[i] = strings.ReplaceAll(c, n, "<NONCE>")
				}
			}
			result["body"] = body
		} else if endpoint == "start" && w.Code == 302 {
			location := w.Header().Get("Location")
			u, e := url.Parse(location)
			must(e)
			p, se := utils.VerifyOIDCState(u.Query().Get("state"))
			must(se)
			result["state_payload"] = map[string]any{"nonce": "<NONCE>", "redirect_uri": p.RedirectURI, "iat_present": p.IssuedAt > 0}
			result["nonce_length"] = len(p.Nonce)
			result["location"] = normalizeAuthURL(location)
			cookies := result["set_cookie"].([]string)
			for i, c := range cookies {
				cookies[i] = strings.ReplaceAll(c, p.Nonce, "<NONCE>")
			}
		} else if strings.Contains(w.Header().Get("Location"), "#oidc_result=") {
			encoded := strings.SplitN(w.Header().Get("Location"), "#oidc_result=", 2)[1]
			bytes, e := base64.RawURLEncoding.DecodeString(encoded)
			must(e)
			var payload map[string]any
			must(json.Unmarshal(bytes, &payload))
			for _, kind := range []string{"token", "refresh_token"} {
				claims := jwt.MapClaims{}
				_, e := jwt.ParseWithClaims(payload[kind].(string), claims, func(t *jwt.Token) (any, error) { return []byte("oidc-migration-state-secret"), nil })
				must(e)
				delete(claims, "iat")
				delete(claims, "exp")
				delete(claims, "jti")
				payload[kind] = "<LOCAL_TOKEN>"
				payload[kind+"_claims"] = claims
			}
			result["payload"] = payload
			result["location"] = "/#oidc_result=<PAYLOAD>"
		}
		if endpoint == "start" && w.Code != 302 {
			var body any
			must(json.Unmarshal(w.Body.Bytes(), &body))
			result["body"] = body
		}
		snapshots := map[string]any{}
		for _, table := range []string{"users", "tenants", "tenant_members", "storage_backends", "auth_tokens"} {
			var rows []map[string]any
			selected := "*"
			switch table {
			case "users":
				selected = "id,username,email,tenant_id,is_active,preferences,deleted_at"
			case "tenants":
				selected = "id,name,status,default_storage_backend_id,deleted_at"
			case "tenant_members":
				selected = "user_id,tenant_id,role,status"
			case "storage_backends":
				selected = "tenant_id,provider,legacy_alias,deleted_at"
			case "auth_tokens":
				selected = "token_type,is_revoked"
			}
			err := db.Table(table).Select(selected).Find(&rows).Error
			if err != nil {
				snapshots[table] = map[string]any{"lookup_error": true}
			} else {
				if rows == nil {
					rows = []map[string]any{}
				}
				snapshots[table] = rows
			}
		}
		userIDs := map[string]bool{}
		var ids []string
		db.Table("users").Pluck("id", &ids)
		for _, id := range ids {
			if id != "existing" && id != "collision" {
				userIDs[id] = true
			}
		}
		row := map[string]any{"name": name, "clock": now.Unix(), "config": cfg, "endpoint": endpoint, "query": query, "cookie": cookie, "result": result, "provider_responses": providerResponses, "provider_calls": seen, "database": snapshots, "replay": replay}
		rawRow, e := json.Marshal(row)
		must(e)
		rawRow = []byte(strings.ReplaceAll(string(rawRow), provider.URL, "http://127.0.0.1:<PROVIDER_PORT>"))
		var generic map[string]any
		must(json.Unmarshal(rawRow, &generic))
		normalize(generic, "", userIDs)
		if cfg.OIDCAuth != nil {
			generic["client_secret_fixture"] = cfg.OIDCAuth.ClientSecret
		}
		out = append(out, generic)
		provider.Close()
		sqlDB.Close()
	}
	must(json.NewEncoder(os.Stdout).Encode(out))
}
func must(e error) {
	if e != nil {
		panic(e)
	}
}
func normalizeAuthURL(value string) string {
	u, e := url.Parse(value)
	must(e)
	state := u.Query().Get("state")
	return strings.Replace(value, state, url.QueryEscape("<STATE>"), 1)
}

func normalize(value any, base string, ids map[string]bool) {
	switch v := value.(type) {
	case map[string]any:
		for k, item := range v {
			if s, ok := item.(string); ok {
				if ids[s] {
					v[k] = "<USER>"
				} else if k == "created_at" || k == "updated_at" || k == "expires_at" || k == "deleted_at" {
					v[k] = "<TIME>"
				} else if k == "default_storage_backend_id" || k == "jti" {
					v[k] = "<RANDOM_ID>"
				} else {
					if base != "" {
						v[k] = strings.ReplaceAll(s, base, "http://127.0.0.1:<PROVIDER_PORT>")
					}
				}
			} else {
				normalize(item, base, ids)
			}
		}
	case []any:
		for _, item := range v {
			normalize(item, base, ids)
		}
	case []map[string]any:
		for _, item := range v {
			normalize(item, base, ids)
		}
	}
}
