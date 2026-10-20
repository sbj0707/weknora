package pageimage

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"time"
)

//go:embed render_pdf.py
var rendererScript string

type Renderer interface {
	Count(context.Context,string)(int,error)
	Render(context.Context,string,int,string)(int,int,error)
}

type PDFRenderer struct{}

func (PDFRenderer) run(ctx context.Context,args ...string)(map[string]int,error){
	ctx,cancel:=context.WithTimeout(ctx,90*time.Second);defer cancel()
	python:=os.Getenv("PAGE_IMAGE_PYTHON");if python==""{python="python3"}
	cmd:=exec.CommandContext(ctx,python,append([]string{"-c",rendererScript},args...)...)
	var stdout,stderr bytes.Buffer
	cmd.Stdout=&stdout;cmd.Stderr=&stderr
	if err:=cmd.Run();err!=nil{
		// Do not return native parser stderr or file paths to clients.
		if ctx.Err()!=nil{return nil,fmt.Errorf("page renderer timed out")}
		return nil,fmt.Errorf("page renderer failed; install pypdfium2 and Pillow for %s and check that the PDF is readable",python)
	}
	var result map[string]int
	if err:=json.Unmarshal(stdout.Bytes(),&result);err!=nil{return nil,fmt.Errorf("invalid page renderer output")}
	return result,nil
}
func (r PDFRenderer) Count(ctx context.Context,path string)(int,error){m,e:=r.run(ctx,path);return m["pages"],e}
func (r PDFRenderer) Render(ctx context.Context,path string,page int,out string)(int,int,error){m,e:=r.run(ctx,path,fmt.Sprint(page),out);return m["width"],m["height"],e}
