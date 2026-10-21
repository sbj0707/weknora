// Package azureaisearch implements the Azure AI Search data-plane adapter.
package azureaisearch

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/Azure/azure-sdk-for-go/sdk/azcore"
	"github.com/Azure/azure-sdk-for-go/sdk/azcore/policy"
	"github.com/Azure/azure-sdk-for-go/sdk/azidentity"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
)

const APIVersion = "2025-09-01"
const tokenScope = "https://search.azure.com/.default"

var (
	ErrAuthentication = errors.New("Azure AI Search authentication failed")
	ErrPermission     = errors.New("Azure AI Search permission denied")
	ErrDimension      = errors.New("Azure AI Search vector dimension mismatch")
	ErrSchema         = errors.New("Azure AI Search incompatible index schema or owner")
	ErrThrottled      = errors.New("Azure AI Search throttled")
	ErrUnavailable    = errors.New("Azure AI Search temporarily unavailable")
	ErrBatch          = errors.New("Azure AI Search indexing failed")
)

// HTTPError deliberately omits the service's message/body, which may echo
// document contents or credentials. Status/code retain useful error identity.
type HTTPError struct {
	Status       int
	Code         string
	RetryAfter   time.Duration
	MissingIndex bool
}

func (e *HTTPError) Error() string {
	return fmt.Sprintf("Azure AI Search HTTP %d (%s)", e.Status, e.Code)
}
func (e *HTTPError) Unwrap() error {
	switch e.Status {
	case 401:
		return ErrAuthentication
	case 403:
		return ErrPermission
	case 429:
		return ErrThrottled
	}
	if e.Status >= 500 {
		return ErrUnavailable
	}
	return nil
}
func hasStatus(err error, status int) bool {
	var e *HTTPError
	return errors.As(err, &e) && e.Status == status
}

// Client is immutable and safe to share across requests and workers.
type Client struct {
	endpoint   string
	apiKey     string
	credential azcore.TokenCredential
	http       *http.Client
}

func NewClient(config types.ConnectionConfig) (*Client, error) {
	config = config.NormalizeAzureAISearch()
	if err := types.ValidateAzureAISearchConnection(config); err != nil {
		return nil, err
	}
	if err := utils.ValidateURLForSSRF(config.Addr); err != nil {
		return nil, fmt.Errorf("Azure AI Search address: %w", err)
	}
	transport := utils.NewSSRFSafeTransport(utils.DefaultSSRFSafeHTTPClientConfig())
	transport.TLSClientConfig = &tls.Config{MinVersion: tls.VersionTLS12}
	transport.MaxIdleConnsPerHost = 16
	transport.IdleConnTimeout = 90 * time.Second
	transport.ResponseHeaderTimeout = 20 * time.Second
	c := &Client{endpoint: config.Addr, apiKey: config.APIKey, http: &http.Client{
		Transport: transport, Timeout: 30 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
	if config.AuthMode == "managed_identity" {
		opts := &azidentity.ManagedIdentityCredentialOptions{}
		if config.ManagedIdentityClientID != "" {
			opts.ID = azidentity.ClientID(config.ManagedIdentityClientID)
		}
		credential, err := azidentity.NewManagedIdentityCredential(opts)
		if err != nil {
			return nil, fmt.Errorf("initialize Azure managed identity: %w", err)
		}
		c.credential = credential
	}
	return c, nil
}

// TestConnection is read-only; success does not imply document-write permission.
func (c *Client) TestConnection(ctx context.Context) error {
	_, err := c.request(ctx, http.MethodGet, "/indexes", url.Values{"$select": {"name"}}, nil, nil)
	return err
}

func (c *Client) request(ctx context.Context, method, path string, query url.Values, payload any, headers http.Header) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	var body []byte
	var err error
	if payload != nil {
		body, err = json.Marshal(payload)
		if err != nil {
			return nil, fmt.Errorf("encode Azure AI Search request: %w", err)
		}
	}
	if query == nil {
		query = url.Values{}
	} else {
		copyQuery := url.Values{}
		for k, v := range query {
			copyQuery[k] = append([]string(nil), v...)
		}
		query = copyQuery
	}
	query.Set("api-version", APIVersion)
	var last error
	for attempt := 0; attempt < 4; attempt++ {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		req, err := http.NewRequestWithContext(ctx, method, c.endpoint+path+"?"+query.Encode(), bytes.NewReader(body))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json")
		for key, values := range headers {
			req.Header[key] = append([]string(nil), values...)
		}
		if c.credential != nil {
			token, err := c.credential.GetToken(ctx, policy.TokenRequestOptions{Scopes: []string{tokenScope}})
			if err != nil {
				if ctx.Err() != nil {
					return nil, ctx.Err()
				}
				return nil, ErrAuthentication
			}
			req.Header.Set("Authorization", "Bearer "+token.Token)
		} else {
			req.Header.Set("api-key", c.apiKey)
		}
		resp, callErr := c.http.Do(req)
		delay := time.Duration(150*(1<<attempt))*time.Millisecond + time.Duration(rand.IntN(100))*time.Millisecond
		if callErr != nil {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			var netErr net.Error
			if !errors.As(callErr, &netErr) {
				return nil, fmt.Errorf("Azure AI Search transport: %w", callErr)
			}
			last = ErrUnavailable
		} else {
			data, readErr := io.ReadAll(io.LimitReader(resp.Body, (64<<20)+1))
			resp.Body.Close()
			if readErr != nil {
				return nil, fmt.Errorf("read Azure AI Search response: %w", readErr)
			}
			if len(data) > 64<<20 {
				return nil, errors.New("Azure AI Search response exceeds 64 MiB")
			}
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				return data, nil
			}
			var envelope struct {
				Error struct {
					Code    string `json:"code"`
					Message string `json:"message"`
				} `json:"error"`
			}
			_ = json.Unmarshal(data, &envelope)
			// Only accept the small API error-code alphabet, never a free-form message.
			code := envelope.Error.Code
			if len(code) > 80 {
				code = "unknown"
			}
			for _, ch := range code {
				if !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '_') {
					code = "unknown"
					break
				}
			}
			apiErr := &HTTPError{Status: resp.StatusCode, Code: code, RetryAfter: retryAfter(resp.Header)}
			if resp.StatusCode == http.StatusNotFound && code == "" && strings.HasPrefix(path, "/indexes/") {
				indexName := strings.Split(strings.TrimPrefix(path, "/indexes/"), "/")[0]
				// Azure can return an empty code with either wording. Match the
				// requested index exactly; unrelated 404s must remain failures.
				message := envelope.Error.Message
				legacyMissing := strings.HasPrefix(message, "The index '"+indexName+"'") && strings.Contains(message, "was not found")
				namedMissing := strings.HasPrefix(message, "No index with the name '"+indexName+"' was found in the service '")
				apiErr.MissingIndex = legacyMissing || namedMissing
			}
			if resp.StatusCode != 408 && resp.StatusCode != 429 && resp.StatusCode < 500 {
				return nil, apiErr
			}
			last = apiErr
			delay = max(delay, apiErr.RetryAfter)
		}
		if attempt == 3 {
			break
		}
		if err := waitRetry(ctx, delay); err != nil {
			return nil, err
		}
	}
	return nil, last
}

func retryAfter(h http.Header) time.Duration {
	if n, err := strconv.ParseInt(h.Get("retry-after-ms"), 10, 64); err == nil && n > 0 {
		return time.Duration(min(n, 45000)) * time.Millisecond
	}
	if n, err := strconv.ParseInt(h.Get("Retry-After"), 10, 64); err == nil && n > 0 {
		return time.Duration(min(n, 45)) * time.Second
	}
	if at, err := http.ParseTime(h.Get("Retry-After")); err == nil {
		return max(0, min(time.Until(at), 45*time.Second))
	}
	return 0
}
func waitRetry(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
