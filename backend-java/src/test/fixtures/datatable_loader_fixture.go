//go:build ignore
package main
import (
 "context"
 "database/sql"
 "encoding/base64"
 "encoding/json"
 "fmt"
 "os"
 "path/filepath"
 "github.com/Tencent/WeKnora/internal/agent/tools"
 "github.com/Tencent/WeKnora/internal/application/service"
 filesvc "github.com/Tencent/WeKnora/internal/application/service/file"
 "github.com/Tencent/WeKnora/internal/types"
 "github.com/Tencent/WeKnora/internal/types/interfaces"
 _ "github.com/duckdb/duckdb-go/v2"
 "github.com/xuri/excelize/v2"
)
type lookup struct{interfaces.KnowledgeService;k *types.Knowledge}
func(l lookup)GetKnowledgeByIDOnly(context.Context,string)(*types.Knowledge,error){return l.k,nil}
func must(err error){if err!=nil{panic(err)}}
func main(){
 folder,err:=os.MkdirTemp("","weknora-datatable-go-");must(err);defer os.RemoveAll(folder)
 out:=[]map[string]any{}
 csvs:=[]string{"id,amount\n001,3.1400\n002,\n", "name,note\n\"a,b\",\"x\ny\"\nO'Brien,<>&\n", "\ufeff列,空值\r\n中文,\r\n😀,null\r\n", "a,a,A\n1,2,3\n", "id\n", "1,2\n3,4\n"}
 for n:=0;n<10;n++{
  name:=fmt.Sprintf("case-%d",n);kind:="csv";var data []byte
  if n<len(csvs){data=[]byte(csvs[n])}else{
   kind="xlsx";f:=excelize.NewFile();must(f.SetSheetName("Sheet1","O'Brien"))
   must(f.SetSheetRow("O'Brien","A1",&[]any{"id","amount"}));must(f.SetSheetRow("O'Brien","A2",&[]any{"001",3.14}));must(f.SetSheetRow("O'Brien","A3",&[]any{"002",nil}))
   if n>=7{_,err=f.NewSheet("表二");must(err);must(f.SetSheetRow("表二","A1",&[]any{"id","note"}));must(f.SetSheetRow("表二","A2",&[]any{"003","<>&中文"}))}
   if n==8{style,er:=f.NewStyle(&excelize.Style{CustomNumFmt:ptr("0.0000")});must(er);must(f.SetCellStyle("O'Brien","B2","B2",style))}
   if n==9{must(f.SetCellStr("表二","B3","line1\nline2"));must(f.SetCellStr("表二","A3","004"))}
   buf,er:=f.WriteToBuffer();must(er);data=buf.Bytes();must(f.Close())
  }
  filename:=filepath.Join(folder,"data'file."+kind);must(os.WriteFile(filename,data,0600))
  k:=&types.Knowledge{ID:"table-123",TenantID:1,FilePath:filename,FileType:kind}
  db,er:=sql.Open("duckdb",":memory:");must(er)
  for _,ext:=range []string{"spatial","excel"}{_,er=db.Exec("LOAD "+ext);must(er)}
  tool:=tools.NewDataAnalysisTool(nil,lookup{k:k},nil,filesvc.NewLocalFileService(folder,""),db,"table_summary_"+k.ID)
  schema,er:=tool.LoadFromKnowledge(context.Background(),k);must(er)
  args,_:=json.Marshal(map[string]any{"knowledge_id":k.ID,"sql":"SELECT * FROM \"dataset\" LIMIT 10"})
  sample,er:=tool.Execute(context.Background(),args);must(er)
  row:=map[string]any{"name":name,"file_type":kind,"input_base64":base64.StdEncoding.EncodeToString(data),"schema":schema,"description":schema.Description(),"rows":sample.Data["rows"],"sample":service.MigrationTableSample(sample)}
  tool.Cleanup(context.Background());var remain int;must(db.QueryRow("SELECT count(*) FROM duckdb_tables() WHERE table_name=?",schema.TableName).Scan(&remain));row["tables_after_cleanup"]=remain
  _,er=os.Stat(filename);row["source_preserved"]=er==nil;must(db.Close());out=append(out,row)
 }
 must(json.NewEncoder(os.Stdout).Encode(out))
}
func ptr(s string)*string{return &s}
