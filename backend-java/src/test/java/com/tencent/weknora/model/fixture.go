//go:build ignore

// Run from repository root: go run backend-java/src/test/java/com/tencent/weknora/model/fixture.go
// Fixture responses use the actual production Go response DTO and secret scanner.
package main

import (
    "context"
    "encoding/json"
    "fmt"
    "os"
    "time"
    "github.com/Tencent/WeKnora/internal/handler/dto"
    "github.com/Tencent/WeKnora/internal/types"
    "github.com/Tencent/WeKnora/internal/utils"
)

func main() {
    cases := []map[string]any{}
    inputs := []string{
        `{}`,
        `{"base_url":"https://private.invalid/v1","api_key":"plaintext-api","app_secret":"plaintext-app","app_id":"account","provider":"volcengine","interface_type":"openai","parameter_size":"7B","embedding_parameters":{"dimension":1024,"truncate_prompt_tokens":4096,"supports_dimension_override":true},"supports_vision":true,"context_window":8192,"max_output_tokens":2048,"max_concurrency":2,"extra_config":{"secret_key":"private-sk","region":"cn-beijing"},"custom_headers":{"X-Routing":"internal"},"spec":{"api":"openai-completions","reasoning":false,"input":["text"],"context_window":4096,"thinking_levels":{"low":null},"compat":{"extra_body":{"route":"internal"}}}}`,
        `{"provider":"openai","extra_config":{"secret_key":"leftover-credential","region":"cn"},"api_key":"enc:v1:invalid-ciphertext","app_secret":""}`,
        `{"provider":"","base_url":"https://lkeap.tencentcloudapi.com","extra_config":{"region":"ap-beijing"},"api_key":"","spec":{}}`,
    }
    for _, input := range inputs {
        for _, role := range []types.TenantRole{types.TenantRoleViewer,types.TenantRoleContributor,types.TenantRoleAdmin,types.TenantRoleOwner} {
            for _, builtin := range []bool{false,true} {
                for _, system := range []bool{false,true} {
                    var m types.Model
                    m.ID="fixture";m.TenantID=2;m.Name="embedding";m.DisplayName="Fixture";m.Type=types.ModelTypeEmbedding;m.Source=types.ModelSourceRemote;m.IsBuiltin=builtin;m.Status=types.ModelStatusActive;m.ManagedBy="yaml"
                    m.CreatedAt=time.Date(2026,10,1,1,2,3,123456000,time.UTC);m.UpdatedAt=m.CreatedAt
                    if err:=m.Parameters.Scan([]byte(input));err!=nil {panic(err)}
                    ctx:=context.WithValue(context.Background(),types.TenantRoleContextKey,role)
                    ctx=context.WithValue(ctx,types.SystemAdminContextKey,system)
                    cases=append(cases,map[string]any{"parameters_db":input,"model":m,"role":role,"system_admin":system,"aes_key":"","response":dto.NewModelResponse(ctx,&m)})
                }
            }
        }
    }
    chatInputs:=[]struct{name string;kind types.ModelType;params string}{
        {"gpt-5",types.ModelTypeKnowledgeQA,`{"provider":"openai","api_key":"not-returned"}`},
        {"gpt-5",types.ModelTypeVLLM,`{"provider":"openai","base_url":"https://relay.invalid/v1","supports_vision":true}`},
        {"qwen3.8-future",types.ModelTypeKnowledgeQA,`{"provider":"aliyun","extra_config":{"thinking_control":"enable_thinking"}}`},
        {"kimi-k3",types.ModelTypeKnowledgeQA,`{"provider":"moonshot","spec":{"reasoning":true,"context_window":12345,"thinking_levels":{"off":null,"max":"max"}}}`},
        {"gemini-3-pro",types.ModelTypeVLLM,`{"provider":"gemini","base_url":"https://relay.invalid/openai/v1"}`},
        {"private-model",types.ModelTypeKnowledgeQA,`{"provider":"not-registered","spec":{"api":"anthropic-messages","reasoning":true,"compat":{"thinking_mode":"adaptive"}}}`},
        {"model-with-invalid-spec",types.ModelTypeKnowledgeQA,`{"provider":"generic","spec":{"api":"bad-api"}}`},
        {"model-with-invalid-compat",types.ModelTypeKnowledgeQA,`{"provider":"generic","spec":{"compat":{"typo":true}}}`},
    }
    for _,input:=range chatInputs {for _,role:=range []types.TenantRole{types.TenantRoleViewer,types.TenantRoleAdmin} {for _,builtin:=range []bool{false,true} {for _,system:=range []bool{false,true} {
        m:=types.Model{ID:"chat-fixture",TenantID:2,Name:input.name,Type:input.kind,Source:types.ModelSourceRemote,IsBuiltin:builtin,Status:types.ModelStatusActive}
        if err:=m.Parameters.Scan([]byte(input.params));err!=nil{panic(err)}
        ctx:=context.WithValue(context.Background(),types.TenantRoleContextKey,role);ctx=context.WithValue(ctx,types.SystemAdminContextKey,system)
        cases=append(cases,map[string]any{"parameters_db":input.params,"model":m,"role":role,"system_admin":system,"aes_key":"","response":dto.NewModelResponse(ctx,&m)})
    }}}}
    key:="0123456789abcdef0123456789abcdef"
    encrypted,err:=utils.EncryptAESGCM("encrypted-secret",[]byte(key));if err!=nil{panic(err)}
    raw,_:=json.Marshal(map[string]string{"api_key":encrypted,"app_secret":encrypted})
    for _, k:=range []string{"",key,"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"} {
        os.Setenv("SYSTEM_AES_KEY",k)
        m:=types.Model{ID:"encrypted",TenantID:1,Type:types.ModelTypeEmbedding,Source:types.ModelSourceRemote,Status:types.ModelStatusActive}
        if err:=m.Parameters.Scan(raw);err!=nil{panic(err)}
        ctx:=context.WithValue(context.Background(),types.TenantRoleContextKey,types.TenantRoleAdmin)
        cases=append(cases,map[string]any{"parameters_db":string(raw),"model":m,"role":"admin","system_admin":false,"aes_key":k,"response":dto.NewModelResponse(ctx,&m)})
    }
    data,err:=json.MarshalIndent(cases,"","  ");if err!=nil{panic(err)}
    fmt.Println(string(data))
}
