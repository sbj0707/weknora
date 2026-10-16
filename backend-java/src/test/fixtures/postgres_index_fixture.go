//go:build ignore

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	pg "github.com/Tencent/WeKnora/internal/application/repository/retriever/postgres"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/jackc/pgx/v5/pgconn"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"math"
	"os"
)

type input struct {
	SourceID        string `json:"source_id"`
	SourceType      int    `json:"source_type"`
	ChunkID         string `json:"chunk_id"`
	KnowledgeID     string `json:"knowledge_id"`
	KnowledgeBaseID string `json:"knowledge_base_id"`
	TagID           string `json:"tag_id"`
	Content         string `json:"content"`
	Enabled         bool   `json:"enabled"`
}

func (i input) info() *types.IndexInfo {
	return &types.IndexInfo{SourceID: i.SourceID, SourceType: types.SourceType(i.SourceType), ChunkID: i.ChunkID, KnowledgeID: i.KnowledgeID, KnowledgeBaseID: i.KnowledgeBaseID, TagID: i.TagID, Content: i.Content, IsEnabled: i.Enabled}
}
func must(e error) {
	if e != nil {
		panic(e)
	}
}
func state(e error) string {
	var p *pgconn.PgError
	if errors.As(e, &p) {
		return p.Code
	}
	return "other"
}
func main() {
	// Destructive reset is constrained to the explicitly named disposable parity database.
	dsn := os.Getenv("WEKNORA_PARITY_PG_DSN")
	if dsn == "" {
		panic("WEKNORA_PARITY_PG_DSN required")
	}
	db, e := gorm.Open(postgres.Open(dsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	must(e)
	sql, e := db.DB()
	must(e)
	defer sql.Close()
	sql.SetMaxOpenConns(1)
	var database string
	must(db.Raw("SELECT current_database()").Scan(&database).Error)
	if database != "weknora_parity" {
		panic("only disposable weknora_parity database accepted")
	}
	must(db.Exec("TRUNCATE embeddings RESTART IDENTITY").Error)
	ctx := context.Background()
	repo := pg.NewPostgresRetrieveEngineRepository(db)
	queries := []types.RetrieveParams{}
	for _, q := range []string{"hello", "世界", "中文", "人民银行", "中国", "A股", "2023", "中文? OR 数据*", "\"hello\" -missing", "mixed Words", "cafe", "ＣＡＦＥ", "a_b", "東京", "한글", "あいう", "emoji 😀", "missing", "中文 hello", ""} {
		for _, k := range []int{0, 1, 3, 10} {
			queries = append(queries, types.RetrieveParams{Query: q, TopK: k, RetrieverType: types.KeywordsRetrieverType})
		}
	}
	for _, v := range [][]float32{{1, 0}, {0, 1}, {-1, 0}, {.8, .6}, {1, 0, 0}, {0, 0}} {
		for filter := 0; filter < 11; filter++ {
			p := types.RetrieveParams{Embedding: v, TopK: 3, RetrieverType: types.VectorRetrieverType}
			switch filter {
			case 1:
				p.KnowledgeBaseIDs = []string{"kb"}
			case 2:
				p.KnowledgeIDs = []string{"doc"}
			case 3:
				p.TagIDs = []string{"tag"}
			case 4:
				p.KnowledgeBaseIDs = []string{"kb"}
				p.KnowledgeIDs = []string{"doc"}
				p.TagIDs = []string{"tag"}
			case 5:
				p.Threshold = .9
			case 6:
				p.ExcludeChunkIDs = []string{"a"}
			case 7:
				p.TopK = 1
			case 8:
				p.TopK = 0
			case 9:
				p.Threshold = -.5
			case 10:
				p.Threshold = 1.1
			}
			queries = append(queries, p)
		}
	}
	queries = append(queries, types.RetrieveParams{TopK: 3, RetrieverType: types.VectorRetrieverType}, types.RetrieveParams{Embedding: []float32{1, 0}, TopK: 201, RetrieverType: types.VectorRetrieverType})
	steps := []map[string]any{}
	capture := func(op string, inputs []input, vectors map[string][]float32, ids []string) {
		infos := []*types.IndexInfo{}
		for _, in := range inputs {
			infos = append(infos, in.info())
		}
		var err error
		switch op {
		case "save":
			err = repo.BatchSave(ctx, infos, map[string]any{"embedding": vectors})
		case "delete_chunk":
			err = repo.DeleteByChunkIDList(ctx, ids, 0, "")
		case "delete_source":
			err = repo.DeleteBySourceIDList(ctx, ids, 0, "")
		case "delete_knowledge":
			err = repo.DeleteByKnowledgeIDList(ctx, ids, 0, "")
		case "disable":
			err = repo.BatchUpdateChunkEnabledStatus(ctx, map[string]bool{ids[0]: false})
		case "tag":
			err = repo.BatchUpdateChunkTagID(ctx, map[string]string{ids[0]: ids[1]})
		case "move":
			err = repo.(interfaces.KnowledgeIndexMover).MoveKnowledgeIndices(ctx, ids[0], ids[1], ids[2], nil, 0, "")
		case "copy":
			err = repo.CopyIndices(ctx, "kb", map[string]string{"doc": "copied-doc"}, map[string]string{"a": "copied-a", "b": "copied-b"}, "copied-kb", 0, "")
		}
		item := map[string]any{"op": op, "inputs": inputs, "embeddings": vectors, "ids": ids, "estimate": repo.EstimateStorageSize(ctx, infos, map[string]any{"embedding": vectors})}
		if err != nil {
			item["error_state"] = state(err)
		}
		var rows []map[string]any
		must(db.Raw("SELECT id,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,embedding::text AS embedding,is_enabled FROM embeddings ORDER BY id").Scan(&rows).Error)
		if rows == nil {
			rows = []map[string]any{}
		}
		item["rows"] = rows
		replies := []map[string]any{}
		for _, p := range queries {
			r, e := repo.Retrieve(ctx, p)
			q := map[string]any{"params": p, "result": r}
			if e != nil {
				q["error_state"] = state(e)
			}
			replies = append(replies, q)
		}
		item["queries"] = replies
		steps = append(steps, item)
	}
	mk := func(id, kb, doc, tag, text string, on bool) input { return input{id, 0, id, doc, kb, tag, text, on} }
	capture("save", []input{mk("a", "kb", "doc", "tag", "hello 世界 中文 人民银行 A股 2023", true), mk("b", "kb", "doc", "tag", "hello 中文 数据 mixed Words", true), mk("blocked", "other", "other", "other", "hello 中文", true), mk("disabled", "kb", "doc", "tag", "hello 中文", false), mk("c", "kb", "another", "", "café ＣＡＦＥ 東京 한글 あいう emoji 😀", true)}, map[string][]float32{"a": {1, 0}, "b": {.8, .6}, "blocked": {1, 0}, "disabled": {1, 0}, "c": {-1, 0}}, nil)
	capture("save", []input{mk("a", "kb", "doc", "tag", "updated 新内容 NULL\x00clean", true), mk("new", "kb", "doc", "", "newanswer", true)}, map[string][]float32{"a": {0, 1}, "new": {1, 0, 0}}, nil)
	dup := mk("dup", "kb", "doc", "tag", "first", true)
	last := dup
	last.Content = "last duplicate"
	capture("save", []input{dup, last}, map[string][]float32{"dup": {.1, .2}}, nil)
	typed := mk("a", "kb", "doc", "", "passage hello", true)
	typed.SourceType = 1
	capture("save", []input{typed}, map[string][]float32{"a": {1, 0}}, nil)
	question := mk("a-question", "kb", "doc", "tag", "generated question hello", true)
	question.ChunkID = "a"
	question.SourceType = 2
	capture("save", []input{question}, map[string][]float32{"a-question": {.12345678, .8765432}}, nil)
	capture("save", []input{mk("no-vector", "kb", "doc", "", "must fail as Go halfvec []", true)}, nil, nil)
	capture("save", []input{mk("overflow", "kb", "doc", "", "half overflow", true)}, map[string][]float32{"overflow": {math.MaxFloat32, 1}}, nil)
	capture("copy", nil, nil, nil)
	capture("copy", nil, nil, nil)
	capture("disable", nil, nil, []string{"a"})
	capture("tag", nil, nil, []string{"b", "retag"})
	capture("move", nil, nil, []string{"copied-kb", "moved-kb", "copied-doc"})
	capture("delete_chunk", nil, nil, []string{"a"})
	capture("delete_source", nil, nil, []string{"dup"})
	capture("delete_knowledge", nil, nil, []string{"doc"})
	capture("delete_knowledge", nil, nil, []string{"another", "other", "copied-doc"})
	var version string
	must(db.Raw("SELECT version()").Scan(&version).Error)
	out := map[string]any{"steps": steps, "version": version}
	file, e := os.Create(os.Args[1])
	must(e)
	defer file.Close()
	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	must(encoder.Encode(out))
	fmt.Fprintf(os.Stderr, "%d Go PostgreSQL mutation cases, %d queries each\n", len(steps), len(queries))
}
