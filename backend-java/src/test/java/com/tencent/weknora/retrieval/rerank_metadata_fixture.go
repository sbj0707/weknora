//go:build ignore

// Actual production Go image/document metadata and reranking passage behavior.
package main

import (
	_ "embed"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
)

//go:embed rerank_metadata_oracle.go.txt
var algorithm string

func main() {
	if len(os.Args) != 2 {
		panic("usage: go run rerank_metadata_fixture.go metadata.json")
	}
	root, err := os.Getwd()
	must(err)
	if _, err = os.Stat(filepath.Join(root, "go.mod")); err != nil {
		root = filepath.Dir(root)
		if _, err = os.Stat(filepath.Join(root, "go.mod")); err != nil {
			panic("run from repository root or backend-java")
		}
	}
	dir, err := os.MkdirTemp("", "weknora-hybrid-go-oracle-")
	must(err)
	defer os.RemoveAll(dir)
	first := filepath.Join(dir, "algorithm.go.txt")
	must(os.WriteFile(first, []byte(algorithm), 0600))
	overlay, err := json.Marshal(map[string]any{"Replace": map[string]string{filepath.Join(root, "internal/application/service/java_rerank_metadata_oracle_test.go"): first}})
	must(err)
	overlayFile := filepath.Join(dir, "overlay.json")
	must(os.WriteFile(overlayFile, overlay, 0600))
	output, err := filepath.Abs(os.Args[1])
	must(err)
	cmd := exec.Command("go", "test", "-tags", "sqlite_fts5", "-vet=off", "-overlay="+overlayFile, "./internal/application/service", "-run", "^TestJavaPassageMetadataOracle$", "-count=1")
	cmd.Dir = root
	cmd.Env = append(os.Environ(), "WEKNORA_PASSAGE_METADATA_FIXTURE_OUTPUT="+output)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	must(cmd.Run())
}
func must(err error) {
	if err != nil {
		panic(err)
	}
}
