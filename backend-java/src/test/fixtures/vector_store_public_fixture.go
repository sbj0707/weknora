//go:build ignore

// Actual Go handler/service/repository, role/API policy, SQL and owned ES8.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	svc "github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/application/service/retriever"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/container"
	h "github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/gin-gonic/gin"
	"gorm.io/driver/postgres"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"time"
)

func must(e error) {
	if e != nil {
		panic(e)
	}
}
func schema(t reflect.Type) any {
	out := map[string]any{"kind": t.Kind().String(), "name": t.Name(), "type": t.String()}
	if t.Kind() == reflect.Struct {
		fields := []any{}
		for i := 0; i < t.NumField(); i++ {
			f := t.Field(i)
			name := strings.Split(f.Tag.Get("json"), ",")[0]
			fields = append(fields, map[string]any{"name": name, "go_name": f.Name, "schema": schema(f.Type), "required": f.Tag.Get("binding") == "required"})
		}
		out["fields"] = fields
	} else if t.Kind() == reflect.Int {
		out["bits"] = t.Bits()
	}
	return out
}

type scene struct {
	RenameCorrupt                                       bool
	Name, Method, Path, Body, Role, Env, Probe, Corrupt string
	Settings                                            map[string]string
	Tenant                                              uint64
	Machine, Full, Capability, Bound, FactoryFail       bool
}

func main() {
	logger.SetOutput(os.Stderr)
	gin.SetMode(gin.TestMode)
	time.Local = time.UTC
	address := os.Getenv("WEKNORA_ES_PARITY_URL")
	if !strings.HasPrefix(address, "http://127.0.0.1:") {
		panic("owned loopback ES required")
	}
	must(os.Setenv("SYSTEM_AES_KEY", "0123456789abcdef0123456789abcdef"))
	must(os.Setenv("SSRF_WHITELIST", "127.0.0.1"))
	utils.ResetSSRFWhitelistForTest()
	create := func(extra string) string {
		return `{"name":"Created","engine_type":"elasticsearch","connection_config":{"addr":"` + address + `","username":"fixture","password":"synthetic-secret","api_key":"synthetic-api"},"index_config":{"index_name":"weknora-crud-created"}` + extra + `}`
	}
	scenes := []scene{{Name: "list", Method: "GET", Path: ""}, {Name: "types", Method: "GET", Path: "/types"}, {Name: "get", Method: "GET", Path: "/seed"}, {Name: "missing", Method: "GET", Path: "/missing"}, {Name: "foreign", Method: "GET", Path: "/foreign"}, {Name: "deleted", Method: "GET", Path: "/deleted"}, {Name: "create", Method: "POST", Body: create("")}, {Name: "rename", Method: "PUT", Path: "/seed", Body: `{"name":"Renamed","engine_type":"qdrant","connection_config":{"password":"ignored"},"index_config":{"index_name":"ignored"}}`}, {Name: "delete", Method: "DELETE", Path: "/seed"}, {Name: "delete-bound", Method: "DELETE", Path: "/seed", Bound: true}, {Name: "update-missing-before-bind", Method: "PUT", Path: "/missing", Body: "{"}, {Name: "delete-foreign", Method: "DELETE", Path: "/foreign"}, {Name: "saved-test", Method: "POST", Path: "/seed/test"}, {Name: "test-missing", Method: "POST", Path: "/missing/test"}, {Name: "raw-test", Method: "POST", Path: "/test", Body: create("")}, {Name: "factory-failure-persists", Method: "POST", Body: create(""), FactoryFail: true}}
	for _, p := range []struct{ name, body string }{{"empty", ""}, {"incomplete", "{"}, {"null", "null"}, {"array", "[]"}, {"number", "1"}, {"empty-object", "{}"}, {"missing-name", `{"engine_type":"elasticsearch","connection_config":{}}`}, {"missing-engine", `{"name":"X","connection_config":{}}`}, {"empty-config", `{"name":"X","engine_type":"elasticsearch","connection_config":{}}`}, {"null-config", `{"name":"X","engine_type":"elasticsearch","connection_config":null}`}, {"config-type", `{"name":"X","engine_type":"elasticsearch","connection_config":1}`}, {"name-type", `{"name":1,"engine_type":"elasticsearch"}`}, {"config-number", `{"name":"X","engine_type":"elasticsearch","connection_config":{"port":1.0}}`}, {"unsupported", `{"name":"X","engine_type":"sqlite","connection_config":{}}`}, {"unknown", strings.Replace(create(""), `"name":"Created"`, `"name":"Created","unknown":1`, 1)}, {"trailing", create("") + `{}`}, {"duplicate-null", strings.Replace(create(""), `"name":"Created"`, `"name":"Created","name":null`, 1)}, {"duplicate-type", strings.Replace(create(""), `"name":"Created"`, `"name":1,"name":"Created"`, 1)}, {"casefold", strings.Replace(create(""), `"name"`, `"NAME"`, 1)}, {"invalid-index", strings.Replace(create(""), "weknora-crud-created", "1bad", 1)}, {"index-dot", strings.Replace(create(""), "weknora-crud-created", "some.index", 1)}, {"negative-shards", strings.Replace(create(""), `"index_name":"weknora-crud-created"`, `"number_of_shards":-1`, 1)}, {"duplicate-endpoint", strings.Replace(create(""), "weknora-crud-created", "weknora-crud-seed", 1)}, {"duplicate-name", strings.Replace(create(""), "Created", "Seed", 1)}} {
		scenes = append(scenes, scene{Name: "create-" + p.name, Method: "POST", Body: p.body})
	}
	for _, body := range []string{"", "null", "{}", `{"name":1}`, `{"name":""}`, `{"name":"  "}`, `{"name":"New","name":null}`, `{"name":"Other"}{}`} {
		scenes = append(scenes, scene{Name: fmt.Sprintf("rename-binder-%d", len(scenes)), Method: "PUT", Path: "/seed", Body: body})
	}
	for _, engine := range []string{"sqlite", "postgres", "unknown", "qdrant", "milvus", "weaviate", "doris", "tencent_vectordb", "opensearch"} {
		scenes = append(scenes, scene{Name: "raw-required-" + engine, Method: "POST", Path: "/test", Body: `{"engine_type":"` + engine + `","connection_config":{}}`})
	}
	for _, driver := range []string{"sqlite", "postgres", "elasticsearch_v8", "elasticsearch_v7", "qdrant", "milvus", "weaviate", "doris", "tencent_vectordb", "opensearch", " sqlite,unknown,postgres,sqlite ,"} {
		scenes = append(scenes, scene{Name: "env-" + driver, Method: "GET", Env: driver})
	}
	for _, method := range []string{"GET", "PUT", "DELETE", "POST"} {
		path := "/__env_sqlite__"
		if method == "POST" {
			path += "/test"
		}
		scenes = append(scenes, scene{Name: "env-readonly-" + method, Method: method, Path: path, Body: "{", Env: "sqlite"})
	}
	for _, role := range []string{"viewer", "contributor", "admin", "owner"} {
		for _, method := range []string{"GET", "POST"} {
			scenes = append(scenes, scene{Name: "role-" + role + "-" + method, Method: method, Role: role, Body: "{"})
		}
	}
	for _, cap := range []string{"none", "manage", "full"} {
		scenes = append(scenes, scene{Name: "api-" + cap, Method: "POST", Body: create(""), Machine: true, Capability: cap == "manage", Full: cap == "full", Role: "viewer"})
	}
	scenes = append(scenes, scene{Name: "tenant-zero", Method: "POST", Body: "{", Tenant: 99})

	for _, mode := range []string{"ok", "forbidden", "redirect", "invalid-json", "number-version", "trailing", "large", "no-version"} {
		for _, operation := range []string{"raw", "saved", "create"} {
			method, path, body := "POST", "/test", create("")
			if operation == "saved" {
				path = "/seed/test"
				body = ""
			}
			if operation == "create" {
				path = ""
			}
			scenes = append(scenes, scene{Name: "probe-" + operation + "-" + mode, Method: method, Path: path, Body: body, Probe: mode})
		}
	}
	for _, addr := range []string{"http://127.0.0.2:9200", "ftp://example.invalid", "http://[broken", "http://metadata.google.internal"} {
		for _, path := range []string{"", "/test"} {
			scenes = append(scenes, scene{Name: "ssrf-" + fmt.Sprint(len(scenes)), Method: "POST", Path: path, Body: strings.Replace(create(""), address, addr, 1)})
		}
	}
	settings := map[string]string{"ELASTICSEARCH_ADDR": address, "ELASTICSEARCH_USERNAME": "fixture", "ELASTICSEARCH_PASSWORD": "env-password", "ELASTICSEARCH_INDEX": "weknora-crud-created", "QDRANT_HOST": "qdrant.invalid", "QDRANT_API_KEY": "env-api", "MILVUS_ADDRESS": "milvus.invalid:19530", "MILVUS_USERNAME": "fixture", "MILVUS_PASSWORD": "env-password", "WEAVIATE_HOST": "weaviate.invalid:8080", "WEAVIATE_GRPC_ADDRESS": "weaviate.invalid:50051", "WEAVIATE_SCHEME": "https", "WEAVIATE_API_KEY": "env-api", "DORIS_ADDR": "doris.invalid:9030", "DORIS_HTTP_PORT": "+8030", "DORIS_DATABASE": "docs", "DORIS_USERNAME": "fixture", "DORIS_PASSWORD": "env-password", "DORIS_TABLE_PREFIX": "prefix", "TENCENT_VECTORDB_ADDR": "https://vector.invalid", "TENCENT_VECTORDB_USERNAME": "fixture", "TENCENT_VECTORDB_API_KEY": "env-api", "TENCENT_VECTORDB_DATABASE": "db", "TENCENT_VECTORDB_COLLECTION": "collection", "OPENSEARCH_ADDR": "https://opensearch.invalid:9200", "OPENSEARCH_USERNAME": "fixture", "OPENSEARCH_PASSWORD": "env-password", "OPENSEARCH_INDEX": "os-index", "OPENSEARCH_INSECURE_SKIP_VERIFY": "TRUE"}
	for _, driver := range []string{"elasticsearch_v8", "qdrant", "milvus", "weaviate", "doris", "tencent_vectordb", "opensearch", "\u00a0sqlite\u00a0,\u3000postgres\u3000"} {
		scenes = append(scenes, scene{Name: "env-rich-" + driver, Method: "GET", Env: driver, Settings: settings})
	}
	scenes = append(scenes, scene{Name: "env-duplicate", Method: "POST", Body: create(""), Env: "elasticsearch_v8", Settings: settings})
	for _, method := range []string{"GET", "PUT", "DELETE", "POST"} {
		path := "/seed"
		if method == "POST" {
			path += "/test"
		}
		scenes = append(scenes, scene{Name: "corrupt-secret-" + method, Method: method, Path: path, Body: "{", Corrupt: "enc:v1:AA"})
	}
	for _, field := range []string{"password", "api_key"} {
		value := "synthetic-secret"
		if field == "api_key" {
			value = "synthetic-api"
		}
		scenes = append(scenes, scene{Name: "create-cipher-prefix-" + field, Method: "POST", Body: strings.Replace(create(""), value, "enc:v1:AA", 1)})
	}
	scenes = append(scenes, scene{Name: "rename-refetch-corrupt", Method: "PUT", Path: "/seed", Body: `{"name":"Renamed"}`, RenameCorrupt: true})
	scenes = append(scenes, scene{Name: "create-duplicate-scan-error", Method: "POST", Body: create(""), Corrupt: "enc:v1:AA"})
	output := []any{}
	for _, s := range scenes {

		for key := range settings {
			must(os.Setenv(key, ""))
		}
		for key, value := range s.Settings {
			must(os.Setenv(key, value))
		}
		var probe *httptest.Server
		var probeMu sync.Mutex
		probeRequests := []any{}
		probeAddr := ""
		sceneAddress := address
		requestBody := s.Body
		if s.Probe != "" {
			probe = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				u, p, ok := r.BasicAuth()
				probeMu.Lock()
				probeRequests = append(probeRequests, map[string]any{"method": r.Method, "path": r.URL.Path, "has_basic": ok, "basic_correct": ok && u == "fixture" && p == "synthetic-secret"})
				probeMu.Unlock()
				w.Header().Set("X-Elastic-Product", "Elasticsearch")
				w.Header().Set("Content-Type", "application/json")
				if r.URL.Path != "/" {
					if r.Method != "HEAD" {
						fmt.Fprint(w, `{"weknora-crud-created":{"mappings":{"properties":{"chunk_id":{"type":"keyword"}}}}}`)
					}
					return
				}
				switch s.Probe {
				case "forbidden":
					w.WriteHeader(401)
				case "redirect":
					w.Header().Set("Location", "http://127.0.0.2:9200/")
					w.WriteHeader(302)
				case "invalid-json":
					fmt.Fprint(w, `not-json`)
				case "number-version":
					fmt.Fprint(w, `{"version":{"number":123}}`)
				case "trailing":
					fmt.Fprint(w, `{"version":{"number":"8.14.0"}}{}`)
				case "large":
					fmt.Fprint(w, strings.Repeat(" ", 5000)+`{"version":{"number":"8.14.0"}}`)
				case "no-version":
					fmt.Fprint(w, `{}`)
				default:
					fmt.Fprint(w, `{"version":{"number":"8.14.0"}}`)
				}
			}))
			probeAddr = probe.URL
			sceneAddress = probeAddr
			requestBody = strings.ReplaceAll(s.Body, address, probeAddr)
		}
		temp, e := os.MkdirTemp("", "weknora-go-vector-public-")
		must(e)
		now := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
		var dialect gorm.Dialector = sqlite.Open(filepath.Join(temp, "test.db"))
		pgDSN := os.Getenv("WEKNORA_VECTOR_PUBLIC_PG_DSN")
		if pgDSN != "" {
			if !strings.Contains(pgDSN, "/weknora_vector_public_parity?") {
				panic("owned PG database required")
			}
			dialect = postgres.Open(pgDSN)
		}
		db, e := gorm.Open(dialect, &gorm.Config{Logger: gl.Default.LogMode(gl.Silent), NowFunc: func() time.Time { return now }})
		must(e)
		file, e := os.ReadFile("migrations/sqlite/000000_init.up.sql")
		must(e)
		if pgDSN == "" {
			must(db.Exec(string(file)).Error)
		} else {
			must(db.Exec("TRUNCATE vector_stores,knowledge_bases,tenants RESTART IDENTITY CASCADE").Error)
		}
		must(db.Exec("INSERT INTO tenants(id,name,business) VALUES(1,'Owner','fixture'),(2,'Foreign','fixture')").Error)
		storeRepo := repo.NewVectorStoreRepository(db)
		for _, seed := range []struct {
			id, name string
			tenant   uint64
		}{{"seed", "Seed", 1}, {"foreign", "Foreign", 2}, {"deleted", "Deleted", 1}} {
			store := &types.VectorStore{ID: seed.id, TenantID: seed.tenant, Name: seed.name, EngineType: types.ElasticsearchRetrieverEngineType, ConnectionConfig: types.ConnectionConfig{Addr: sceneAddress, Password: "seed-password", APIKey: "seed-api", Version: "old"}, IndexConfig: types.IndexConfig{IndexName: "weknora-crud-seed"}}
			must(storeRepo.Create(context.Background(), store))
		}
		must(storeRepo.Delete(context.Background(), 1, "deleted"))
		if s.Corrupt != "" {
			raw, err := json.Marshal(types.ConnectionConfig{Addr: sceneAddress, Password: s.Corrupt})
			must(err)
			must(db.Exec("UPDATE vector_stores SET connection_config=? WHERE id='seed'", raw).Error)
		}
		if s.RenameCorrupt {
			raw, _ := json.Marshal(types.ConnectionConfig{Addr: sceneAddress, Password: "enc:v1:AA"})
			literal := strings.ReplaceAll(string(raw), "'", "''")
			if pgDSN == "" {
				must(db.Exec("CREATE TRIGGER public_rename_damage AFTER UPDATE OF name ON vector_stores WHEN NEW.id='seed' BEGIN UPDATE vector_stores SET connection_config=CAST('" + literal + "' AS BLOB) WHERE id='seed'; END").Error)
			} else {
				must(db.Exec("CREATE FUNCTION public_rename_damage() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN UPDATE vector_stores SET connection_config='" + literal + "'::jsonb WHERE id='seed'; RETURN NEW; END; $$").Error)
				must(db.Exec("CREATE TRIGGER public_rename_damage AFTER UPDATE OF name ON vector_stores FOR EACH ROW WHEN (NEW.id='seed') EXECUTE FUNCTION public_rename_damage()").Error)
			}
		}
		if s.Bound {
			must(db.Exec("INSERT INTO knowledge_bases(id,tenant_id,name,type,vector_store_id,embedding_model_id,summary_model_id) VALUES('kb',1,'Bound','document','seed','','')").Error)
		}
		enabled := true
		cfg := &config.Config{Tenant: &config.TenantConfig{EnableRBAC: &enabled}}
		factory := container.NewEngineFactory(db, cfg, nil)
		builds, registered, unregistered := 0, 0, 0
		captureFactory := func(ctx context.Context, store types.VectorStore) (interfaces.RetrieveEngineService, error) {
			builds++
			if s.FactoryFail {
				return nil, fmt.Errorf("controlled factory failure")
			}
			return factory(ctx, store)
		}
		registry := &captureRegistry{base: retriever.NewRetrieveEngineRegistry(storeRepo, captureFactory).(interfaces.StoreRegistry), registered: &registered, unregistered: &unregistered}
		must(os.Setenv("RETRIEVE_DRIVER", s.Env))
		service := svc.NewVectorStoreService(storeRepo, repo.NewKnowledgeBaseRepository(db), registry, captureFactory, db)
		handler := h.NewVectorStoreHandler(storeRepo, service)
		role := s.Role
		if role == "" {
			role = "admin"
		}
		tenant := uint64(1)
		if s.Tenant == 99 {
			tenant = 0
		}
		scope := types.TenantAPIKeyScope{FullAccess: s.Full}
		if s.Capability {
			scope.Capabilities = types.StringArray{string(types.APIKeyCapabilityManageVectorStores)}
		}
		router := gin.New()
		router.Use(middleware.ErrorHandler())
		router.Use(func(c *gin.Context) {
			ctx := context.WithValue(c.Request.Context(), types.TenantIDContextKey, tenant)
			ctx = context.WithValue(ctx, types.TenantRoleContextKey, types.TenantRole(role))
			ctx = context.WithValue(ctx, types.UserIDContextKey, "fixture")
			if s.Machine {
				ctx = types.WithTenantAPIKeyScope(ctx, scope)
			}
			c.Request = c.Request.WithContext(ctx)
			c.Set(types.TenantIDContextKey.String(), tenant)
		})
		gate := middleware.NewAPIKeyRouteAuthorizer()
		root := "/api/v1/vector-stores"
		routes := []struct {
			method, path string
			fn           gin.HandlerFunc
		}{{"GET", "", handler.ListStores}, {"GET", "/types", handler.ListStoreTypes}, {"POST", "/test", handler.TestStoreRaw}, {"POST", "", handler.CreateStore}, {"GET", "/:id", handler.GetStore}, {"PUT", "/:id", handler.UpdateStore}, {"DELETE", "/:id", handler.DeleteStore}, {"POST", "/:id/test", handler.TestStoreByID}}
		for _, route := range routes {
			gate.Register(route.method, root+route.path, middleware.APIKeyRoutePolicy{RequireFullAccess: true}.WithCapability(types.APIKeyCapabilityManageVectorStores))
			floor := types.TenantRoleAdmin
			if route.method == "GET" {
				floor = types.TenantRoleViewer
			}
			router.Handle(route.method, root+route.path, gate.Middleware(), middleware.RequireRole(floor, cfg), route.fn)
		}
		request := httptest.NewRequest(s.Method, root+s.Path, strings.NewReader(requestBody))
		request.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		storeReads := 0
		must(db.Callback().Query().After("gorm:query").Register("public_store_reads", func(tx *gorm.DB) {
			if tx.Statement.Table == "vector_stores" {
				storeReads++
			}
		}))
		router.ServeHTTP(response, request)
		must(db.Callback().Query().Remove("public_store_reads"))
		var body any
		dec := json.NewDecoder(strings.NewReader(response.Body.String()))
		dec.UseNumber()
		must(dec.Decode(&body))
		var rows []map[string]any
		must(db.Raw("SELECT * FROM vector_stores ORDER BY id").Scan(&rows).Error)
		// Keep actual persisted credentials for native replay; randomized ciphertext is checked semantically.
		output = append(output, map[string]any{"scene": s, "store_reads": storeReads, "status": response.Code, "body": body, "rows": rows, "builds": builds, "registered": registered, "unregistered": unregistered, "probe_address": probeAddr, "probe_requests": probeRequests})
		if s.RenameCorrupt {
			if pgDSN == "" {
				must(db.Exec("DROP TRIGGER public_rename_damage").Error)
			} else {
				must(db.Exec("DROP TRIGGER public_rename_damage ON vector_stores").Error)
				must(db.Exec("DROP FUNCTION public_rename_damage()").Error)
			}
		}
		if builds > 0 {
			req, _ := http.NewRequest("DELETE", address+"/weknora-crud-created", nil)
			res, err := http.DefaultClient.Do(req)
			if err == nil {
				res.Body.Close()
			}
		}
		if probe != nil {
			probe.Close()
		}
		sql, _ := db.DB()
		sql.Close()
		os.RemoveAll(temp)
	}
	out := map[string]any{"cases": output, "schema": map[string]any{"create": schema(reflect.TypeOf(h.CreateStoreRequest{})), "update": schema(reflect.TypeOf(h.UpdateStoreRequest{})), "test": schema(reflect.TypeOf(h.TestStoreRequest{}))}, "types": types.GetVectorStoreTypes()}
	data, e := json.Marshal(out)
	must(e)
	must(os.WriteFile(os.Args[1], append(data, '\n'), 0600))
}

type captureRegistry struct {
	base                     interfaces.StoreRegistry
	registered, unregistered *int
}

func (c *captureRegistry) RegisterWithStoreID(id string, s interfaces.RetrieveEngineService) {
	*c.registered++
	c.base.RegisterWithStoreID(id, s)
}
func (c *captureRegistry) GetByStoreID(id string) (interfaces.RetrieveEngineService, error) {
	return c.base.GetByStoreID(id)
}
func (c *captureRegistry) UnregisterByStoreID(id string) {
	*c.unregistered++
	c.base.UnregisterByStoreID(id)
}
