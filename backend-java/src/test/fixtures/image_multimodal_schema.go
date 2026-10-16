//go:build ignore

package main

import (
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/types"
	"os"
	"reflect"
	"strings"
)

func imageSchema(t reflect.Type) map[string]any {
	out := map[string]any{"kind": t.Kind().String(), "type": t.String()}
	switch t.Kind() {
	case reflect.Struct:
		out["name"] = t.Name()
		fields := []any{}
		for i := 0; i < t.NumField(); i++ {
			f := t.Field(i)
			if f.PkgPath != "" {
				continue
			}
			if f.Anonymous {
				fields = append(fields, imageSchema(f.Type)["fields"].([]any)...)
				continue
			}
			tokens := strings.Split(f.Tag.Get("json"), ",")
			name := tokens[0]
			if name == "-" {
				continue
			}
			if name == "" {
				name = f.Name
			}
			fields = append(fields, map[string]any{"name": name, "schema": imageSchema(f.Type), "omitempty": len(tokens) > 1 && tokens[1] == "omitempty"})
		}
		out["fields"] = fields
	case reflect.Slice, reflect.Array, reflect.Map:
		out["elem"] = imageSchema(t.Elem())
	case reflect.Pointer:
		out["kind"] = "pointer"
		out["elem"] = imageSchema(t.Elem())
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64, reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Float32, reflect.Float64:
		out["bits"] = t.Bits()
	}
	return out
}
func main() {
	json.NewEncoder(os.Stdout).Encode(imageSchema(reflect.TypeOf(types.ImageMultimodalPayload{})))
}
