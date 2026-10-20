package file

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/policy"
	"github.com/Azure/azure-sdk-for-go/sdk/azidentity"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob"
	"github.com/Azure/azure-sdk-for-go/sdk/storage/azblob/blob"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/Tencent/WeKnora/internal/utils"
	"github.com/google/uuid"
)

type azureFileService struct {
	client      *azblob.Client
	config      types.AzureEngineConfig
	prefix      string
	externalURL string
}

// AzureStorageError exposes an operation and a safe diagnosis, never the SDK's
// request URL, response body, identity endpoint or authentication information.
type AzureStorageError struct {
	Operation string
	Code      string
	cause     error
}

func (e *AzureStorageError) Error() string {
	messages := map[string]string{
		"authentication":    "authentication failed; check the account key or managed identity",
		"permission":        "permission denied; check Blob data read/write/delete permissions",
		"container_missing": "Container does not exist",
		"object_missing":    "Blob does not exist",
		"identity":          "managed identity token acquisition failed; check the app hosting identity",
		"network":           "connection failed; check the Blob endpoint and network access",
		"timeout":           "operation timed out or was cancelled",
		"integrity":         "test object content did not match",
	}
	message := messages[e.Code]
	if message == "" {
		message = "storage operation failed"
	}
	return "Azure Blob " + e.Operation + ": " + message
}

func (e *AzureStorageError) SafeStorageMessage() string { return e.Error() }
func (e *AzureStorageError) Unwrap() error              { return e.cause }

func azureError(operation string, err error) error {
	if err == nil {
		return nil
	}
	code := "network"
	var response *azcore.ResponseError
	var identity *azidentity.AuthenticationFailedError
	switch {
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		code = "timeout"
	case errors.As(err, &identity), strings.Contains(err.Error(), "ManagedIdentityCredential"):
		code = "identity"
	case errors.As(err, &response):
		switch response.ErrorCode {
		case "AuthenticationFailed", "InvalidAuthenticationInfo", "KeyBasedAuthenticationNotPermitted":
			code = "authentication"
		case "ContainerNotFound":
			code = "container_missing"
		case "BlobNotFound":
			code = "object_missing"
		default:
			if response.StatusCode == http.StatusForbidden {
				code = "permission"
			} else if response.StatusCode == http.StatusUnauthorized {
				code = "authentication"
			}
		}
	}
	return &AzureStorageError{Operation: operation, Code: code, cause: err}
}

// NewAzureFileService is network-free apart from SSRF endpoint validation. It
// never creates containers. Token acquisition happens only on actual I/O.
func NewAzureFileService(config types.AzureEngineConfig) (interfaces.FileService, error) {
	c := config.Normalized()
	if err := c.Validate(); err != nil {
		return nil, err
	}
	if err := utils.ValidateURLForSSRF(c.Endpoint); err != nil {
		return nil, fmt.Errorf("unsafe Azure Blob endpoint")
	}
	options := &azblob.ClientOptions{ClientOptions: azcore.ClientOptions{
		Transport: objectStorageHTTPClient(),
		Retry:     policy.RetryOptions{MaxRetries: 2, RetryDelay: time.Second, MaxRetryDelay: 5 * time.Second},
	}}
	var client *azblob.Client
	var err error
	if c.AuthMode == types.AzureAuthAccountKey {
		credential, credentialErr := azblob.NewSharedKeyCredential(c.AccountName, c.AccountKey)
		if credentialErr != nil {
			return nil, fmt.Errorf("Azure account_key must be a valid Base64 storage account key")
		}
		client, err = azblob.NewClientWithSharedKeyCredential(c.Endpoint, credential, options)
	} else {
		identityOptions := &azidentity.ManagedIdentityCredentialOptions{}
		if c.ManagedIdentityClientID != "" {
			identityOptions.ID = azidentity.ClientID(c.ManagedIdentityClientID)
		}
		// The credential's SDK-owned metadata transport is intentionally separate
		// from user-configurable object endpoints. Never allow arbitrary Blob
		// requests through the metadata transport or a default credential chain.
		credential, credentialErr := azidentity.NewManagedIdentityCredential(identityOptions)
		if credentialErr != nil {
			return nil, &AzureStorageError{Operation: "identity", Code: "identity"}
		}
		client, err = azblob.NewClient(c.Endpoint, credential, options)
	}
	if err != nil {
		return nil, fmt.Errorf("invalid Azure Blob client configuration")
	}
	prefix := strings.Trim(c.PathPrefix, "/")
	if prefix != "" {
		prefix += "/"
	}
	return &azureFileService{client: client, config: c, prefix: prefix,
		externalURL: strings.TrimRight(strings.TrimSpace(os.Getenv("APP_EXTERNAL_URL")), "/")}, nil
}

func (s *azureFileService) reference(key string) string {
	return "azure://" + s.config.ContainerName + "/" + key
}

func (s *azureFileService) objectKey(ctx context.Context, reference string) (string, error) {
	reference = storageBackendInnerPath(reference)
	prefix := "azure://" + s.config.ContainerName + "/"
	if !strings.HasPrefix(reference, prefix) {
		return "", fmt.Errorf("Azure Blob container or provider mismatch")
	}
	key := strings.TrimPrefix(reference, prefix)
	if err := utils.SafeObjectKey(key); err != nil || path.Clean(key) != key || strings.ContainsAny(key, "\\\x00\r\n?#") || !strings.HasPrefix(key, s.prefix) {
		return "", fmt.Errorf("Azure Blob object path is outside the configured prefix")
	}
	// All generated objects have an unambiguous tenant segment after the
	// instance prefix, independent of numeric container or prefix components.
	relative := strings.TrimPrefix(key, s.prefix)
	parts := strings.Split(relative, "/")
	if len(parts) < 3 || parts[0] != "tenants" {
		return "", fmt.Errorf("invalid Azure Blob tenant path")
	}
	tenantID, err := strconv.ParseUint(parts[1], 10, 64)
	if err != nil || tenantID == 0 {
		return "", fmt.Errorf("invalid Azure Blob tenant path")
	}
	if currentTenant, ok := types.TenantIDFromContext(ctx); ok && currentTenant != tenantID {
		return "", fmt.Errorf("Azure Blob tenant mismatch")
	}
	return key, nil
}

func (s *azureFileService) destination(tenantID uint64, knowledgeID, filename string, temporary bool) (string, error) {
	if tenantID == 0 {
		return "", fmt.Errorf("tenant ID is required")
	}
	name, err := utils.SafeFileName(filename)
	if err != nil {
		return "", err
	}
	group := "assets"
	if knowledgeID != "" {
		if strings.ContainsAny(knowledgeID, "/\\?#\x00\r\n") || strings.Contains(knowledgeID, "..") {
			return "", fmt.Errorf("invalid knowledge ID")
		}
		group = "knowledge/" + knowledgeID
	}
	if temporary {
		group = "temporary"
	}
	return fmt.Sprintf("%stenants/%d/%s/%s%s", s.prefix, tenantID, group, uuid.NewString(), filepath.Ext(name)), nil
}

func (s *azureFileService) upload(ctx context.Context, key string, reader io.Reader, filename, contentType string) error {
	if contentType == "" {
		contentType = utils.GetContentTypeByExt(filepath.Ext(filename))
	}
	disposition := mime.FormatMediaType("inline", map[string]string{"filename": filepath.Base(filename)})
	_, err := s.client.UploadStream(ctx, s.config.ContainerName, key, reader, &azblob.UploadStreamOptions{
		BlockSize: 4 * 1024 * 1024, Concurrency: 2,
		HTTPHeaders: &blob.HTTPHeaders{BlobContentType: &contentType, BlobContentDisposition: &disposition},
	})
	return azureError("upload", err)
}

func (s *azureFileService) SaveFile(ctx context.Context, file *multipart.FileHeader, tenantID uint64, knowledgeID string) (string, error) {
	if file == nil {
		return "", fmt.Errorf("file is required")
	}
	key, err := s.destination(tenantID, knowledgeID, file.Filename, false)
	if err != nil {
		return "", err
	}
	reader, err := file.Open()
	if err != nil {
		return "", fmt.Errorf("open upload file: %w", err)
	}
	defer reader.Close()
	ctx, cancel := objectStorageTransferContext(ctx)
	defer cancel()
	if err := s.upload(ctx, key, reader, file.Filename, file.Header.Get("Content-Type")); err != nil {
		return "", err
	}
	return s.reference(key), nil
}

func (s *azureFileService) SaveBytes(ctx context.Context, data []byte, tenantID uint64, filename string, temporary bool) (string, error) {
	key, err := s.destination(tenantID, "", filename, temporary)
	if err != nil {
		return "", err
	}
	ctx, cancel := objectStorageTransferContext(ctx)
	defer cancel()
	if err := s.upload(ctx, key, bytes.NewReader(data), filename, ""); err != nil {
		return "", err
	}
	return s.reference(key), nil
}

func (s *azureFileService) GetFile(ctx context.Context, reference string) (io.ReadCloser, error) {
	key, err := s.objectKey(ctx, reference)
	if err != nil {
		return nil, err
	}
	ctx, cancel := objectStorageTransferContext(ctx)
	response, err := s.client.DownloadStream(ctx, s.config.ContainerName, key, nil)
	if err != nil {
		cancel()
		return nil, azureError("download", err)
	}
	return objectStorageBoundReader(response.Body, cancel), nil
}

func (s *azureFileService) GetFileURL(ctx context.Context, reference string) (string, error) {
	key, err := s.objectKey(ctx, reference)
	if err != nil {
		return "", err
	}
	if s.externalURL == "" {
		return reference, nil // authenticated file proxy remains available
	}
	parts := strings.Split(strings.TrimPrefix(key, s.prefix), "/")
	tenantID, _ := strconv.ParseUint(parts[1], 10, 64)
	return utils.SignFileURL(s.externalURL, reference, tenantID, 2*time.Hour)
}

func (s *azureFileService) DeleteFile(ctx context.Context, reference string) error {
	key, err := s.objectKey(ctx, reference)
	if err != nil {
		return err
	}
	ctx, cancel := objectStorageTransferContext(ctx)
	defer cancel()
	_, err = s.client.DeleteBlob(ctx, s.config.ContainerName, key, nil)
	var response *azcore.ResponseError
	if errors.As(err, &response) && response.ErrorCode == "BlobNotFound" {
		return nil
	}
	return azureError("delete", err)
}

func (s *azureFileService) CopyFile(ctx context.Context, reference string, tenantID uint64, knowledgeID string) (string, error) {
	source, err := s.objectKey(ctx, reference)
	if err != nil {
		return "", ErrCrossBackendCopy
	}
	target, err := s.destination(tenantID, knowledgeID, path.Base(source), false)
	if err != nil {
		return "", err
	}
	ctx, cancel := objectStorageTransferContext(ctx)
	defer cancel()
	response, err := s.client.DownloadStream(ctx, s.config.ContainerName, source, nil)
	if err != nil {
		return "", azureError("copy read", err)
	}
	defer response.Body.Close()
	contentType := ""
	if response.ContentType != nil {
		contentType = *response.ContentType
	}
	filename := path.Base(source)
	if response.ContentDisposition != nil {
		if _, params, parseErr := mime.ParseMediaType(*response.ContentDisposition); parseErr == nil {
			if original, nameErr := utils.SafeFileName(params["filename"]); nameErr == nil {
				filename = original
			}
		}
	}
	// Stream an independent copy with bounded buffers. This works with both
	// auth modes, private endpoints and no SAS signing/delegation permissions.
	if err := s.upload(ctx, target, response.Body, filename, contentType); err != nil {
		return "", err
	}
	return s.reference(target), nil
}

type AzureConnectivityStage struct {
	Stage  string `json:"stage"`
	Status string `json:"status"`
}

type AzureConnectivityError struct {
	Stages []AzureConnectivityStage
	cause  error
}

func (e *AzureConnectivityError) Error() string {
	return utils.SanitizeStorageConnectivityError(e.cause)
}
func (e *AzureConnectivityError) SafeStorageMessage() string { return e.Error() }
func (e *AzureConnectivityError) Unwrap() error              { return e.cause }

// AzureConnectivityStages reports completed operations, including cleanup after a failed probe.
func AzureConnectivityStages(err error) []AzureConnectivityStage {
	var report *AzureConnectivityError
	if errors.As(err, &report) {
		return report.Stages
	}
	status := "passed"
	if err != nil {
		status = "skipped"
	}
	return []AzureConnectivityStage{{"container", status}, {"upload", status}, {"read", status}, {"cleanup", status}}
}

func (s *azureFileService) CheckConnectivity(ctx context.Context) (result error) {
	stages := []AzureConnectivityStage{{"container", "skipped"}, {"upload", "skipped"}, {"read", "skipped"}, {"cleanup", "skipped"}}
	defer func() {
		if result != nil {
			result = &AzureConnectivityError{Stages: stages, cause: result}
		}
	}()

	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	container := s.client.ServiceClient().NewContainerClient(s.config.ContainerName)
	if _, err := container.GetProperties(ctx, nil); err != nil {
		stages[0].Status = "failed"
		return azureError("container check", err)
	}
	stages[0].Status = "passed"
	key := s.prefix + ".connection-check/" + uuid.NewString()
	// Upload errors can occur after the object has been committed. Cleanup
	// uses its own bounded context, including when the test was cancelled.
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cleanupCancel()
		_, err := s.client.DeleteBlob(cleanupCtx, s.config.ContainerName, key, nil)
		var response *azcore.ResponseError
		if errors.As(err, &response) && response.ErrorCode == "BlobNotFound" {
			err = nil
		}
		stages[3].Status = "passed"
		if err != nil {
			stages[3].Status = "failed"
		}
		result = errors.Join(result, azureError("test object cleanup", err))
	}()
	data := []byte("weknora-azure-storage-check")
	if err := s.upload(ctx, key, bytes.NewReader(data), "check.txt", "text/plain"); err != nil {
		stages[1].Status = "failed"
		return err
	}
	stages[1].Status = "passed"
	stages[2].Status = "failed"
	response, err := s.client.DownloadStream(ctx, s.config.ContainerName, key, nil)
	if err != nil {
		return azureError("test object read", err)
	}
	got, readErr := io.ReadAll(io.LimitReader(response.Body, int64(len(data)+1)))
	closeErr := response.Body.Close()
	if err := errors.Join(readErr, closeErr); err != nil {
		return azureError("test object read", err)
	}
	if !bytes.Equal(got, data) {
		return &AzureStorageError{Operation: "test object read", Code: "integrity"}
	}
	stages[2].Status = "passed"
	return nil
}

var _ interfaces.FileService = (*azureFileService)(nil)
