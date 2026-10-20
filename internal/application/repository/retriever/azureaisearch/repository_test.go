package azureaisearch

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"regexp"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/policy"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const testStoreID = "0193b8a0-1111-7000-8000-000000000001"

// fakeAzure emulates the service's public JSON contracts. It deliberately
// returns underfilled search pages and supports item-level indexing failures.
type fakeAzure struct {
	mu             sync.Mutex
	schema         any
	docs           map[string]action
	batches        [][]action
	queries        []map[string]any
	failItems      map[string]int
	maxAccepted    int
	pageSize       int
	creates        int
	createConflict bool
	tokenLag       int
	status         int
	headers        http.Header
}

func newFake(t *testing.T) (*Repository, *fakeAzure) {
	t.Helper()
	fake := &fakeAzure{docs: map[string]action{}, failItems: map[string]int{}}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) { fake.serve(t, w, req) }))
	t.Cleanup(server.Close)
	repo, err := NewRepository(&Client{endpoint: server.URL, apiKey: "test-key", http: server.Client()}, types.VectorStore{ID: testStoreID, TenantID: 42, IndexConfig: types.IndexConfig{VectorDimensions: 3}})
	require.NoError(t, err)
	fake.schema = repo.schema()
	return repo, fake
}
func encode(w http.ResponseWriter, value any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}

func encodeAzureSchema(w http.ResponseWriter, value any) {
	if schema, ok := value.(indexSchema); ok {
		schema.Fields = append([]field(nil), schema.Fields...)
		for i := range schema.Fields {
			if schema.Fields[i].Stored == nil {
				schema.Fields[i].Stored = boolPointer(true)
			}
		}
		value = schema
	}
	encode(w, value)
}

func (f *fakeAzure) serve(t *testing.T, w http.ResponseWriter, req *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	assert.Equal(t, APIVersion, req.URL.Query().Get("api-version"))
	assert.Equal(t, "test-key", req.Header.Get("api-key"))
	if f.status > 0 {
		status := f.status
		f.status = 0
		for k, v := range f.headers {
			w.Header()[k] = v
		}
		w.WriteHeader(status)
		encode(w, map[string]any{"error": map[string]any{"code": "ServiceUnavailable", "message": "secret-content"}})
		return
	}
	if req.Method == http.MethodGet {
		if f.schema == nil {
			w.WriteHeader(404)
			encode(w, map[string]any{"error": map[string]any{"code": "", "message": "No index with the name '" + strings.TrimPrefix(req.URL.Path, "/indexes/") + "' was found in the service 'test'."}})
			return
		}
		encodeAzureSchema(w, f.schema)
		return
	}
	if req.Method == http.MethodPut {
		assert.Equal(t, "*", req.Header.Get("If-None-Match"))
		var schema indexSchema
		if err := json.NewDecoder(req.Body).Decode(&schema); err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		f.schema = schema
		f.creates++
		if f.createConflict {
			f.createConflict = false
			w.WriteHeader(http.StatusPreconditionFailed)
			encode(w, map[string]any{"error": map[string]any{"code": "PreconditionFailed"}})
			return
		}
		w.WriteHeader(201)
		encodeAzureSchema(w, schema)
		return
	}
	if strings.HasSuffix(req.URL.Path, "/docs/index") {
		var batch struct {
			Value []action `json:"value"`
		}
		if err := json.NewDecoder(req.Body).Decode(&batch); err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		f.batches = append(f.batches, batch.Value)
		if f.maxAccepted > 0 && len(batch.Value) > f.maxAccepted {
			w.WriteHeader(413)
			encode(w, map[string]any{"error": map[string]any{"code": "RequestEntityTooLarge"}})
			return
		}
		result := []map[string]any{}
		for _, a := range batch.Value {
			key, _ := a["id"].(string)
			code := 200
			if fail := f.failItems[key]; fail > 0 {
				code = fail
				delete(f.failItems, key)
			} else {
				switch a["@search.action"] {
				case "upload":
					f.docs[key] = a
				case "merge":
					if d := f.docs[key]; d != nil {
						for k, v := range a {
							d[k] = v
						}
					} else {
						code = 404
					}
				case "delete":
					delete(f.docs, key)
				default:
					t.Errorf("unknown indexing action: %v", a["@search.action"])
					code = 400
				}
			}
			result = append(result, map[string]any{"key": key, "status": code < 300, "statusCode": code})
		}
		encode(w, map[string]any{"value": result})
		return
	}
	var query map[string]any
	if err := json.NewDecoder(req.Body).Decode(&query); err != nil {
		t.Error(err)
		w.WriteHeader(400)
		return
	}
	f.queries = append(f.queries, query)
	filter, _ := query["filter"].(string)
	if strings.Contains(filter, "write_token eq") && f.tokenLag > 0 {
		f.tokenLag--
		encode(w, map[string]any{"value": []any{}})
		return
	}
	ids := []string{}
	matches := testMatcher(filter)
	for id, d := range f.docs {
		if matches(d) {
			ids = append(ids, id)
		}
	}
	sort.Strings(ids)
	top := int(query["top"].(float64))
	if f.pageSize > 0 {
		top = min(top, f.pageSize)
	}
	ids = ids[:min(top, len(ids))]
	docs := []action{}
	for _, id := range ids {
		docs = append(docs, f.docs[id])
	}
	encode(w, map[string]any{"value": docs})
}

var testEqual = regexp.MustCompile(`([a-z_]+) eq '((?:[^']|'')*)'`)
var testMember = regexp.MustCompile(`search.in\(([a-z_]+), '((?:[^']|'')*)', '([^']*)'\)`)
var testAfter = regexp.MustCompile(`id gt '([^']+)'`)

func testMatcher(filter string) func(action) bool {
	equals := testEqual.FindAllStringSubmatch(filter, -1)
	members := testMember.FindAllStringSubmatch(filter, -1)
	sets := map[string]map[string]bool{}
	for _, m := range members {
		set := map[string]bool{}
		for _, v := range strings.Split(strings.ReplaceAll(m[2], "''", "'"), m[3]) {
			set[v] = true
		}
		sets[m[1]] = set
	}
	cursor := testAfter.FindStringSubmatch(filter)
	return func(d action) bool {
		for _, m := range equals {
			if d[m[1]] != strings.ReplaceAll(m[2], "''", "'") {
				return false
			}
		}
		for field, set := range sets {
			if !set[fmt.Sprint(d[field])] {
				return false
			}
		}
		if cursor != nil && fmt.Sprint(d["id"]) <= cursor[1] {
			return false
		}
		return !strings.Contains(filter, "is_enabled eq true") || d["is_enabled"] == true
	}
}

func info(source, chunk string) *types.IndexInfo {
	return &types.IndexInfo{SourceID: source, ChunkID: chunk, KnowledgeID: "doc", KnowledgeBaseID: "kb", Content: "中文 Azure search", IsEnabled: true, TagID: "source-tag"}
}
func vectorParams(sources ...string) map[string]any {
	values := map[string][]float32{}
	for _, s := range sources {
		values[s] = []float32{1, 0, 0}
	}
	return map[string]any{"embedding": values}
}

func TestAzureUploadAliasesAndVisibility(t *testing.T) {
	r, f := newFake(t)
	f.schema = nil
	f.tokenLag = 1
	infos := []*types.IndexInfo{info("chunk", "chunk"), info("chunk-q1", "chunk"), info("chunk-q2", "chunk")}
	require.NoError(t, r.BatchSave(context.Background(), infos, vectorParams("chunk", "chunk-q1", "chunk-q2")))
	assert.Equal(t, 1, f.creates)
	require.Len(t, f.docs, 3)
	for _, d := range f.docs {
		assert.Equal(t, "42", d["tenant_id"])
		assert.Equal(t, testStoreID, d["store_id"])
		assert.Equal(t, "chunk", d["chunk_id"])
		assert.Len(t, d["embedding"], 3)
	}
	require.NoError(t, r.BatchSave(context.Background(), infos, vectorParams("chunk", "chunk-q1", "chunk-q2")))
	assert.Len(t, f.docs, 3, "stable keys must make reindexing idempotent")
	records, err := r.ListChunkIndexRecords(context.Background(), "kb", "doc", "chunk", types.VectorRetrieverType)
	require.NoError(t, err)
	assert.Len(t, records, 3)
	counts, err := r.CountKnowledgeIndices(context.Background(), "kb", "doc", types.VectorRetrieverType)
	require.NoError(t, err)
	assert.Equal(t, int64(1), counts.Chunks)
	assert.Equal(t, int64(2), counts.Questions)
}
func TestAzureMissingIndexReadDoesNotCreate(t *testing.T) {
	r, f := newFake(t)
	f.schema = nil
	result, err := r.Retrieve(context.Background(), types.RetrieveParams{RetrieverType: types.KeywordsRetrieverType, Query: "hello", TopK: 5, KnowledgeBaseIDs: []string{"kb"}})
	require.NoError(t, err)
	assert.Empty(t, result[0].Results)
	assert.Zero(t, f.creates)
	assert.Empty(t, f.batches)
}
func TestAzureMissingIndexWriteCreatesOwnedIndex(t *testing.T) {
	r, f := newFake(t)
	f.schema = nil
	require.NoError(t, r.Save(context.Background(), info("chunk", "chunk"), vectorParams("chunk")))
	assert.Equal(t, 1, f.creates)
	assert.Len(t, f.docs, 1)
}

func TestAzureBlankCodeMissingIndexResponses(t *testing.T) {
	for _, tc := range []struct {
		name, message string
		missing       bool
	}{
		{"observed Azure response", "No index with the name 'owned-index' was found in the service 'test'.", true},
		{"legacy response", "The index 'owned-index' for service 'test' was not found.", true},
		{"another index", "No index with the name 'other-index' was found in the service 'test'.", false},
		{"unknown route", "The requested resource was not found.", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				w.WriteHeader(http.StatusNotFound)
				encode(w, map[string]any{"error": map[string]any{"code": "", "message": tc.message}})
			}))
			defer server.Close()
			client := &Client{endpoint: server.URL, apiKey: "test-key", http: server.Client()}
			_, err := client.request(context.Background(), http.MethodGet, "/indexes/owned-index", nil, nil, nil)
			require.Error(t, err)
			assert.Equal(t, tc.missing, indexMissing(err))
			assert.NotContains(t, err.Error(), tc.message, "raw service messages must remain private")
		})
	}
}
func TestAzureSchemaDriftRejectedBeforeMutation(t *testing.T) {
	for _, change := range []string{"owner", "dimension", "analyzer", "stored", "metric", "similarity"} {
		t.Run(change, func(t *testing.T) {
			r, f := newFake(t)
			schema := r.schema()
			switch change {
			case "owner":
				schema.Description = "foreign"
			case "dimension":
				schema.Fields[len(schema.Fields)-1].Dimensions = 768
			case "stored":
				value := false
				schema.Fields[len(schema.Fields)-1].Stored = &value
			case "analyzer":
				schema.Fields[10].Analyzer = "zh-Hans.microsoft"
			case "metric":
				schema.VectorSearch.Algorithms[0].Parameters.Metric = "dotProduct"
			case "similarity":
				schema.Similarity.Type = "#Microsoft.Azure.Search.ClassicSimilarity"
			}
			f.schema = schema
			err := r.Save(context.Background(), info("chunk", "chunk"), vectorParams("chunk"))
			require.ErrorIs(t, err, ErrSchema)
			assert.Empty(t, f.batches)
		})
	}
}

// Azure materializes stored=true on scalar fields in its GET/PUT responses,
// even when the create request omitted stored. Exercise that response shape
// without weakening validation of an explicitly disabled stored field.
func TestAzureSchemaStoredDefaults(t *testing.T) {
	for _, tc := range []struct {
		name     string
		vector   bool
		stored   *bool
		accepted bool
	}{
		{"scalar omitted", false, nil, true},
		{"scalar Azure default", false, boolPointer(true), true},
		{"scalar disabled", false, boolPointer(false), false},
		{"vector omitted default", true, nil, true},
		{"vector explicit true", true, boolPointer(true), true},
		{"vector disabled", true, boolPointer(false), false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r, f := newFake(t)
			schema := r.schema()
			for i := range schema.Fields {
				schema.Fields[i].Stored = boolPointer(true)
			}
			index := 0
			if tc.vector {
				index = len(schema.Fields) - 1
			}
			schema.Fields[index].Stored = tc.stored
			f.schema = schema
			_, err := r.ensureReady(context.Background(), false)
			if tc.accepted {
				require.NoError(t, err)
			} else {
				require.ErrorIs(t, err, ErrSchema)
			}
			assert.Empty(t, f.batches)
			assert.Zero(t, f.creates)
		})
	}
}

func boolPointer(value bool) *bool { return &value }
func TestAzureWriteValidation(t *testing.T) {
	r, f := newFake(t)
	for _, vectors := range [][]float32{{1, 0}, {0, 0, 0}, {1, float32(math.Inf(1)), 0}} {
		require.Error(t, r.Save(context.Background(), info("chunk", "chunk"), map[string]any{"embedding": map[string][]float32{"chunk": vectors}}))
	}
	require.Error(t, r.BatchSave(context.Background(), []*types.IndexInfo{info("chunk", "chunk"), info("chunk", "chunk")}, vectorParams("chunk")))
	require.Error(t, r.BatchSave(context.Background(), []*types.IndexInfo{info("chunk", "chunk"), info("question", "chunk")}, vectorParams("chunk")))
	assert.Empty(t, f.batches)
	// Keyword-only records are valid and must not pretend to have vectors.
	require.NoError(t, r.Save(context.Background(), info("keyword", "keyword"), nil))
	for _, d := range f.docs {
		assert.NotContains(t, d, "embedding")
	}
}
func TestAzurePartialBatchRetriesOnlyFailedItems(t *testing.T) {
	r, f := newFake(t)
	f.failItems[r.documentID(0, "retry")] = 503
	require.NoError(t, r.BatchSave(context.Background(), []*types.IndexInfo{info("success", "success"), info("retry", "retry")}, nil))
	require.Len(t, f.batches, 2)
	assert.Len(t, f.batches[0], 2)
	require.Len(t, f.batches[1], 1)
	assert.Equal(t, r.documentID(0, "retry"), f.batches[1][0]["id"])
	f.failItems[r.documentID(0, "bad")] = 400
	err := r.BatchSave(context.Background(), []*types.IndexInfo{info("bad", "bad"), info("other", "other")}, nil)
	var batchErr *BatchError
	require.ErrorAs(t, err, &batchErr)
	assert.Equal(t, 400, batchErr.Items[0].Status)
	assert.NotContains(t, err.Error(), "中文")
}
func TestAzureBatchLimitsAnd413Split(t *testing.T) {
	t.Run("count", func(t *testing.T) {
		r, f := newFake(t)
		infos := []*types.IndexInfo{}
		for n := 0; n < 1001; n++ {
			id := fmt.Sprintf("s%d", n)
			infos = append(infos, info(id, id))
		}
		require.NoError(t, r.BatchSave(context.Background(), infos, nil))
		require.Len(t, f.batches, 2)
		assert.Len(t, f.batches[0], 1000)
		assert.Len(t, f.batches[1], 1)
	})
	t.Run("escaped-bytes", func(t *testing.T) {
		r, f := newFake(t)
		d := info("huge", "huge")
		d.Content = strings.Repeat("<", maxBatchBytes/5)
		require.Error(t, r.Save(context.Background(), d, nil))
		assert.Empty(t, f.batches)
	})
	t.Run("split", func(t *testing.T) {
		r, f := newFake(t)
		f.maxAccepted = 1
		require.NoError(t, r.BatchSave(context.Background(), []*types.IndexInfo{info("a", "a"), info("b", "b")}, nil))
		assert.Len(t, f.docs, 2)
		assert.Len(t, f.batches, 3)
	})
}
func TestAzureVectorScoresAndKeywordContract(t *testing.T) {
	r, f := newFake(t)
	require.NoError(t, r.BatchSave(context.Background(), []*types.IndexInfo{info("chunk", "chunk"), info("question", "chunk")}, vectorParams("chunk", "question")))
	f.docs[r.documentID(0, "chunk")]["@search.score"] = 1.0 / (2 - 0.8)
	f.docs[r.documentID(0, "question")]["@search.score"] = 1.0 / (2 - 0.7)
	p := types.RetrieveParams{RetrieverType: types.VectorRetrieverType, KnowledgeBaseIDs: []string{"kb"}, Embedding: []float32{1, 0, 0}, TopK: 10, Threshold: 0.75}
	results, err := r.Retrieve(context.Background(), p)
	require.NoError(t, err)
	require.Len(t, results[0].Results, 1)
	assert.InDelta(t, 0.8, results[0].Results[0].Score, 1e-10)
	assert.Equal(t, types.MatchTypeEmbedding, results[0].Results[0].MatchType)
	q := f.queries[len(f.queries)-1]
	assert.Equal(t, "preFilter", q["vectorFilterMode"])
	assert.NotContains(t, q, "search")
	assert.NotContains(t, q["select"], "embedding")
	assert.Contains(t, q["filter"], "knowledge_type eq 'document'")
	f.docs[r.documentID(0, "chunk")]["@search.score"] = 8.5
	p.RetrieverType = types.KeywordsRetrieverType
	p.Query = "中文 + Azure (test)"
	p.Threshold = 8
	results, err = r.Retrieve(context.Background(), p)
	require.NoError(t, err)
	require.Len(t, results[0].Results, 1)
	assert.Equal(t, 8.5, results[0].Results[0].Score)
	q = f.queries[len(f.queries)-1]
	assert.Equal(t, "simple", q["queryType"])
	assert.Contains(t, q["search"], `\+`)
	assert.NotContains(t, q, "vectorQueries")
}
func TestAzureFiltersEscapeAndFailClosed(t *testing.T) {
	r, f := newFake(t)
	p := types.RetrieveParams{RetrieverType: types.VectorRetrieverType, KnowledgeBaseIDs: []string{"kb' or true or '"}, KnowledgeIDs: []string{"doc"}, TagIDs: []string{"tag"}, ExcludeChunkIDs: []string{"excluded"}, ExcludeKnowledgeIDs: []string{"old"}, KnowledgeType: types.KnowledgeTypeFAQ, Embedding: []float32{1, 0, 0}, TopK: 10}
	filter := r.retrievalFilter(p)
	assert.Contains(t, filter, "kb'' or true or ''")
	assert.Contains(t, filter, "tenant_id eq '42'")
	assert.Contains(t, filter, "store_id eq '"+testStoreID+"'")
	assert.Contains(t, filter, "is_enabled eq true")
	assert.Contains(t, filter, "not (search.in(chunk_id")
	assert.Contains(t, filter, "not (search.in(knowledge_id")
	assert.Contains(t, filter, "knowledge_type eq 'faq'")
	p.KnowledgeBaseIDs = nil
	_, err := r.Retrieve(context.Background(), p)
	require.Error(t, err)
	assert.Empty(t, f.queries)
	p.KnowledgeBaseIDs = []string{"kb"}
	p.Embedding = []float32{1}
	_, err = r.Retrieve(context.Background(), p)
	require.ErrorIs(t, err, ErrDimension)
	p.Embedding = []float32{1, 0, 0}
	p.TopK = 1001
	_, err = r.Retrieve(context.Background(), p)
	require.Error(t, err)
}
func TestAzureLifecycleIncludesAliasesAndDisabledRecords(t *testing.T) {
	r, f := newFake(t)
	f.pageSize = 1
	require.NoError(t, r.BatchSave(context.Background(), []*types.IndexInfo{info("chunk", "chunk"), info("chunk-q", "chunk")}, vectorParams("chunk", "chunk-q")))
	require.NoError(t, r.BatchUpdateChunkEnabledStatus(context.Background(), map[string]bool{"chunk": false}))
	require.NoError(t, r.BatchUpdateChunkTagID(context.Background(), map[string]string{"chunk": ""}))
	for _, d := range f.docs {
		assert.Equal(t, false, d["is_enabled"])
		assert.Equal(t, "", d["tag_id"])
		assert.Len(t, d["embedding"], 3)
	}
	mapping := map[string]string{"chunk": "copy"}
	for n := 0; n < 2; n++ {
		require.NoError(t, r.CopyIndices(context.Background(), "kb", map[string]string{"doc": "copy-doc"}, mapping, "target", 3, "document"))
	}
	require.Len(t, f.docs, 4)
	for _, d := range f.docs {
		if d["knowledge_base_id"] == "target" {
			assert.Equal(t, "copy-doc", d["knowledge_id"])
			assert.Equal(t, "copy", d["chunk_id"])
			assert.Equal(t, "", d["tag_id"])
			assert.Len(t, d["embedding"], 3)
		}
	}
	for n := 0; n < 2; n++ {
		require.NoError(t, r.MoveKnowledgeIndices(context.Background(), "kb", "moved", "doc", []string{"chunk"}, 3, "document"))
	}
	for _, d := range f.docs {
		if d["knowledge_id"] == "doc" {
			assert.Equal(t, "moved", d["knowledge_base_id"])
			assert.Equal(t, "", d["tag_id"])
		}
	}
	require.NoError(t, r.DeleteBySourceIDList(context.Background(), []string{"copy-q"}, 3, "document"))
	assert.Len(t, f.docs, 3)
	require.NoError(t, r.DeleteByChunkIDList(context.Background(), []string{"chunk"}, 3, "document"))
	assert.Len(t, f.docs, 1)
	require.NoError(t, r.DeleteByKnowledgeIDList(context.Background(), []string{"copy-doc"}, 0, ""))
	assert.Empty(t, f.docs)
	require.ErrorIs(t, r.CopyIndices(context.Background(), "kb", nil, mapping, "target", 768, ""), ErrDimension)
	require.ErrorIs(t, r.MoveKnowledgeIndices(context.Background(), "kb", "target", "doc", []string{"chunk"}, 768, ""), ErrDimension)
}

func TestAzureHTTPRetryAndCancellation(t *testing.T) {
	r, f := newFake(t)
	f.status = 429
	require.NoError(t, r.client.TestConnection(context.Background()))
	f.status = 503
	f.headers = http.Header{"Retry-After": []string{"10"}}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	require.ErrorIs(t, r.client.TestConnection(ctx), context.DeadlineExceeded)
}

type tokenStub struct{ t *testing.T }

func (c tokenStub) GetToken(_ context.Context, opts policy.TokenRequestOptions) (azcore.AccessToken, error) {
	assert.Equal(c.t, []string{tokenScope}, opts.Scopes)
	return azcore.AccessToken{Token: "identity-token", ExpiresOn: time.Now().Add(time.Hour)}, nil
}
func TestAzureManagedIdentityAuth(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "Bearer identity-token", r.Header.Get("Authorization"))
		assert.Empty(t, r.Header.Get("api-key"))
		encode(w, map[string]any{"value": []any{}})
	}))
	defer ts.Close()
	client := &Client{endpoint: ts.URL, http: ts.Client(), credential: tokenStub{t}}
	require.NoError(t, client.TestConnection(context.Background()))
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestAzureProductionClientRejectsRedirects(t *testing.T) {
	utils.SetSSRFWhitelistFromRaw("test-search.search.windows.net")
	defer utils.ResetSSRFWhitelistForTest()
	c, err := NewClient(types.ConnectionConfig{Addr: "https://test-search.search.windows.net", APIKey: "test-key"})
	require.NoError(t, err)
	calls := 0
	c.http.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls++
		return &http.Response{StatusCode: 302, Header: http.Header{"Location": []string{"https://attacker.example/"}}, Body: io.NopCloser(strings.NewReader("")), Request: r}, nil
	})
	err = c.TestConnection(context.Background())
	require.Error(t, err)
	assert.Equal(t, 1, calls)
	var apiErr *HTTPError
	require.True(t, errors.As(err, &apiErr))
	assert.Equal(t, 302, apiErr.Status)
}

func TestAzureConditionalCreateAndReload(t *testing.T) {
	r, f := newFake(t)
	f.schema = nil
	f.createConflict = true
	require.NoError(t, r.Save(context.Background(), info("chunk", "chunk"), vectorParams("chunk")))
	assert.Equal(t, 1, f.creates)
	// A fresh repository must validate and reuse the existing owned schema.
	fresh, err := NewRepository(r.client, types.VectorStore{ID: testStoreID, TenantID: 42, IndexConfig: types.IndexConfig{VectorDimensions: 3}})
	require.NoError(t, err)
	exists, err := fresh.ensureReady(context.Background(), false)
	require.NoError(t, err)
	assert.True(t, exists)
	assert.Equal(t, 1, f.creates)
}

func TestAzureScanAndDeleteAcrossUnderfilledPages(t *testing.T) {
	r, f := newFake(t)
	f.pageSize = 37
	infos := make([]*types.IndexInfo, 205)
	for i := range infos {
		infos[i] = info(fmt.Sprintf("chunk-%03d", i), fmt.Sprintf("chunk-%03d", i))
		infos[i].IsEnabled = i%2 == 0
	}
	require.NoError(t, r.BatchSave(context.Background(), infos, nil))
	counts, err := r.CountKnowledgeIndices(context.Background(), "kb", "doc", types.KeywordsRetrieverType)
	require.NoError(t, err)
	assert.EqualValues(t, 205, counts.Chunks)
	require.NoError(t, r.DeleteByKnowledgeIDList(context.Background(), []string{"doc"}, 3, ""))
	assert.Empty(t, f.docs)
}
