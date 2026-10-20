package service

import (
	"context"
	"fmt"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"io"
	"strings"
	"testing"
)

type attachmentTenantFileService struct{ interfaces.FileService }

func (s attachmentTenantFileService) GetFile(ctx context.Context, _ string) (io.ReadCloser, error) {
	tenant, _ := types.TenantIDFromContext(ctx)
	if tenant != 42 {
		return nil, fmt.Errorf("wrong storage tenant: %d", tenant)
	}
	return io.NopCloser(strings.NewReader("attachment content")), nil
}
func TestAzureAttachmentReadsAsOwnerDuringSharedAgentParsing(t *testing.T) {
	service := &temporaryDocumentService{fileService: attachmentTenantFileService{}}
	ctx := context.WithValue(context.Background(), types.TenantIDContextKey, uint64(43))
	document := &types.TemporaryDocument{TenantID: 42, ResourceRef: "resource://attachment", FileType: "txt"}
	content, _, _, err := service.parse(ctx, document)
	if err != nil || content != "attachment content" {
		t.Fatal("shared agent attachment lost owner scope", err)
	}
	tenant, _ := types.TenantIDFromContext(ctx)
	if tenant != 43 {
		t.Fatal("parser execution context mutated")
	}
}
