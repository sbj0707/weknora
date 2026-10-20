package file

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/policy"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
)

type azureMemoryTransport struct {
	mu                sync.Mutex
	objects           map[string][]byte
	blocks            map[string][]byte
	failMethod        string
	failCode          string
	deletes           int
	requests          int
	transientFailures int
	lastDisposition   string
}

func (m *azureMemoryTransport) Do(req *http.Request) (*http.Response, error) {
	if err := req.Context().Err(); err != nil {
		return nil, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.requests++
	status := http.StatusOK
	data := []byte{}
	headers := http.Header{"Etag": []string{`"test"`}, "Content-Type": []string{"application/octet-stream"}}
	path := req.URL.Path
	code := ""
	if (req.Method == m.failMethod && req.URL.Query().Get("restype") != "container") || (m.failMethod == "container" && req.URL.Query().Get("restype") == "container") {
		code = m.failCode
	}
	if m.transientFailures > 0 {
		m.transientFailures--
		code = "ServerBusy"
	}
	switch {
	case code != "":
		status = 403
		if code == "ServerBusy" {
			status = 503
		}
	case req.URL.Query().Get("restype") == "container":
	case req.Method == http.MethodPut:
		raw, err := io.ReadAll(req.Body)
		if err != nil {
			return nil, err
		}
		switch req.URL.Query().Get("comp") {
		case "block":
			m.blocks[path+"?"+req.URL.Query().Get("blockid")] = raw
		case "blocklist":
			var list struct {
				Latest []string `xml:"Latest"`
			}
			if err := xml.Unmarshal(raw, &list); err != nil {
				return nil, err
			}
			var content []byte
			for _, id := range list.Latest {
				content = append(content, m.blocks[path+"?"+id]...)
			}
			m.objects[path] = content
		default:
			m.objects[path] = raw
		}
		for key, values := range req.Header {
			if strings.EqualFold(key, "x-ms-blob-content-disposition") && len(values) > 0 {
				m.lastDisposition = values[0]
			}
		}
		status = http.StatusCreated
	case req.Method == http.MethodGet:
		var ok bool
		data, ok = m.objects[path]
		if !ok {
			status = 404
			code = "BlobNotFound"
		}
	case req.Method == http.MethodDelete:
		m.deletes++
		if _, ok := m.objects[path]; !ok {
			status = 404
			code = "BlobNotFound"
		} else {
			delete(m.objects, path)
			status = http.StatusAccepted
		}
	default:
		return nil, fmt.Errorf("unexpected method %s", req.Method)
	}
	if code != "" {
		headers.Set("x-ms-error-code", code)
		headers.Set("Content-Type", "application/xml")
		data = []byte("<Error><Code>" + code + "</Code><Message>secret-server-details</Message></Error>")
	}
	headers.Set("Content-Length", fmt.Sprint(len(data)))
	return &http.Response{StatusCode: status, Header: headers, Body: io.NopCloser(bytes.NewReader(data)), ContentLength: int64(len(data)), Request: req}, nil
}
func newAzureMemoryService(t *testing.T) (*azureFileService, *azureMemoryTransport, context.Context) {
	t.Helper()
	transport := &azureMemoryTransport{objects: map[string][]byte{}, blocks: map[string][]byte{}}
	credential, err := azblob.NewSharedKeyCredential("account123", base64.StdEncoding.EncodeToString([]byte("secret")))
	if err != nil {
		t.Fatal(err)
	}
	client, err := azblob.NewClientWithSharedKeyCredential("https://account123.blob.core.windows.net", credential, &azblob.ClientOptions{ClientOptions: azcore.ClientOptions{Transport: transport, Retry: policy.RetryOptions{MaxRetries: -1}}})
	if err != nil {
		t.Fatal(err)
	}
	service := &azureFileService{client: client, config: types.AzureEngineConfig{ContainerName: "documents"}, prefix: "prod/"}
	return service, transport, context.WithValue(context.Background(), types.TenantIDContextKey, uint64(42))
}
func TestAzureBlobRoundTripCopyAndIdempotentDelete(t *testing.T) {
	service, transport, ctx := newAzureMemoryService(t)
	// Exercise multiple upload blocks and a non-ASCII name through the actual SDK pipeline.
	content := bytes.Repeat([]byte("document-page-image"), 500000)
	ref, err := service.SaveBytes(ctx, content, 42, "中文图片.png", true)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(ref, "azure://documents/prod/tenants/42/temporary/") {
		t.Fatal(ref)
	}
	if !strings.Contains(transport.lastDisposition, "filename*") {
		t.Fatal("UTF-8 filename metadata missing")
	}
	read, err := service.GetFile(ctx, ref)
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(read)
	read.Close()
	if err != nil || !bytes.Equal(got, content) {
		t.Fatal("uploaded bytes changed", err)
	}
	copied, err := service.CopyFile(ctx, ref, 42, "kb-copy")
	if err != nil {
		t.Fatal(err)
	}
	if copied == ref || !strings.Contains(copied, "/knowledge/kb-copy/") {
		t.Fatal("copy not independent", copied)
	}
	if err := service.DeleteFile(ctx, ref); err != nil {
		t.Fatal(err)
	}
	if err := service.DeleteFile(ctx, ref); err != nil {
		t.Fatal("delete must be idempotent", err)
	}
	read, err = service.GetFile(ctx, copied)
	if err != nil {
		t.Fatal("copy disappeared with source", err)
	}
	got, err = io.ReadAll(read)
	read.Close()
	if err != nil || !bytes.Equal(got, content) {
		t.Fatal("copy not complete before return", err)
	}
}
func TestAzureIsolationRejectsBeforeNetwork(t *testing.T) {
	service, transport, ctx := newAzureMemoryService(t)
	for _, ref := range []string{"azure://other/prod/tenants/42/assets/test.png", "azure://documents/other/tenants/42/assets/test.png", "azure://documents/prod/tenants/43/assets/test.png", "azure://documents/prod/tenants/42/../43/test.png", "https://attacker.invalid/file", "azure://documents/prod/tenants/42/assets/file?sig=secret"} {
		if _, err := service.GetFile(ctx, ref); err == nil {
			t.Errorf("accepted invalid path %s", ref)
		}
	}
	wrapped := NewBackendScopedFileService("backend-1", service)
	if _, err := wrapped.GetFile(ctx, "storage://backend-2/azure://documents/prod/tenants/42/assets/test.png"); err == nil {
		t.Fatal("cross-instance path accepted")
	}
	if transport.requests != 0 {
		t.Fatal("invalid paths caused network access")
	}
}
func TestAzureConnectivityCleansUpAndReportsFailures(t *testing.T) {
	service, transport, ctx := newAzureMemoryService(t)
	if err := service.CheckConnectivity(ctx); err != nil {
		t.Fatal(err)
	}
	if len(transport.objects) != 0 || transport.deletes != 1 {
		t.Fatal("test blob leaked")
	}
	for _, method := range []string{http.MethodGet, http.MethodDelete, "container", http.MethodPut} {
		t.Run(method, func(t *testing.T) {
			service, transport, ctx := newAzureMemoryService(t)
			transport.failMethod = method
			transport.failCode = "AuthorizationPermissionMismatch"
			err := service.CheckConnectivity(ctx)
			if err == nil {
				t.Fatal("failed test returned success")
			}
			safe := utils.SanitizeStorageConnectivityError(err)
			if strings.Contains(safe, "secret-server-details") || strings.Contains(safe, "account123.blob") {
				t.Fatal("SDK details leaked")
			}
			if method != "container" && method != http.MethodDelete && transport.deletes != 1 {
				t.Fatal("failed probe did not attempt cleanup")
			}
			if method == http.MethodDelete && !strings.Contains(safe, "cleanup") {
				t.Fatal("cleanup failure hidden")
			}
			stages := AzureConnectivityStages(err)
			expected := map[string]int{"container": 0, http.MethodPut: 1, http.MethodGet: 2, http.MethodDelete: 3}[method]
			if stages[expected].Status != "failed" {
				t.Fatal("failed stage not reported", stages)
			}
			if method != "container" && method != http.MethodDelete && stages[3].Status != "passed" {
				t.Fatal("successful cleanup not reported", stages)
			}

		})
	}
}
func TestAzureCancellationAndPrivateAccess(t *testing.T) {
	service, transport, ctx := newAzureMemoryService(t)
	canceled, cancel := context.WithCancel(ctx)
	cancel()
	if _, err := service.SaveBytes(canceled, []byte("x"), 42, "file.txt", false); err == nil {
		t.Fatal("cancelled upload succeeded")
	}
	if transport.requests != 0 {
		t.Fatal("cancelled operation reached transport")
	}
	t.Setenv("SYSTEM_AES_KEY", "0123456789abcdef0123456789abcdef")
	service.externalURL = "https://app.example.com"
	ref := "azure://documents/prod/tenants/42/assets/test.pdf"
	link, err := NewBackendScopedFileService("backend-1", service).GetFileURL(ctx, "storage://backend-1/"+ref)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(link, "https://app.example.com/api/v1/files/presigned?") || strings.Contains(link, "core.windows.net") {
		t.Fatal("private file bypassed app", link)
	}
	if _, err := service.GetFileURL(context.WithValue(ctx, types.TenantIDContextKey, uint64(43)), ref); err == nil {
		t.Fatal("cross tenant URL signed")
	}
	for _, code := range []string{"AuthenticationFailed", "ContainerNotFound", "AuthorizationPermissionMismatch"} {
		err := azureError("test", &azcore.ResponseError{StatusCode: 403, ErrorCode: code})
		var safe *AzureStorageError
		if !errors.As(err, &safe) || safe.Code == "network" {
			t.Fatal("missing error diagnosis", err)
		}
	}
}

func TestAzureResourceCatalogAndPrivateGrant(t *testing.T) {
	service, _, ctx := newAzureMemoryService(t)
	t.Setenv("APP_EXTERNAL_URL", "https://app.example.com")
	catalog := &catalogStub{}
	wrapped := NewResourceCatalogFileService(NewBackendScopedFileService("azure-instance", service), catalog)
	ref, err := wrapped.SaveBytes(ctx, []byte("image-bytes"), 42, "页面.jpg", false)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(ref, "resource://") || !strings.HasPrefix(catalog.resource.PhysicalPath, "storage://azure-instance/azure://documents/") {
		t.Fatal("resource identity or instance scope lost")
	}
	reader, err := wrapped.GetFile(ctx, ref)
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(reader)
	reader.Close()
	if err != nil || string(got) != "image-bytes" {
		t.Fatal("catalog did not resolve Azure bytes", err)
	}
	url, err := wrapped.GetFileURL(ctx, ref)
	if err != nil || !strings.HasPrefix(url, "https://app.example.com/r/") {
		t.Fatal("private resource grant missing", err)
	}
	if err := wrapped.DeleteFile(ctx, ref); err != nil {
		t.Fatal(err)
	}
}

func TestAzureMultipartOriginalUpload(t *testing.T) {
	service, transport, ctx := newAzureMemoryService(t)
	var requestBody bytes.Buffer
	writer := multipart.NewWriter(&requestBody)
	part, err := writer.CreateFormFile("file", "原文.pdf")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = part.Write([]byte("original-document"))
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	request, _ := http.NewRequest(http.MethodPost, "https://app.example.com/upload", &requestBody)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	if err := request.ParseMultipartForm(1024); err != nil {
		t.Fatal(err)
	}
	defer request.MultipartForm.RemoveAll()
	ref, err := service.SaveFile(ctx, request.MultipartForm.File["file"][0], 42, "kb-original")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(ref, "/tenants/42/knowledge/kb-original/") || !strings.HasSuffix(ref, ".pdf") {
		t.Fatal("wrong original location")
	}
	if !strings.Contains(transport.lastDisposition, "filename*") {
		t.Fatal("original filename missing")
	}
	reader, err := service.GetFile(ctx, ref)
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(reader)
	reader.Close()
	if err != nil || string(got) != "original-document" {
		t.Fatal("original upload changed bytes", err)
	}
}

func TestAzureTransientRetriesAreBounded(t *testing.T) {
	for _, failures := range []int{2, 5} {
		service, transport, ctx := newAzureMemoryService(t)
		transport.transientFailures = failures
		credential, _ := azblob.NewSharedKeyCredential("account123", base64.StdEncoding.EncodeToString([]byte("secret")))
		client, err := azblob.NewClientWithSharedKeyCredential("https://account123.blob.core.windows.net", credential, &azblob.ClientOptions{ClientOptions: azcore.ClientOptions{Transport: transport, Retry: policy.RetryOptions{MaxRetries: 2, RetryDelay: time.Millisecond, MaxRetryDelay: time.Millisecond}}})
		if err != nil {
			t.Fatal(err)
		}
		service.client = client
		_, err = service.SaveBytes(ctx, []byte("retry"), 42, "file.txt", false)
		if (err == nil) != (failures == 2) {
			t.Fatal("wrong retry result", err)
		}
		if transport.requests != 3 {
			t.Fatalf("retry attempts=%d; want 3", transport.requests)
		}
	}
}
