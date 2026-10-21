package azureaisearch

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"maps"
	"net/http"
	"slices"
	"time"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/google/uuid"
)

const maxBatchBytes = 12 << 20 // below Azure's 16 MB request limit, including envelope
const maxBatchCount = 1000

type action map[string]any

type ItemError struct {
	Key    string
	Status int
}
type BatchError struct{ Items []ItemError }

func (e *BatchError) Error() string {
	return fmt.Sprintf("%v: %d document action(s) failed", ErrBatch, len(e.Items))
}
func (*BatchError) Unwrap() error { return ErrBatch }

func (r *Repository) Save(ctx context.Context, info *types.IndexInfo, params map[string]any) error {
	return r.BatchSave(ctx, []*types.IndexInfo{info}, params)
}
func (r *Repository) BatchSave(ctx context.Context, infos []*types.IndexInfo, params map[string]any) error {
	if len(infos) == 0 {
		return nil
	}
	embeddings, _ := params["embedding"].(map[string][]float32)
	enabled, _ := params["chunk_enabled"].(map[string]bool)
	actions := make([]action, 0, len(infos))
	seen := map[string]bool{}
	for _, info := range infos {
		if info == nil || info.SourceID == "" || info.ChunkID == "" || info.KnowledgeID == "" || info.KnowledgeBaseID == "" {
			return fmt.Errorf("Azure AI Search index requires source, chunk, knowledge and knowledge base IDs")
		}
		vector, hasVector := embeddings[info.SourceID]
		if len(embeddings) > 0 && !hasVector {
			return fmt.Errorf("missing embedding for Azure AI Search index source")
		}
		if hasVector {
			if err := r.validateVector(vector); err != nil {
				return err
			}
		}
		isEnabled := info.IsEnabled
		if value, ok := enabled[info.ChunkID]; ok {
			isEnabled = value
		}
		d := document{ID: r.documentID(int(info.SourceType), info.SourceID), TenantID: r.tenantID, StoreID: r.storeID, Content: info.Content, Embedding: vector, SourceID: info.SourceID, SourceType: int(info.SourceType), ChunkID: info.ChunkID, KnowledgeID: info.KnowledgeID, KnowledgeBaseID: info.KnowledgeBaseID, KnowledgeType: normalizedKnowledgeType(info.KnowledgeType), TagID: info.TagID, IsEnabled: isEnabled, IsRecommended: info.IsRecommended}
		a, err := uploadAction(d)
		if err != nil {
			return err
		}
		if seen[d.ID] {
			return fmt.Errorf("duplicate Azure AI Search source ID in batch")
		}
		seen[d.ID] = true
		actions = append(actions, a)
	}
	if _, err := r.ensureReady(ctx, true); err != nil {
		return err
	}
	return r.submit(ctx, actions)
}
func uploadAction(d document) (action, error) {
	d.Score = 0
	data, err := json.Marshal(d)
	if err != nil {
		return nil, err
	}
	var a action
	if err := json.Unmarshal(data, &a); err != nil {
		return nil, err
	}
	a["@search.action"] = "upload"
	return a, nil
}

// submit bounds both count and the actual JSON byte size, including escaping.
func (r *Repository) submit(ctx context.Context, actions []action) error {
	ctx, cancel := context.WithTimeout(ctx, 90*time.Second)
	defer cancel()
	// A visibility token lets callers safely chain upload -> copy/update/delete.
	// Polling by this token also avoids accepting an older version of a record.
	token := uuid.NewString()
	prepared := make([]action, 0, len(actions))
	for _, original := range actions {
		a := maps.Clone(original)
		if a["@search.action"] != "delete" {
			a["write_token"] = token
		}
		prepared = append(prepared, a)
	}
	var batch []action
	size := len(`{"value":[]}`)
	for _, a := range prepared {
		data, err := json.Marshal(a)
		if err != nil {
			return err
		}
		if len(data)+len(`{"value":[]}`) > maxBatchBytes {
			return fmt.Errorf("Azure AI Search single document exceeds batch size limit")
		}
		if len(batch) >= maxBatchCount || size+len(data)+1 > maxBatchBytes {
			if err := r.sendBatch(ctx, batch); err != nil {
				return err
			}
			batch = nil
			size = len(`{"value":[]}`)
		}
		batch = append(batch, a)
		size += len(data) + 1
	}
	if len(batch) > 0 {
		if err := r.sendBatch(ctx, batch); err != nil {
			return err
		}
	}
	return r.awaitVisible(ctx, prepared, token)
}
func transientItem(status int) bool {
	return status == 408 || status == 409 || status == 422 || status == 429 || status >= 500
}

func (r *Repository) sendBatch(ctx context.Context, actions []action) error {
	pending := actions
	var failures []ItemError
	for attempt := 0; attempt < 4 && len(pending) > 0; attempt++ {
		data, err := r.client.request(ctx, http.MethodPost, "/indexes/"+r.index+"/docs/index", nil, map[string]any{"value": pending}, nil)
		if hasStatus(err, 413) && len(pending) > 1 {
			mid := len(pending) / 2
			first := r.sendBatch(ctx, pending[:mid])
			second := r.sendBatch(ctx, pending[mid:])
			if len(failures) > 0 {
				return errors.Join(&BatchError{Items: failures}, first, second)
			}
			return errors.Join(first, second)
		}
		if err != nil {
			if len(failures) > 0 {
				return errors.Join(&BatchError{Items: failures}, err)
			}
			return err
		}
		var response struct {
			Value []struct {
				Key        string `json:"key"`
				Status     bool   `json:"status"`
				StatusCode int    `json:"statusCode"`
			} `json:"value"`
		}
		if err := json.Unmarshal(data, &response); err != nil {
			return fmt.Errorf("decode Azure AI Search batch: %w", err)
		}
		byKey := make(map[string]action, len(pending))
		for _, a := range pending {
			key, _ := a["id"].(string)
			if key == "" {
				return fmt.Errorf("Azure AI Search action has no key")
			}
			if _, ok := byKey[key]; ok {
				return fmt.Errorf("duplicate Azure AI Search key in one batch")
			}
			byKey[key] = a
		}
		retry := make([]action, 0)
		for _, item := range response.Value {
			a, ok := byKey[item.Key]
			if !ok {
				return fmt.Errorf("Azure AI Search batch returned an unexpected or duplicate key")
			}
			delete(byKey, item.Key)
			if item.Status && item.StatusCode >= 200 && item.StatusCode < 300 {
				continue
			}
			// Deleting a missing document is idempotent even if a backend reports 404.
			if item.StatusCode == 404 && a["@search.action"] == "delete" {
				continue
			}
			if transientItem(item.StatusCode) && attempt < 3 {
				retry = append(retry, a)
			} else {
				failures = append(failures, ItemError{Key: item.Key, Status: item.StatusCode})
			}
		}
		if len(byKey) > 0 {
			return fmt.Errorf("Azure AI Search batch response omitted %d action(s)", len(byKey))
		}
		pending = retry
		if len(pending) > 0 {
			if err := waitRetry(ctx, time.Duration(150*(1<<attempt))*time.Millisecond); err != nil {
				return err
			}
		}
	}
	if len(failures) > 0 {
		return &BatchError{Items: failures}
	}
	return nil
}

// Azure push acknowledgements precede query visibility. Confirm this write's
// version before dependent lifecycle scans and return a retryable failure on
// timeout. This bounded check does not provide a cross-replica snapshot.
func (r *Repository) awaitVisible(ctx context.Context, actions []action, token string) error {
	ids := []string{}
	for _, a := range actions {
		if a["@search.action"] != "delete" {
			ids = append(ids, a["id"].(string))
		}
	}
	if len(ids) == 0 {
		return nil
	}
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	for start := 0; start < len(ids); start += filterBatchSize {
		wanted := ids[start:min(start+filterBatchSize, len(ids))]
		remaining := slices.Clone(wanted)
		for len(remaining) > 0 {
			page, err := r.search(ctx, map[string]any{"search": "*", "filter": and(r.scope(), inFilter("id", remaining), "write_token eq "+quote(token)), "select": "id,tenant_id,store_id,write_token", "top": len(remaining)})
			if err != nil {
				return fmt.Errorf("Azure AI Search mutation visibility check: %w", err)
			}
			found := map[string]bool{}
			for _, d := range page.Value {
				if d.TenantID != r.tenantID || d.StoreID != r.storeID || d.WriteToken != token || !slices.Contains(remaining, d.ID) {
					return fmt.Errorf("Azure AI Search visibility check returned an unrelated record")
				}
				found[d.ID] = true
			}
			remaining = slices.DeleteFunc(remaining, func(id string) bool { return found[id] })
			if len(remaining) > 0 {
				if err := waitRetry(ctx, 200*time.Millisecond); err != nil {
					return fmt.Errorf("Azure AI Search mutation acknowledged but not yet searchable; retry the operation: %w", err)
				}
			}
		}
	}
	return nil
}
