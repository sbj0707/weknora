package service

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"sort"
	"strings"
	"testing"

	"github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/hibiken/asynq"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	gl "gorm.io/gorm/logger"

	"github.com/stretchr/testify/require"
)

// Runs the production helpers against real HTTP. An explicit output path also
// exports the same observations for the Java differential test.
func TestFileURLJavaDownloadOracle(t *testing.T) {
	t.Setenv("SSRF_WHITELIST_EXTRA", "127.0.0.1")
	type scenario struct {
		Name        string `json:"name"`
		Path        string `json:"path"`
		Disposition string `json:"disposition"`
		Filename    string `json:"filename"`
		FileType    string `json:"file_type"`
		Size        int    `json:"size"`
		Status      int    `json:"status"`
		Chunked     bool   `json:"chunked"`
		Redirect    bool   `json:"redirect"`
	}
	cases := []scenario{
		{Name: "url_name", Path: "/document.TXT"},
		{Name: "payload_name", Path: "/document.pdf", Disposition: `attachment; filename="header.csv"`, Filename: "payload.MD"},
		{Name: "payload_type", Path: "/document.pdf", FileType: ".XLSX"},
		{Name: "header_name", Path: "/document.pdf", Disposition: `attachment; filename="header.csv"`},
		{Name: "uppercase_header_key", Path: "/document.pdf", Disposition: `attachment; FILENAME='header.TXT'`},
		{Name: "filename_star_ignored", Path: "/fallback.txt", Disposition: `attachment; filename*=UTF-8''encoded.pdf`},
		{Name: "semicolon_split", Path: "/fallback.txt", Disposition: `attachment; filename="first;second.pdf"`},
		{Name: "empty_header_falls_back", Path: "/fallback.txt", Disposition: `attachment; filename=""`},
		{Name: "no_extension", Path: "/noextension"},
		{Name: "empty_extension", Path: "/name."},
		{Name: "decoded_url", Path: "/space%20name.%E4%B8%AD%E6%96%87"},
		{Name: "decoded_slash", Path: "/parent%2Fchild.txt"},
		{Name: "trailing_slash", Path: "/folder.txt/"},
		{Name: "root", Path: "/"},
		{Name: "redirect_original_path", Path: "/original.TXT", Redirect: true},
		{Name: "status_404", Path: "/not-found.txt", Status: 404},
		{Name: "status_204", Path: "/empty.txt", Status: 204},
		{Name: "at_limit", Path: "/limit.txt", Size: maxFileURLSize},
		{Name: "header_over_limit", Path: "/limit.txt", Size: maxFileURLSize + 1},
		{Name: "chunked_over_limit", Path: "/limit.txt", Size: maxFileURLSize + 1, Chunked: true},
	}
	var rows []map[string]any
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			size := c.Size
			if size == 0 {
				size = 23
			}
			data := bytes.Repeat([]byte("x"), size)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if c.Redirect && r.URL.Path != "/redirected.pdf" {
					http.Redirect(w, r, "/redirected.pdf", 302)
					return
				}
				if c.Disposition != "" {
					w.Header().Set("Content-Disposition", c.Disposition)
				}
				status := c.Status
				if status == 0 {
					status = 200
				}
				if !c.Chunked {
					w.Header().Set("Content-Length", fmt.Sprint(size))
				}
				w.WriteHeader(status)
				if c.Chunked {
					w.(http.Flusher).Flush()
				}
				_, _ = w.Write(data)
			}))
			defer server.Close()
			name, typ := c.Filename, c.FileType
			body, err := downloadFileFromURL(context.Background(), server.URL+c.Path, &name, &typ)
			message := ""
			if err != nil {
				message = err.Error()
			}
			if c.Status != 0 || c.Size > maxFileURLSize {
				require.Error(t, err)
			} else {
				require.NoError(t, err)
				require.Equal(t, data, body)
			}
			hash := sha256.Sum256(body)
			rows = append(rows, map[string]any{"input": c, "filename": name, "file_type": typ, "size": len(body), "sha256": hex.EncodeToString(hash[:]), "error": message})
		})
	}
	for _, header := range []string{"", `attachment; filename ="ignored.pdf"`, `attachment; filename="first.txt";filename="second.pdf"`, `attachment; filename="'nested.txt'"`, `attachment; filename= padded.txt `} {
		rows = append(rows, map[string]any{"header": header, "expected": extractFileNameFromContentDisposition(header)})
	}
	for _, path := range []string{"https://example.com/a/../b.TXT", "https://example.com/a/./", "https://example.com/a/..", "https://example.com", "https://example.com/a%2Fb.md?download=1", "https://example.com/%E4%B8%AD%E6%96%87.txt"} {
		rows = append(rows, map[string]any{"url": path, "expected": extractFileNameFromURL(path)})
	}
	if output := os.Getenv("WEKNORA_JAVA_FILEURL_FIXTURE"); strings.TrimSpace(output) != "" {
		raw, err := json.MarshalIndent(rows, "", "  ")
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(output, append(raw, '\n'), 0600))
	}
}

type remoteParityKB struct {
	interfaces.KnowledgeBaseService
	kb *types.KnowledgeBase
}

func (s *remoteParityKB) GetKnowledgeBaseByID(context.Context, string) (*types.KnowledgeBase, error) {
	return s.kb, nil
}
func (s *remoteParityKB) GetKnowledgeBaseByIDOnly(context.Context, string) (*types.KnowledgeBase, error) {
	return s.kb, nil
}

type remoteParityTenant struct{ interfaces.TenantRepository }

func (*remoteParityTenant) GetTenantByID(context.Context, uint64) (*types.Tenant, error) {
	return &types.Tenant{ID: 1}, nil
}
func (*remoteParityTenant) AdjustStorageUsed(context.Context, uint64, int64) error { return nil }

type remoteParityTenantService struct{ interfaces.TenantService }

func (*remoteParityTenantService) GetWeKnoraCloudCredentials(context.Context) *types.WeKnoraCloudCredentials {
	return nil
}

type remoteParityGraph struct {
	interfaces.RetrieveGraphRepository
}

func (*remoteParityGraph) DelGraph(context.Context, []types.NameSpace) error { return nil }

type remoteParityRegistry struct {
	interfaces.RetrieveEngineRegistry
}

func (*remoteParityRegistry) GetRetrieveEngineService(types.RetrieverEngineType) (interfaces.RetrieveEngineService, error) {
	return nil, errors.New("no configured engine")
}

type remoteParityFiles struct {
	interfaces.FileService
	data     []byte
	saved    []map[string]any
	opens    []string
	failSave bool
}

func (f *remoteParityFiles) SaveBytes(_ context.Context, b []byte, tenant uint64, name string, temp bool) (string, error) {
	f.saved = append(f.saved, map[string]any{"tenant": tenant, "filename": name, "temporary": temp, "content": string(b)})
	if f.failSave {
		return "", errors.New("controlled storage failure")
	}
	f.data = append([]byte{}, b...)
	return "downloaded", nil
}
func (f *remoteParityFiles) GetFile(_ context.Context, path string) (io.ReadCloser, error) {
	f.opens = append(f.opens, path)
	return io.NopCloser(bytes.NewReader(f.data)), nil
}

type remoteParityReader struct {
	interfaces.DocumentReader
	requests    []*types.ReadRequest
	parserError string
	title       string
	failure     bool
}

func (r *remoteParityReader) Read(_ context.Context, request *types.ReadRequest) (*types.ReadResult, error) {
	r.requests = append(r.requests, request)
	if r.failure {
		return nil, errors.New("controlled reader failure")
	}
	return &types.ReadResult{MarkdownContent: "remote parsed body", Metadata: map[string]string{"title": r.title}, Error: r.parserError}, nil
}

type remoteParityTasks struct{ tasks []*asynq.Task }

func (q *remoteParityTasks) Enqueue(task *asynq.Task, _ ...asynq.Option) (*asynq.TaskInfo, error) {
	q.tasks = append(q.tasks, task)
	return &asynq.TaskInfo{ID: fmt.Sprint(len(q.tasks))}, nil
}

func TestRemoteDocumentJavaWorkerOracle(t *testing.T) {
	t.Setenv("SSRF_WHITELIST_EXTRA", "127.0.0.1")
	type scenario struct {
		Name           string `json:"name"`
		Mode           string `json:"mode"`
		Title          string `json:"title"`
		Filename       string `json:"filename"`
		FileType       string `json:"file_type"`
		Status         int    `json:"status"`
		SaveFailure    bool   `json:"save_failure"`
		ParserError    string `json:"parser_error"`
		ReaderFailure  bool   `json:"reader_failure"`
		NoReader       bool   `json:"no_reader"`
		ExtractedTitle string `json:"extracted_title"`
	}
	cases := []scenario{
		{Name: "url_empty_title", Mode: "url", ExtractedTitle: "Extracted page title"},
		{Name: "url_source_title", Mode: "url", Title: "$ORIGIN/page", ExtractedTitle: "Extracted page title"},
		{Name: "url_custom_title", Mode: "url", Title: "User chosen title", ExtractedTitle: "Extracted page title"},
		{Name: "url_missing_extracted_title", Mode: "url", Title: "$ORIGIN/page"},
		{Name: "url_parser_error", Mode: "url", Title: "$ORIGIN/page", ParserError: "controlled parser error"},
		{Name: "url_transport_failure", Mode: "url", Title: "$ORIGIN/page", ReaderFailure: true},
		{Name: "url_no_reader", Mode: "url", Title: "$ORIGIN/page", NoReader: true},
		{Name: "file_url_discovered_metadata", Mode: "file_url"},
		{Name: "file_url_existing_metadata", Mode: "file_url", Filename: "task.TXT", FileType: "txt", Title: "Existing title"},
		{Name: "file_url_remote_reader", Mode: "file_url", Filename: "task.PDF", FileType: "pdf"},
		{Name: "file_url_unsupported", Mode: "file_url", Filename: "task.exe", FileType: "exe"},
		{Name: "file_url_http_failure", Mode: "file_url", Status: 404},
		{Name: "file_url_save_failure", Mode: "file_url", SaveFailure: true},
	}
	var out []map[string]any
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				status := c.Status
				if status == 0 {
					status = 200
				}
				w.Header().Set("Content-Disposition", `attachment; filename="discovered.TXT"`)
				w.WriteHeader(status)
				_, _ = w.Write([]byte("downloaded body"))
			}))
			defer server.Close()
			replace := func(s string) string { return strings.ReplaceAll(s, "$ORIGIN", server.URL) }
			db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{Logger: gl.Default.LogMode(gl.Silent)})
			require.NoError(t, err)
			sql, err := db.DB()
			require.NoError(t, err)
			defer sql.Close()
			sql.SetMaxOpenConns(1)
			require.NoError(t, db.AutoMigrate(&types.Knowledge{}, &types.Chunk{}))
			repo := repository.NewKnowledgeRepository(db)
			chunks := repository.NewChunkRepository(db)
			kb := &types.KnowledgeBase{ID: "kb", TenantID: 1, Type: "document", IndexingStrategy: types.IndexingStrategy{GraphEnabled: true}, ChunkingConfig: types.ChunkingConfig{ChunkSize: 64, ChunkOverlap: 10, Separators: []string{"\n\n", "\n", "。"}}}
			k := &types.Knowledge{ID: "knowledge", TenantID: 1, KnowledgeBaseID: "kb", Type: c.Mode, Source: server.URL + "/page", Title: replace(c.Title), FileName: c.Filename, FileType: c.FileType, ParseStatus: "pending", EnableStatus: "disabled"}
			summary := false
			require.NoError(t, k.SetProcessOverrides(&types.KnowledgeProcessOverrides{SummaryEnabled: &summary}))
			require.NoError(t, repo.CreateKnowledge(context.Background(), k))
			reader := &remoteParityReader{title: c.ExtractedTitle, parserError: c.ParserError, failure: c.ReaderFailure}
			var remote interfaces.DocumentReader = reader
			if c.NoReader {
				remote = nil
			}
			file := &remoteParityFiles{failSave: c.SaveFailure, saved: []map[string]any{}, opens: []string{}}
			queue := &remoteParityTasks{}
			svc, err := NewKnowledgeService(nil, repo, remote, &remoteParityKB{kb: kb}, &remoteParityTenant{}, &remoteParityTenantService{}, nil, chunks, nil, nil, file, nil, nil, nil, queue, nil, &remoteParityGraph{}, &remoteParityRegistry{}, nil, nil, nil, nil, nil, nil, nil, nil, nil)
			require.NoError(t, err)
			payload := types.DocumentProcessPayload{TenantID: 1, KnowledgeID: k.ID, KnowledgeBaseID: "kb", FileName: c.Filename, FileType: c.FileType, Language: "zh-CN", RequestId: "remote-request"}
			if c.Mode == "url" {
				payload.URL = server.URL + "/page"
			} else {
				payload.FileURL = server.URL + "/download"
			}
			raw, err := json.Marshal(payload)
			require.NoError(t, err)
			err = svc.ProcessDocument(context.Background(), asynq.NewTask(types.TypeDocumentProcess, raw))
			message := ""
			if err != nil {
				message = err.Error()
			}
			stored, e := repo.GetKnowledgeByID(context.Background(), 1, k.ID)
			require.NoError(t, e)
			var cs []*types.Chunk
			require.NoError(t, db.Where("knowledge_id=?", k.ID).Find(&cs).Error)
			sort.Slice(cs, func(i, j int) bool { return cs[i].ChunkIndex < cs[j].ChunkIndex })
			chunkRows := []map[string]any{}
			for _, chunk := range cs {
				chunkRows = append(chunkRows, map[string]any{"content": chunk.Content, "type": chunk.ChunkType, "index": chunk.ChunkIndex})
			}
			requests := []map[string]any{}
			for _, req := range reader.requests {
				requests = append(requests, map[string]any{"url": strings.ReplaceAll(req.URL, server.URL, "$ORIGIN"), "title": strings.ReplaceAll(req.Title, server.URL, "$ORIGIN"), "filename": req.FileName, "file_type": req.FileType, "content": string(req.FileContent), "engine": req.ParserEngine, "request_id": req.RequestID})
			}
			taskTypes := []string{}
			for _, task := range queue.tasks {
				taskTypes = append(taskTypes, task.Type())
			}
			out = append(out, map[string]any{"input": c, "row": map[string]any{"title": strings.ReplaceAll(stored.Title, server.URL, "$ORIGIN"), "file_name": stored.FileName, "file_type": stored.FileType, "file_path": stored.FilePath, "parse_status": stored.ParseStatus, "enable_status": stored.EnableStatus, "error": stored.ErrorMessage}, "chunks": chunkRows, "requests": requests, "saved": file.saved, "opened": file.opens, "tasks": taskTypes, "error": message})
		})
	}
	if output := os.Getenv("WEKNORA_JAVA_REMOTE_WORKER_FIXTURE"); output != "" {
		raw, err := json.MarshalIndent(out, "", "  ")
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(output, append(raw, '\n'), 0600))
	}
}
