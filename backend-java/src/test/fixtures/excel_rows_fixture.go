//go:build ignore
package main
import("encoding/json";"os";"encoding/base64";"github.com/Tencent/WeKnora/internal/sourceloc")
func main(){var docs []map[string]any;b,e:=os.ReadFile(os.Getenv("WEKNORA_EXCEL_ROWS_INPUT"));if e!=nil{panic(e)};if e=json.Unmarshal(b,&docs);e!=nil{panic(e)};for _,doc:=range docs{bytes,e:=base64.StdEncoding.DecodeString(doc["data"].(string));if e!=nil{panic(e)};units,e:=sourceloc.StructureUnits(doc["file_type"].(string),bytes);if e!=nil{doc["error"]=e.Error()}else{doc["error"]=""};doc["units"]=units};json.NewEncoder(os.Stdout).Encode(docs)}
