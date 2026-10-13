//go:build ignore
package main
import("encoding/json";"reflect";"strings";"os";"github.com/Tencent/WeKnora/internal/types")
func schema(t reflect.Type)map[string]any{out:=map[string]any{"kind":t.Kind().String()};if t.Kind()==reflect.Pointer{return map[string]any{"kind":"pointer","items":schema(t.Elem())}};switch t.Kind(){case reflect.Struct:fields:=map[string]any{};for i:=0;i<t.NumField();i++{f:=t.Field(i);if f.PkgPath!=""{continue};tag:=strings.Split(f.Tag.Get("json"),",")[0];if tag=="-"{continue};if tag==""{tag=f.Name};fields[tag]=schema(f.Type)};out["fields"]=fields;case reflect.Slice,reflect.Array,reflect.Map:out["items"]=schema(t.Elem());case reflect.Int,reflect.Int8,reflect.Int16,reflect.Int32,reflect.Int64,reflect.Uint,reflect.Uint8,reflect.Uint16,reflect.Uint32,reflect.Uint64:out["bits"]=t.Bits()};return out}
func main(){enc:=json.NewEncoder(os.Stdout);enc.SetIndent("","  ");enc.Encode(schema(reflect.TypeOf(types.CustomAgentConfig{})))}
