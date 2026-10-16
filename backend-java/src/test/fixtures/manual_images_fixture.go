//go:build ignore

// Calls the actual Go manual-image parser. HTTP is real; physical saving is a
// recording FileService seam, separately verified by the native pipeline tests.
package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Tencent/WeKnora/internal/infrastructure/docparser"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	utils "github.com/Tencent/WeKnora/internal/utils"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
)

type store struct {
	interfaces.FileService
	saves []any
	fail  bool
}

func (s *store) SaveBytes(ctx context.Context, b []byte, owner uint64, name string, temp bool) (string, error) {
	hash := sha256.Sum256(b)
	s.saves = append(s.saves, map[string]any{"sha256": fmt.Sprintf("%x", hash), "length": len(b), "owner": owner, "extension": filepath.Ext(name), "temporary": temp})
	if s.fail {
		return "", errors.New("controlled physical save failure")
	}
	return fmt.Sprintf("resource://%022d", len(s.saves)), nil
}

type test struct {
	name, markdown, keep, ctype string
	body                        []byte
	status                      int
	fail                        bool
}

func picture(w, h int, kind string) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(x*31 + y*17), uint8(x + y*9), uint8(x*7 + y), 255})
		}
	}
	var b bytes.Buffer
	switch kind {
	case "jpeg":
		jpeg.Encode(&b, img, nil)
	case "gif":
		gif.Encode(&b, img, nil)
	default:
		png.Encode(&b, img)
	}
	return b.Bytes()
}
func main() {
	os.Setenv("SSRF_WHITELIST", "127.0.0.1")
	utils.ResetSSRFWhitelistForTest()
	large := picture(96, 80, "png")
	icon := picture(63, 63, "png")
	wide := picture(64, 1, "png")
	noise := bytes.Repeat([]byte("x"), 600)
	data := func(b []byte, mime string) string {
		return "![inline](data:" + mime + ";base64," + base64.StdEncoding.EncodeToString(b) + ")"
	}
	remote := "![remote]({server}/image.png)"
	cases := []test{
		{name: "plain", markdown: "# Manual text"},
		{name: "data-png", markdown: data(large, "image/png")},
		{name: "data-jpeg", markdown: data(picture(80, 80, "jpeg"), "image/jpeg")},
		{name: "data-gif", markdown: data(picture(80, 80, "gif"), "image/gif")},
		{name: "data-icon", markdown: "a" + data(icon, "image/png") + "b"},
		{name: "data-wide", markdown: data(wide, "image/png")},
		{name: "data-both-small", markdown: data(picture(1, 64, "png"), "image/png")},
		{name: "data-unrecognized-large", markdown: data(noise, "image/unknown")},
		{name: "data-small-fallback", markdown: data([]byte("tiny"), "image/svg+xml")},
		{name: "data-uppercase", markdown: strings.ReplaceAll(data(large, "image/png"), "data:image/png", "DATA:IMAGE/PNG")},
		{name: "data-unpadded", markdown: strings.ReplaceAll(data(large, "image/png"), "=", "")},
		{name: "data-url-base64", markdown: strings.ReplaceAll(strings.ReplaceAll(data(noise, "image/png"), "+", "-"), "/", "_")},
		{name: "data-invalid-base64", markdown: "![bad](data:image/png;base64,invalid!)"},
		{name: "data-spaces", markdown: strings.ReplaceAll(data(large, "image/png"), "base64,", "base64, \n\t")},
		{name: "data-save-error", markdown: data(large, "image/png"), fail: true},
		{name: "data-reverse", markdown: data(large, "image/png") + "\n" + data(noise, "image/jpeg")},
		{name: "data-budget", markdown: strings.Repeat(data(large, "image/png")+"\n", 32)},
		{name: "linked-data", markdown: "[" + data(large, "image/png") + "](https://destination.example)"},
		{name: "html-data-not-manual", markdown: "<img src='data:image/png;base64," + base64.StdEncoding.EncodeToString(large) + "'>"},
		{name: "remote", markdown: remote, ctype: "image/png", body: large},
		{name: "remote-icon", markdown: remote, ctype: "image/png", body: icon},
		{name: "remote-keep-host", markdown: remote, keep: "127.0.0.1", ctype: "image/png", body: large},
		{name: "remote-keep-authority", markdown: remote, keep: "{authority}", ctype: "image/png", body: large},
		{name: "remote-keep-unrelated", markdown: remote, keep: "cdn.example", ctype: "image/png", body: large},
		{name: "remote-uppercase-scheme", markdown: "![remote](HTTP://{authority}/image.png)", ctype: "image/png", body: large},
		{name: "remote-not-found", markdown: remote, status: 404, ctype: "image/png", body: large},
		{name: "remote-no-type", markdown: remote, body: large},
		{name: "remote-octet", markdown: remote, ctype: "application/octet-stream", body: large},
		{name: "remote-text", markdown: remote, ctype: "text/plain", body: large},
		{name: "remote-text-sniff", markdown: remote, ctype: "application/octet-stream", body: noise},
		{name: "remote-malformed-mime", markdown: remote, ctype: "image/png; charset", body: large},
		{name: "remote-duplicate-mime-param", markdown: remote, ctype: "image/png; x=a; x=b", body: large},
		{name: "remote-quoted-mime-param", markdown: remote, ctype: "IMAGE/PNG; x=\"a;b\"", body: large},
		{name: "remote-custom-mime", markdown: "![r]({server}/image.jpg)", ctype: "image/unknown", body: noise},
		{name: "remote-query-extension", markdown: "![r]({server}/image.jpg?x=1)", ctype: "image/unknown", body: noise},
		{name: "remote-save-error", markdown: remote, ctype: "image/png", body: large, fail: true},
		{name: "remote-budget", markdown: strings.Repeat(remote+"\n", 32), ctype: "image/png", body: large},
		{name: "remote-html", markdown: "<IMG alt='a' SRC=\"{server}/image.png\"/>", ctype: "image/png", body: large},
		{name: "remote-html-normalize", markdown: "<img src='  HTTP://{authority}/image.png?a=1&amp;b=2  '>", ctype: "image/png", body: large},
		{name: "remote-html-keep-normalize", markdown: "<img src='  HTTP://{authority}/image.png?a=1&amp;b=2  '>", keep: "127.0.0.1", ctype: "image/png", body: large},
		{name: "remote-html-unquoted", markdown: "<img src={server}/image.png>", ctype: "image/png", body: large},
		{name: "remote-independent-budgets", markdown: strings.Repeat(remote+"\n", 31) + strings.Repeat("<img src='{server}/html.png'>\n", 31), ctype: "image/png", body: large},
		{name: "linked-remote-parens", markdown: "[![linked]({server}/image(1).png)](https://target.example/(1))", ctype: "image/png", body: large},
		{name: "data-exceeds-size", markdown: data(bytes.Repeat([]byte("x"), 10*1024*1024+1), "image/png")},
		{name: "remote-exceeds-size", markdown: remote, ctype: "image/png", body: bytes.Repeat([]byte("x"), 10*1024*1024+1)},
		{name: "remote-webp-sniff-malformed", markdown: remote, ctype: "application/octet-stream", body: append([]byte("RIFFxxxxWEBP"), noise...)},
		{name: "remote-webp-sniff", markdown: remote, ctype: "application/octet-stream", body: append([]byte("RIFFxxxxWEBPVP8 "), noise...)},
	}
	cases = append(cases,
		test{name: "data-url-safe-valid", markdown: "![inline](data:image/png;base64," + base64.URLEncoding.EncodeToString(bytes.Repeat([]byte{251, 255, 254}, 200)) + ")"},
		test{name: "data-url-safe-raw", markdown: "![inline](data:image/png;base64," + base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{251, 255, 254}, 201)) + ")"},
		test{name: "remote-malformed-mime-noise", markdown: remote, ctype: "image/png; charset", body: noise},
		test{name: "remote-duplicate-mime-noise", markdown: remote, ctype: "image/png; x=a; x=b", body: noise},
		test{name: "remote-cur-sniff", markdown: remote, ctype: "application/octet-stream", body: append([]byte{0, 0, 2, 0}, noise...)},
		test{name: "remote-icon512-fallback", markdown: remote, ctype: "image/png", body: bytes.Repeat([]byte("x"), 512)},
		test{name: "remote-icon511-fallback", markdown: remote, ctype: "image/png", body: bytes.Repeat([]byte("x"), 511)},
		test{name: "manual-icon-only", markdown: data(icon, "image/png")},
		test{name: "manual-linked-no-image", markdown: "[![linked](relative.png)](https://example.org)"},
		test{name: "manual-icon-with-valid", markdown: data(icon, "image/png") + "\n" + data(large, "image/png")},
	)
	out := []any{}
	for _, c := range cases {
		requests := []any{}
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			requests = append(requests, map[string]any{"path": r.URL.RequestURI(), "user_agent": r.UserAgent()})
			if c.ctype != "" {
				w.Header().Set("Content-Type", c.ctype)
			}
			status := c.status
			if status == 0 {
				status = 200
			}
			w.WriteHeader(status)
			w.Write(c.body)
		}))
		authority := strings.TrimPrefix(server.URL, "http://")
		replace := func(s string) string {
			return strings.ReplaceAll(strings.ReplaceAll(s, "{server}", server.URL), "{authority}", authority)
		}
		s := &store{saves: []any{}, fail: c.fail}
		os.Setenv("IMAGE_HOST_KEEP_URL", replace(c.keep))
		resolver := docparser.NewImageResolver()
		initial := replace(c.markdown)
		markdown, images, _ := resolver.ResolveDataURIImages(context.Background(), initial, s, 8)
		manual := strings.HasPrefix(c.name, "manual-")
		if manual && len(images) == 0 {
			markdown = initial
		}
		beforeRemote := markdown
		markdown, remoteImages, _ := resolver.ResolveRemoteImages(context.Background(), markdown, s, 8)
		if manual && len(remoteImages) == 0 {
			markdown = beforeRemote
		}
		images = append(images, remoteImages...)
		imageDTO := []any{}
		for _, i := range images {
			imageDTO = append(imageDTO, map[string]any{"original": i.OriginalRef, "serving": i.ServingURL, "mime": i.MimeType})
		}
		normalize := func(s string) string {
			return strings.ReplaceAll(strings.ReplaceAll(s, server.URL, "{server}"), authority, "{authority}")
		}
		markdown = normalize(markdown)
		for _, raw := range imageDTO {
			m := raw.(map[string]any)
			m["original"] = normalize(m["original"].(string))
			m["serving"] = normalize(m["serving"].(string))
		}
		out = append(out, map[string]any{"name": c.name, "markdown": c.markdown, "keep": c.keep, "content_type": c.ctype, "body": base64.StdEncoding.EncodeToString(c.body), "status": c.status, "save_error": c.fail, "result": map[string]any{"markdown": markdown, "images": imageDTO, "saves": s.saves, "requests": requests}})
		server.Close()
	}
	json.NewEncoder(os.Stdout).Encode(out)
}
