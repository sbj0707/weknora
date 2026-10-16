//go:build ignore

package main

import (
	"encoding/json"
	"fmt"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/utils"
	"os"
)

func main() {
	out := []any{}
	key := "0123456789abcdef0123456789abcdef"
	encrypted, e := utils.EncryptAESGCM("synthetic-secret", []byte(key))
	if e != nil {
		panic(e)
	}
	raws := []string{`{}`, `null`, `[]`, `1`, `true`, `{"addr":"http://fixture.invalid","username":"demo","password":"legacy"}`, `{"PORT":6334,"use_tls":true,"number_of_shards":2}`, `{"password":"x","password":null}`, `{"password":1,"password":"x"}`, `{"port":1.0}`, `{"port":9223372036854775807}`, `{"port":9223372036854775808}`, `{"use_tls":"true"}`, `{"unknown":[1,2]}`, `{"addr":"a"}{}`, `{"addr":"\ud800"}`, `{"password":"enc:v1:broken"}`, `{"password":"` + encrypted + `"}`, `{"api_key":"` + encrypted + `"}`}
	for i, raw := range raws {
		for _, storage := range []string{"blob", "text", "null"} {
			for _, keyMode := range []string{"configured", "missing", "wrong"} {
				if keyMode != "configured" && i < 17 {
					continue
				}
				aes := key
				if keyMode == "missing" {
					aes = ""
				}
				if keyMode == "wrong" {
					aes = "abcdef0123456789abcdef0123456789ab"
				}
				os.Setenv("SYSTEM_AES_KEY", aes)
				var v any = []byte(raw)
				if storage == "text" {
					v = raw
				}
				if storage == "null" {
					v = nil
				}
				var cc types.ConnectionConfig
				err := cc.Scan(v)
				var cfg types.IndexConfig
				ixerr := cfg.Scan(v)
				out = append(out, map[string]any{"name": fmt.Sprintf("%d/%s/%s", i, storage, keyMode), "raw": raw, "storage": storage, "key": aes, "connection_error": err != nil, "index_error": ixerr != nil, "connection": cc, "index": cfg})
			}
		}
	}
	data, e := json.Marshal(out)
	if e != nil {
		panic(e)
	}
	if e = os.WriteFile(os.Args[1], append(data, '\n'), 0600); e != nil {
		panic(e)
	}
	fmt.Println(len(out))
}
