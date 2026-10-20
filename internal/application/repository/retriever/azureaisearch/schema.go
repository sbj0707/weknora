package azureaisearch

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"reflect"
)

type field struct {
	Name        string `json:"name"`
	Type        string `json:"type"`
	Key         bool   `json:"key"`
	Searchable  bool   `json:"searchable"`
	Filterable  bool   `json:"filterable"`
	Sortable    bool   `json:"sortable"`
	Retrievable bool   `json:"retrievable"`
	Stored      *bool  `json:"stored,omitempty"`
	Dimensions  int    `json:"dimensions,omitempty"`
	Profile     string `json:"vectorSearchProfile,omitempty"`
	Analyzer    string `json:"analyzer,omitempty"`
}
type indexSchema struct {
	Name         string     `json:"name"`
	Description  string     `json:"description"`
	Fields       []field    `json:"fields"`
	Similarity   similarity `json:"similarity"`
	VectorSearch struct {
		Algorithms []algorithm `json:"algorithms"`
		Profiles   []profile   `json:"profiles"`
	} `json:"vectorSearch"`
}
type similarity struct {
	Type string `json:"@odata.type"`
}
type algorithm struct {
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	Parameters struct {
		Metric         string `json:"metric"`
		M              int    `json:"m"`
		EFConstruction int    `json:"efConstruction"`
		EFSearch       int    `json:"efSearch"`
	} `json:"hnswParameters"`
}
type profile struct {
	Name      string `json:"name"`
	Algorithm string `json:"algorithm"`
}

func (r *Repository) schema() indexSchema {
	s := indexSchema{Name: r.index, Description: "weknora:azure-ai-search:v1:" + r.tenantID + ":" + r.storeID}
	s.Similarity.Type = "#Microsoft.Azure.Search.BM25Similarity"
	for _, name := range []string{"id", "tenant_id", "store_id", "source_id", "chunk_id", "knowledge_id", "knowledge_base_id", "knowledge_type", "tag_id", "write_token"} {
		s.Fields = append(s.Fields, field{Name: name, Type: "Edm.String", Key: name == "id", Filterable: true, Sortable: name == "id", Retrievable: true})
	}
	s.Fields = append(s.Fields,
		field{Name: "content", Type: "Edm.String", Searchable: true, Retrievable: true, Analyzer: r.analyzer},
		field{Name: "source_type", Type: "Edm.Int32", Filterable: true, Retrievable: true},
		field{Name: "is_enabled", Type: "Edm.Boolean", Filterable: true, Retrievable: true},
		field{Name: "is_recommended", Type: "Edm.Boolean", Filterable: true, Retrievable: true},
	)
	stored := true
	s.Fields = append(s.Fields, field{Name: "embedding", Type: "Collection(Edm.Single)", Searchable: true, Retrievable: true, Stored: &stored, Dimensions: r.dimensions, Profile: "weknora-vector"})
	a := algorithm{Name: "weknora-hnsw", Kind: "hnsw"}
	a.Parameters.Metric = "cosine"
	a.Parameters.M = 4
	a.Parameters.EFConstruction = 400
	a.Parameters.EFSearch = 500
	s.VectorSearch.Algorithms = []algorithm{a}
	s.VectorSearch.Profiles = []profile{{Name: "weknora-vector", Algorithm: a.Name}}
	return s
}

// ensureReady validates foreign/existing schemas before any read or mutation.
// Missing indexes are created ONLY on writes. The mutex is retryable following
// a temporary failure and the If-None-Match condition covers other processes.
func (r *Repository) ensureReady(ctx context.Context, create bool) (bool, error) {
	r.initMu.Lock()
	defer r.initMu.Unlock()
	if r.ready {
		return true, nil
	}
	data, err := r.client.request(ctx, http.MethodGet, "/indexes/"+r.index, nil, nil, nil)
	if err != nil {
		if !indexMissing(err) {
			return false, err
		}
		if !create {
			return false, nil
		}
		data, err = r.client.request(ctx, http.MethodPut, "/indexes/"+r.index, nil, r.schema(), http.Header{"If-None-Match": []string{"*"}})
		if hasStatus(err, 409) || hasStatus(err, 412) {
			data, err = r.client.request(ctx, http.MethodGet, "/indexes/"+r.index, nil, nil, nil)
		}
		if err != nil {
			return false, err
		}
	}
	var actual indexSchema
	if err := json.Unmarshal(data, &actual); err != nil {
		return false, fmt.Errorf("decode Azure AI Search schema: %w", err)
	}
	expected := r.schema()
	if actual.Name != expected.Name || actual.Description != expected.Description {
		return false, ErrSchema
	}
	if actual.Similarity != expected.Similarity {
		return false, fmt.Errorf("%w: keyword similarity", ErrSchema)
	}
	fields := make(map[string]field, len(actual.Fields))
	for _, f := range actual.Fields {
		fields[f.Name] = f
	}
	for _, f := range expected.Fields {
		got, ok := fields[f.Name]
		// Azure can materialize stored=true on every field or omit it. Both
		// representations mean true, including on non-vector fields. Normalize
		// both sides while retaining explicit false as an incompatible change.
		stored := true
		if f.Stored == nil {
			f.Stored = &stored
		}
		if got.Stored == nil {
			got.Stored = &stored
		}
		if !ok || !reflect.DeepEqual(got, f) {
			return false, fmt.Errorf("%w: field %s; create a new store and rebuild", ErrSchema, f.Name)
		}
	}
	algorithms := map[string]algorithm{}
	for _, a := range actual.VectorSearch.Algorithms {
		algorithms[a.Name] = a
	}
	profiles := map[string]profile{}
	for _, p := range actual.VectorSearch.Profiles {
		profiles[p.Name] = p
	}
	if !reflect.DeepEqual(algorithms["weknora-hnsw"], expected.VectorSearch.Algorithms[0]) || profiles["weknora-vector"] != expected.VectorSearch.Profiles[0] {
		return false, fmt.Errorf("%w: vector profile", ErrSchema)
	}
	r.ready = true
	return true, nil
}

func indexMissing(err error) bool {
	if !hasStatus(err, 404) {
		return false
	}
	var e *HTTPError
	// Azure uses ResourceNotFound for an absent named index. Unknown 404s
	// (wrong proxy route/service) must not be converted into successful emptiness.
	if errors.As(err, &e) {
		return e.Code == "ResourceNotFound" || e.Code == "IndexNotFound" || e.MissingIndex
	}
	return false
}
