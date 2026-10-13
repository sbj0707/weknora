//go:build ignore

package main
import("bytes";"encoding/base64";"encoding/json";"fmt";"io";"math/rand";"strings")
func main(){
 var bodies [][]byte
 add:=func(s string){bodies=append(bodies,[]byte(s))}
 for _,s:=range []string{""," \n\t", "{}trailing","[]{}",`"hi"trailing`,"0trailing","01","1e+","-","true!","false","null",`{"a":1,"a":2}`,strings.Repeat("[",10000)+"0"+strings.Repeat("]",10000),strings.Repeat("[",10001)+"0"+strings.Repeat("]",10001)}{add(s)}
 for i:=0;i<256;i++{bodies=append(bodies,[]byte{byte(i)},[]byte{'"',byte(i),'"'},[]byte{'"','\\',byte(i),'"'},[]byte{'"','\\','u',byte(i),'0','0','0','"'})}
 seeds:=[]string{`{"text":"你好😀","unknown":[true,false,null,{"a":-1.23e+4}]}`,`{"chunking_config":{"chunk_size":50,"languages":["en","zh"]}}`, `[0,-3,2.1,3e+5,"x\n\u0034",{},[],true]`, `true`, `null`, `false`}
 rng:=rand.New(rand.NewSource(517));for i:=0;i<6000;i++{body:=[]byte(seeds[i%len(seeds)]);at:=rng.Intn(len(body));switch i%3{case 0:body[at]=byte(rng.Intn(256));case 1:body=append(body[:at],body[at+1:]...);case 2:body=append(append(append([]byte(nil),body[:at]...),byte(rng.Intn(256))),body[at:]...)};bodies=append(bodies,body)}
 for _,body:=range bodies{reader:=bytes.NewReader(body);dec:=json.NewDecoder(reader);var raw json.RawMessage;err:=dec.Decode(&raw);failure:="";length:=0;if err!=nil{failure=err.Error()}else{left,_:=io.ReadAll(dec.Buffered());length=len(body)-len(left)-reader.Len()};fmt.Printf("%s\t%s\t%d\n",base64.StdEncoding.EncodeToString(body),base64.StdEncoding.EncodeToString([]byte(failure)),length)}
}
