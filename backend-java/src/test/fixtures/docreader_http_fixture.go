//go:build ignore

package main
import (
 "context"
 "encoding/base64"
 "encoding/json"
 "fmt"
 "net/http"
 "net/http/httptest"
 "os"
 "github.com/Tencent/WeKnora/internal/infrastructure/docparser"
 "github.com/Tencent/WeKnora/internal/types"
 sec "github.com/Tencent/WeKnora/internal/utils"
)
type fixture struct { Name string `json:"name"`; Operation string `json:"operation"`; Status int `json:"status"`; Body string `json:"body"`; BodyBase64 string `json:"body_base64"`; Request *types.ReadRequest `json:"request,omitempty"`; Overrides map[string]string `json:"overrides,omitempty"`; Captured json.RawMessage `json:"captured"`; Result interface{} `json:"result,omitempty"`; Error string `json:"error,omitempty"` }
func main(){
 sec.SetSSRFWhitelistFromRaw("127.0.0.1")
 cases:=[]fixture{}
 read:=func(name,body string){cases=append(cases,fixture{Name:name,Operation:"read",Status:200,Body:body,Request:&types.ReadRequest{}})}
 read("empty","{}");read("null","null");read("full",`{"markdown_content":"中😀<&>","image_dir_path":"/images","metadata":{"key":"value","empty":""},"error":"parser-error-as-data","image_refs":[{"filename":"图.png","original_ref":"a","mime_type":"image/png","storage_key":"local/x","image_data":"AAH/"}],"source_blocks":[{"start":0,"end":2,"locator_json":"{\"type\":\"pdf\",\"page\":2,\"bbox\":[0,1,2,3]}"},{"start":2,"end":2,"locator_json":"{}"},{"start":3,"end":4,"locator_json":"{\"type\":true}"}]}`)
 read("dups-nulls",`{"markdown_content":"retain","MARKDOWN_CONTENT":null,"metadata":{"a":"1"},"metadata":{"b":"2","a":null},"image_refs":[null,{}],"source_blocks":[null],"image_dir_path":"d","image_dir_path":null}`)
 read("clears",`{"metadata":{"x":"1"},"metadata":null,"image_refs":[{}],"image_refs":null,"source_blocks":[{}],"source_blocks":null}`)
 read("surrogates",`{"markdown_content":"\ud800-\udc00-\ud800\udc00","metadata":{"\ud800":"\udc00"}}`)
 read("invalid-utf8",string(append([]byte(`{"markdown_content":"`),append([]byte{0xff,0xfe},[]byte(`"}`)...)...)))
 read("trailing",`{"markdown_content":"first"} garbage`)
 read("unknown",`{"other":[{"n":1e10000}],"markdown_content":"known"}`)
 for _,body:=range []string{"", " ","{",`{"markdown_content":`,"[",`{bad}`,`{"x":01}`,`{"x":"\q"}`,"true","1","[]","\ufeff{}"}{read(fmt.Sprintf("syntax-%d",len(cases)),body)}
 for _,field:=range []string{"markdown_content","image_dir_path","error","metadata","image_refs","source_blocks"}{for _,value:=range []string{"true","3","[]","{}","\"str\""}{read(fmt.Sprintf("type-%s-%s",field,value),`{"`+field+`":`+value+`}`)}}
 for _,field:=range []string{"filename","original_ref","mime_type","storage_key","image_data"}{for _,value:=range []string{"true","3","{}","[]"}{read(fmt.Sprintf("image-type-%s-%s",field,value),`{"image_refs":[{"`+field+`":`+value+`}]}`)}}
 for _,value:=range []string{"0","4294967295","4294967296","-1","-0","1.0","true","\"1\"","null"}{read("block-start-"+value,`{"source_blocks":[{"start":`+value+`,"end":4294967295,"locator_json":"{\"type\":\"pdf\"}"}]}`)}
 for _,value:=range []string{`"AA=="`,`"AB=="`,`"AAA="`,`"AAB="`,`"AAAA"`,`"A"`,`"AA"`,`"AAA"`,`"?AAA"`,`"AA=A"`,`"AA="`,`"=AAA"`,`"AA==="`,`"AAA=="`,`"AAAA="`,`"AA==AA=="`,`"AA\r\n=="`,`"AA==\r\n"`,`"AA😀"`,`[0,255,null]`,`[-1]`,`[-0]`,`[256]`,`[1.0]`,`["1"]`}{read("base64-"+value,`{"image_refs":[{"image_data":`+value+`}]}`)}
 full:=&types.ReadRequest{FileContent:[]byte{0,1,255},FileName:"中😀.pdf",FileType:"pdf",URL:"https://source.example/read",Title:"title",ParserEngine:"mineru",RequestID:"req-1",ParserEngineOverrides:map[string]string{"endpoint":"https://engine.example","empty":""}}
 cases=append(cases,fixture{Name:"full-request",Operation:"read",Status:200,Body:"{}",Request:full})
 read("duplicate-image-reset",`{"image_refs":[{"filename":"old"}],"image_refs":[],"image_refs":[null]}`)
 read("duplicate-image-null-reset",`{"image_refs":[{"filename":"old"}],"image_refs":null,"image_refs":[{}]}`)
 read("duplicate-byte-fields",`{"image_refs":[{"image_data":[1,2,3],"image_data":[null],"image_data":[null,null,null]}]}`)
 read("duplicate-byte-through-struct",`{"image_refs":[{"image_data":[1,2,3]}],"image_refs":[{"image_data":[null]}],"image_refs":[{"image_data":[null,null,null]}]}`)
 cases=append(cases,fixture{Name:"duplicate-string-fields",Operation:"list-engines",Status:200,Body:`{"engines":[{"file_types":["pdf","txt","csv"],"file_types":[null],"file_types":[null,null,null]}]}`})
 read("duplicate-image-slices",`{"image_refs":[{"filename":"retain","image_data":"AQ=="},{"filename":"tail"}],"image_refs":[{}],"image_refs":[null,{}]}`)
 read("duplicate-block-slices",`{"source_blocks":[{"start":2,"end":5,"locator_json":"{\"type\":\"pdf\"}"}],"source_blocks":[{}]}`)
 read("type-before-base64",`{"markdown_content":true,"image_refs":[{"image_data":"?"}]}`)
 cases=append(cases,fixture{Name:"duplicate-engine-slices",Operation:"list-engines",Status:200,Body:`{"engines":[{"name":"retain","available":true,"file_types":["pdf"]},{"name":"tail"}],"engines":[{}],"engines":[null,{}]}`})
 for _,body:=range []string{`{}`,`null`,`{"engines":[{"name":"builtin","description":"Desc","file_types":["pdf",null,""],"available":true,"unavailable_reason":"Reason"},null]}`,`{"engines":[{"name":"first","name":null,"available":true,"available":null,"file_types":["pdf"],"file_types":null}]}`,`{"engines":null}`,`{"engines":[{"available":1}]}`,`{"engines":[{"file_types":{}}]}`,`{"engines":[{"file_types":[3]}]}`,`{"engines":[{"name":true}]}`,`{"engines":[3]}`,`{"engines":{}}`,`[]`,`{"engines":[]}junk`}{cases=append(cases,fixture{Name:fmt.Sprintf("engines-%d",len(cases)),Operation:"list-engines",Status:200,Body:body,Overrides:map[string]string{"endpoint":"https://api.example","empty":""}})}
 for _,op:=range []string{"read","list-engines"}{for _,status:=range []int{201,204,400,503}{cases=append(cases,fixture{Name:fmt.Sprintf("status-%s-%d",op,status),Operation:op,Status:status,Body:"upstream diagnostic 中",Request:&types.ReadRequest{}})}}
 for i:=range cases { f:=&cases[i]; f.BodyBase64=base64.StdEncoding.EncodeToString([]byte(f.Body)); server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){defer r.Body.Close();var raw json.RawMessage;json.NewDecoder(r.Body).Decode(&raw);f.Captured=raw;w.WriteHeader(f.Status);if f.Status!=204{w.Write([]byte(f.Body))}}));client,err:=docparser.NewHTTPDocumentReader(server.URL+"/");if err!=nil{panic(err)}; if f.Operation=="read"{f.Result,err=client.Read(context.Background(),f.Request)}else{f.Result,err=client.ListEngines(context.Background(),f.Overrides)};if err!=nil{f.Error=err.Error();f.Result=nil};client.Close();server.Close() }
 data,err:=json.MarshalIndent(cases,"","  ");if err!=nil{panic(err)};if err=os.WriteFile(os.Args[1],append(data,'\n'),0644);err!=nil{panic(err)}
}
