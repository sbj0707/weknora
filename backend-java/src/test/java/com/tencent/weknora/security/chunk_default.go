//go:build ignore

package main
import("encoding/json";"os";"github.com/Tencent/WeKnora/internal/types")
func main(){json.NewEncoder(os.Stdout).Encode(types.Chunk{})}
