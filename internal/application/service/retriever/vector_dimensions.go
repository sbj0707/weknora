package retriever

import (
	"context"

	"github.com/Tencent/WeKnora/internal/types/interfaces"
)

// VectorDimensions is zero for engines with per-dimension collections. Fixed
// schema drivers expose their configured dimension without I/O.
func (v *KeywordsVectorHybridRetrieveEngineService) VectorDimensions() int {
	if fixed, ok := v.indexRepository.(interfaces.FixedVectorDimensions); ok {
		return fixed.VectorDimensions()
	}
	return 0
}
func (c *CompositeRetrieveEngine) VectorDimensions() int {
	dimension := 0
	for _, engine := range c.engineInfos {
		if engine == nil {
			continue
		}
		if fixed, ok := engine.retrieveEngine.(interfaces.FixedVectorDimensions); ok {
			n := fixed.VectorDimensions()
			if n > 0 {
				if dimension > 0 && dimension != n {
					return -1
				}
				dimension = n
			}
		}
	}
	return dimension
}

// BoundVectorDimensions resolves an optional schema capability using the same
// ownership checks as the engine factories, without selecting retriever types.
func BoundVectorDimensions(ctx context.Context, registry interfaces.RetrieveEngineRegistry, ownership TenantStoreOwnership, tenantID uint64, storeID string) (int, error) {
	if err := VerifyBinding(ctx, registry, ownership, tenantID, storeID); err != nil {
		return 0, err
	}
	svc, err := registry.GetOrLoadByStoreID(ctx, tenantID, storeID)
	if err != nil {
		return 0, classifyLookupError(err)
	}
	if fixed, ok := svc.(interfaces.FixedVectorDimensions); ok {
		return fixed.VectorDimensions(), nil
	}
	return 0, nil
}
