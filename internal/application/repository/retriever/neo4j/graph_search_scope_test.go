package neo4j

import (
	"reflect"
	"strings"
	"testing"
)

func TestGraphSearchPropertyListsHandleRelationOnlyEndpoints(t *testing.T) {
	for _, value := range []any{nil, []any{}} {
		if got := listI2listS(value); len(got) != 0 {
			t.Fatalf("missing endpoint properties should be empty: %#v", got)
		}
	}
	if got := listI2listS([]any{int64(1000000), float64(1000000), true}); !reflect.DeepEqual(got, []string{"1000000", "1e+06", "true"}) {
		t.Fatalf("stored typed properties changed format: %#v", got)
	}
}

func TestGraphSearchBindsExactDocumentOnSeedsAndNeighbors(t *testing.T) {
	for _, doc := range []string{"", "doc-one"} {
		q, p := graphSearchCypherForNamespace("ENTITYkb:ENTITYdoc_one", doc, []string{"Alice"})
		if !strings.Contains(q, "n.kg = $knowledgeID") || !strings.Contains(q, "m.kg = $knowledgeID") || p["knowledgeID"] != doc {
			t.Fatalf("missing exact document scope %s %#v", q, p)
		}
		if !strings.Contains(q, "name, n.kg") || !strings.Contains(q, "m.name, m.kg, type(r)") {
			t.Fatal("same-name graph rows are not stably ordered")
		}
		if p["maxSeedNodes"] != 200 || p["maxRows"] != 2000 {
			t.Fatal("search caps changed")
		}
	}
}
