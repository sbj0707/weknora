//go:build ignore

package main

import (
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/types"
	"os"
	"reflect"
	"strings"
)

func schema(t reflect.Type) any {
	if t.Kind() != reflect.Struct {
		return map[string]any{"kind": t.Kind().String(), "bits": t.Bits()}
	}
	fields := map[string]any{}
	for i := 0; i < t.NumField(); i++ {
		f := t.Field(i)
		tag := strings.Split(f.Tag.Get("json"), ",")
		fields[tag[0]] = map[string]any{"kind": f.Type.Kind().String(), "bits": func() int {
			if f.Type.Kind() == reflect.Int {
				return f.Type.Bits()
			}
			return 0
		}(), "omit_empty": len(tag) > 1 && tag[1] == "omitempty"}
	}
	return map[string]any{"kind": "struct", "fields": fields}
}
func main() {
	data, e := json.Marshal(map[string]any{"connection": schema(reflect.TypeOf(types.ConnectionConfig{})), "index": schema(reflect.TypeOf(types.IndexConfig{}))})
	if e != nil {
		panic(e)
	}
	if e = os.WriteFile(os.Args[1], append(data, '\n'), 0600); e != nil {
		panic(e)
	}
}
