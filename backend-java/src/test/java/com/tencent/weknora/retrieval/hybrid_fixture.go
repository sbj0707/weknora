//go:build ignore

// Actual production Go algorithms, locked cppjieba, SQLite and loopback model HTTP.
package main

import (
	_ "embed"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
)

//go:embed hybrid_oracle.go.txt
var algorithm string

//go:embed hybrid_wire_oracle.go.txt
var wire string

func main() {
	if len(os.Args) != 3 {
		panic("usage: go run hybrid_fixture.go foundation.json wire.json")
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
	second := filepath.Join(dir, "wire.go.txt")
	must(os.WriteFile(first, []byte(algorithm), 0600))
	must(os.WriteFile(second, []byte(wire), 0600))
	overlay, err := json.Marshal(map[string]any{"Replace": map[string]string{filepath.Join(root, "internal/application/service/java_hybrid_oracle_test.go"): first, filepath.Join(root, "internal/application/service/java_hybrid_wire_oracle_test.go"): second}})
	must(err)
	overlayFile := filepath.Join(dir, "overlay.json")
	must(os.WriteFile(overlayFile, overlay, 0600))
	output, err := filepath.Abs(os.Args[1])
	must(err)
	outputWire, err := filepath.Abs(os.Args[2])
	must(err)
	cmd := exec.Command("go", "test", "-tags", "sqlite_fts5", "-vet=off", "-overlay="+overlayFile, "./internal/application/service", "-run", "^TestJavaHybrid(Oracle|WireOracle)$", "-count=1")
	cmd.Dir = root
	cmd.Env = append(os.Environ(), "WEKNORA_HYBRID_FIXTURE_OUTPUT="+output, "WEKNORA_HYBRID_WIRE_FIXTURE_OUTPUT="+outputWire)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	must(cmd.Run())
}
func must(err error) {
	if err != nil {
		panic(err)
	}
}
