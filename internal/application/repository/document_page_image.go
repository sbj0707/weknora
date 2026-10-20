package repository

import (
	"context"
	"time"

	"github.com/Tencent/WeKnora/internal/types"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type DocumentPageImageRepository struct { DB *gorm.DB }

func NewDocumentPageImageRepository(db *gorm.DB) *DocumentPageImageRepository {
	return &DocumentPageImageRepository{DB: db}
}

func (r *DocumentPageImageRepository) List(ctx context.Context, tenant uint64, doc, hash string) ([]types.DocumentPageImage, error) {
	rows := []types.DocumentPageImage{}
	err := r.DB.WithContext(ctx).Where("tenant_id = ? AND knowledge_id = ? AND source_hash = ? AND render_profile = ?", tenant, doc, hash, types.PageImageRenderProfile).Order("page_number").Find(&rows).Error
	return rows, err
}

func (r *DocumentPageImageRepository) Ensure(ctx context.Context, k *types.Knowledge, pages []int) error {
	rows := make([]types.DocumentPageImage, 0, len(pages))
	for _, page := range pages {
		rows = append(rows, types.DocumentPageImage{ID:uuid.NewString(), TenantID:k.TenantID, KnowledgeBaseID:k.KnowledgeBaseID, KnowledgeID:k.ID, SourceHash:k.FileHash, PageNumber:page, MimeType:"image/jpeg", RenderProfile:types.PageImageRenderProfile, Status:"pending"})
	}
	if len(rows)==0 { return nil }
	return r.DB.WithContext(ctx).Clauses(clause.OnConflict{DoNothing:true}).CreateInBatches(&rows,100).Error
}

// A database lease serializes renderers across app replicas; expired work can be retried.
func (r *DocumentPageImageRepository) Claim(ctx context.Context, id string, force bool, token string) (bool,error) {
	now:=time.Now()
	q:=r.DB.WithContext(ctx).Model(&types.DocumentPageImage{}).Where("id = ? AND (lease_until IS NULL OR lease_until < ?)",id,now)
	if !force { q=q.Where("status <> ?","completed") }
	res:=q.Updates(map[string]any{"status":"processing","error_message":"","task_token":token,"lease_until":now.Add(3*time.Minute),"updated_at":now})
	return res.RowsAffected==1,res.Error
}

func (r *DocumentPageImageRepository) Finish(ctx context.Context, row *types.DocumentPageImage, token string) (bool,error) {
	res:=r.DB.WithContext(ctx).Model(&types.DocumentPageImage{}).Where("id = ? AND task_token = ?", row.ID,token).Updates(map[string]any{"storage_key":row.StorageKey,"width":row.Width,"height":row.Height,"size":row.Size,"status":row.Status,"error_message":row.ErrorMessage,"lease_until":nil,"task_token":"","updated_at":time.Now()})
	return res.RowsAffected==1,res.Error
}

func (r *DocumentPageImageRepository) Links(ctx context.Context, chunk *types.Chunk, hash string) ([]int,error) {
	var pages []int
	err:=r.DB.WithContext(ctx).Model(&types.ChunkPageImageLink{}).Where("tenant_id = ? AND knowledge_id = ? AND chunk_id = ? AND content_revision = ? AND source_hash = ?",chunk.TenantID,chunk.KnowledgeID,chunk.ID,chunk.ContentRevision,hash).Order("page_number").Pluck("page_number",&pages).Error
	return pages,err
}

func (r *DocumentPageImageRepository) SetLinks(ctx context.Context, k *types.Knowledge, chunk *types.Chunk, pages []int, user string) error {
	return r.DB.WithContext(ctx).Transaction(func(tx *gorm.DB)error {
		// Serialize with edits: an association can only be committed for this exact revision.
		var current types.Chunk
		if err:=tx.Clauses(clause.Locking{Strength:"UPDATE"}).Where("id = ? AND tenant_id = ? AND knowledge_id = ? AND content_revision = ?",chunk.ID,k.TenantID,k.ID,chunk.ContentRevision).First(&current).Error;err!=nil{return err}
		if err:=tx.Where("tenant_id = ? AND chunk_id = ?",k.TenantID,chunk.ID).Delete(&types.ChunkPageImageLink{}).Error;err!=nil{return err}
		for _,p:=range pages {
			link:=types.ChunkPageImageLink{ID:uuid.NewString(),TenantID:k.TenantID,KnowledgeBaseID:k.KnowledgeBaseID,KnowledgeID:k.ID,ChunkID:chunk.ID,ContentRevision:chunk.ContentRevision,SourceHash:k.FileHash,PageNumber:p,CreatedBy:user}
			if err:=tx.Create(&link).Error;err!=nil{return err}
		}
		return nil
	})
}
