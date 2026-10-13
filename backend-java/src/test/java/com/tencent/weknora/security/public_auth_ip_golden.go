//go:build ignore

// Go Gin ClientIP and production shared public-auth middleware oracle.
package main

import (
	"encoding/json"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/gin-gonic/gin"
	"net/http/httptest"
	"os"
	"strings"
)

func main() {
	gin.SetMode(gin.TestMode)
	rows := []map[string]any{}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "203.0.113.1:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.1")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "public-peer-spoof", "remote": "203.0.113.1", "proxies": nil, "xff": []string{"198.51.100.1"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "10.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.2")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "private-peer", "remote": "10.0.0.1", "proxies": nil, "xff": []string{"198.51.100.2"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", "10.0.0.1, 192.168.0.2")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "alltrusted", "remote": "127.0.0.1", "proxies": nil, "xff": []string{"10.0.0.1, 192.168.0.2"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", "broken, 198.51.100.3")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "right-untrusted", "remote": "127.0.0.1", "proxies": nil, "xff": []string{"broken, 198.51.100.3"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.4, broken")
		q.Header.Add("X-Real-IP", "198.51.100.5")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "right-invalid-fallback", "remote": "127.0.0.1", "proxies": nil, "xff": []string{"198.51.100.4, broken"}, "real": []string{"198.51.100.5"}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.6")
		q.Header.Add("X-Forwarded-For", "10.0.0.2")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "multi-values", "remote": "127.0.0.1", "proxies": nil, "xff": []string{"198.51.100.6", "10.0.0.2"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.7")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "disabled", "remote": "127.0.0.1", "proxies": []string{}, "xff": []string{"198.51.100.7"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"203.0.113.2"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "203.0.113.2:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.8")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "explicit-public-trusted", "remote": "203.0.113.2", "proxies": []string{"203.0.113.2"}, "xff": []string{"198.51.100.8"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[::1]:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.9")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "loopback6", "remote": "::1", "proxies": nil, "xff": []string{"198.51.100.9"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[fd00::a]:1234"
		q.Header.Add("X-Forwarded-For", "2001:db8::a")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "unique6", "remote": "fd00::a", "proxies": nil, "xff": []string{"2001:db8::a"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[2001:db8:0:0:0:0:0:1]:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.10")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "public6", "remote": "2001:db8:0:0:0:0:0:1", "proxies": nil, "xff": []string{"198.51.100.10"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "hostname:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.11")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "invalidremote", "remote": "hostname", "proxies": nil, "xff": []string{"198.51.100.11"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", "001.2.3.4")
		q.Header.Add("X-Real-IP", "198.51.100.12")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "invalidXFF", "remote": "127.0.0.1", "proxies": nil, "xff": []string{"001.2.3.4"}, "real": []string{"198.51.100.12"}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[::ffff:127.0.0.1]:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.13")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "mapped", "remote": "::ffff:127.0.0.1", "proxies": nil, "xff": []string{"198.51.100.13"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Real-IP", "198.51.100.14")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "emptyXFF", "remote": "127.0.0.1", "proxies": nil, "xff": []string{}, "real": []string{"198.51.100.14"}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"::ffff:127.0.0.0/104"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[::ffff:127.0.0.1]:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.31")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "mapped-cidr", "remote": "::ffff:127.0.0.1", "proxies": []string{"::ffff:127.0.0.0/104"}, "xff": []string{"198.51.100.31"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"::ffff:127.0.0.1"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[::ffff:127.0.0.1]:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.32")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "mapped-single", "remote": "::ffff:127.0.0.1", "proxies": []string{"::ffff:127.0.0.1"}, "xff": []string{"198.51.100.32"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"::ffff:127.0.0.0/104"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[::ffff:128.0.0.1]:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.33")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "mapped-untrusted", "remote": "::ffff:128.0.0.1", "proxies": []string{"::ffff:127.0.0.0/104"}, "xff": []string{"198.51.100.33"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"::ffff:127.0.0.0/104"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", "::ffff:127.0.0.2")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "mapped-header-raw", "remote": "127.0.0.1", "proxies": []string{"::ffff:127.0.0.0/104"}, "xff": []string{"::ffff:127.0.0.2"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[::ffff:001.2.3.4]:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.34")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "ipv6-embedded-leading-zero", "remote": "::ffff:001.2.3.4", "proxies": nil, "xff": []string{"198.51.100.34"}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", "::ffff:001.2.3.4")
		q.Header.Add("X-Real-IP", "198.51.100.35")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "bad-mapped-header", "remote": "127.0.0.1", "proxies": nil, "xff": []string{"::ffff:001.2.3.4"}, "real": []string{"198.51.100.35"}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[2001:db8:0:0:1:0:0:1]:1234"
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "ipv6-zero-run-tie", "remote": "2001:db8:0:0:1:0:0:1", "proxies": nil, "xff": []string{}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[::]:1234"
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "ipv6-unspecified", "remote": "::", "proxies": nil, "xff": []string{}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "127.0.0.1:1234"
		q.Header.Add("X-Forwarded-For", " 198.51.100.36 ")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "unicode-header-space", "remote": "127.0.0.1", "proxies": nil, "xff": []string{" 198.51.100.36 "}, "real": []string{}, "status": w.Code, "body": body})
	}
	{
		r := gin.New()
		r.SetTrustedProxies([]string{"::ffff:127.0.0.0/96"})
		r.Use(middleware.ErrorHandler())
		r.POST("/lookup", middleware.PublicAuthRateLimit(), func(c *gin.Context) { c.JSON(200, gin.H{"ip": c.ClientIP()}) })
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", strings.NewReader(""))
		q.RemoteAddr = "[::ffff:127.0.0.1]:1234"
		q.Header.Add("X-Forwarded-For", "198.51.100.37")
		r.ServeHTTP(w, q)
		var body any
		json.Unmarshal(w.Body.Bytes(), &body)
		rows = append(rows, map[string]any{"name": "full-ipv6-cidr", "remote": "::ffff:127.0.0.1", "proxies": []string{"::ffff:127.0.0.0/96"}, "xff": []string{"198.51.100.37"}, "real": []string{}, "status": w.Code, "body": body})
	}
	json.NewEncoder(os.Stdout).Encode(rows)
}
