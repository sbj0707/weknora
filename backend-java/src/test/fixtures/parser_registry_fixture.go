//go:build ignore
package main
import("encoding/json";"os";"fmt";"github.com/Tencent/WeKnora/internal/infrastructure/docparser";"github.com/Tencent/WeKnora/internal/types")
type Case struct{Name string `json:"name"`; Connected bool `json:"connected"`; Overrides map[string]string `json:"overrides"`; Remote []types.ParserEngineInfo `json:"remote"`; Expected []types.ParserEngineInfo `json:"expected"`}
func main(){
 variants:=[][]types.ParserEngineInfo{nil,{},{{Name:"builtin",Description:"remote builtin",FileTypes:[]string{"pdf"},Available:false,UnavailableReason:"remote-only reason"}},{{Name:"builtin",Description:"first",FileTypes:[]string{"first"}},{Name:"builtin",Description:"",FileTypes:[]string{}}},{{Name:"remote",Description:"first",FileTypes:nil},{Name:"remote",Description:"second",FileTypes:[]string{},Available:true}},{{Name:"simple",Description:" ",FileTypes:[]string{""}},{Name:"anydoc",Description:"anydoc remote",Available:true},{Name:"",Description:"unnamed",FileTypes:[]string{"txt"},UnavailableReason:"why"}}}
 overrides:=[]map[string]string{nil,{}, {"weknoracloud_app_id":" "},{"weknoracloud_app_id":"id"},{"paddleocr_vl_cloud_token":" token "},{"paddleocr_vl_cloud_token":"\u00a0\u3000"},{"mineru_endpoint":"\u00a0\u3000"},{"mineru_api_key":"\u00a0\u3000"},{"paddleocr_vl_endpoint":"\u00a0\u3000"}}
 out:=[]Case{};for _,connected:=range []bool{false,true}{for i,remote:=range variants{for j,config:=range overrides{out=append(out,Case{fmt.Sprintf("%v-%d-%d",connected,i,j),connected,config,remote,docparser.ListAllEngines(connected,config,remote)})}}};if err:=json.NewEncoder(os.Stdout).Encode(out);err!=nil{panic(err)}
}
