//go:build ignore

// Generates request fixtures by executing production Go HTTP handlers and validators.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	repo "github.com/Tencent/WeKnora/internal/application/repository"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/config"
	"github.com/Tencent/WeKnora/internal/handler"
	"github.com/Tencent/WeKnora/internal/logger"
	"github.com/Tencent/WeKnora/internal/middleware"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"github.com/gin-gonic/gin"
	"io"
	"net/http/httptest"
	"os"
)

type users struct{ interfaces.UserService }

func (u users) GetUserByEmail(ctx context.Context, email string) (*types.User, error) {
	return nil, repo.ErrUserNotFound
}

type invitations struct {
	interfaces.TenantInvitationService
}

func (i invitations) LookupByToken(ctx context.Context, token string) (*types.TenantInvitation, error) {
	return nil, service.ErrInvitationTokenInvalid
}
func (i invitations) AcceptByToken(ctx context.Context, token, user string) (*types.TenantMember, error) {
	return nil, service.ErrInvitationTokenInvalid
}
func (i invitations) CreateShareLink(ctx context.Context, tenant uint64, role types.TenantRole, by *string, message string) (*types.TenantInvitation, string, error) {
	return nil, "", errors.New("oracle business boundary")
}
func main() {
	logger.SetOutput(io.Discard)
	gin.SetMode(gin.TestMode)
	i := invitations{}
	u := users{}
	h := handler.NewTenantInvitationHandler(i, u, nil, nil, nil, nil)
	a := handler.NewAuthHandler(&config.Config{Auth: &config.AuthConfig{RegistrationMode: "self_serve"}}, u, nil, nil, i)
	r := gin.New()
	r.Use(middleware.ErrorHandler())
	r.Use(func(c *gin.Context) {
		c.Request = c.Request.WithContext(context.WithValue(c.Request.Context(), types.UserIDContextKey, "caller"))
		c.Next()
	})
	r.POST("/create/:id", h.CreateInvitation)
	r.POST("/share/:id", h.CreateInviteLink)
	r.POST("/lookup", a.LookupInvitationByToken)
	r.POST("/register", a.RegisterByInvite)
	r.POST("/accept", h.AcceptMyInvitationByToken)
	rows := []map[string]any{}
	{
		raw := []byte("")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x20\x20\x0a\x09")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "spaces", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x6e\x75\x6c\x6c")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x5b\x5d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x74\x72\x75\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "bool", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x31\x32\x33")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x22\x61\x62\x63\x22")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "string", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "truncated", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x31\x2c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "syntax", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "valid", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d\x67\x61\x72\x62\x61\x67\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "trailing", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "second-value", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x45\x4d\x41\x49\x4c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x52\x4f\x4c\x45\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x4d\x45\x53\x53\x41\x47\x45\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "casefold", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x20\x22\x75\x6e\x6b\x6e\x6f\x77\x6e\x22\x3a\x20\x7b\x22\x69\x6e\x76\x61\x6c\x69\x64\x22\x3a\x20\x5b\x5d\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "unknown", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x6e\x75\x6c\x6c\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x31\x32\x33\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x7b\x7d\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x5b\x5d\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x66\x61\x6c\x73\x65\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x31\x2c\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x22\x65\x6d\x61\x69\x6c\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x6e\x75\x6c\x6c\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "role-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x31\x32\x33\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "role-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x7b\x7d\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "role-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x5b\x5d\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "role-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x66\x61\x6c\x73\x65\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "role-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "role-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x31\x2c\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "role-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x22\x72\x6f\x6c\x65\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "role-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "message-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x31\x32\x33\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "message-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x7b\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "message-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x5b\x5d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "message-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x66\x61\x6c\x73\x65\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "message-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "message-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x31\x2c\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "message-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "message-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x2e\x2e\x62\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-39", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x2e\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-40", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x2e\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-41", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-42", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x78\x2e\x63\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-43", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x31\x2e\x32\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-44", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x20\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-45", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x5c\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-46", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x75\x30\x30\x30\x31\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-47", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x6e\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-48", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x74\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-49", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x75\x37\x35\x32\x38\x5c\x75\x36\x32\x33\x37\x5c\x75\x35\x34\x30\x64\x40\x5c\x75\x34\x66\x38\x62\x5c\x75\x35\x62\x35\x30\x2e\x5c\x75\x34\x65\x32\x64\x5c\x75\x35\x36\x66\x64\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-50", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x66\x6f\x6f\x2e\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-51", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x66\x6f\x6f\x7e\x62\x61\x72\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-52", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x2d\x66\x6f\x6f\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-53", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x66\x6f\x6f\x2d\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-54", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x66\x6f\x6f\x2e\x63\x6f\x6d\x2e\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-55", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x6c\x6f\x63\x61\x6c\x68\x6f\x73\x74\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-56", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x20\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-57", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x20\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-58", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x65\x78\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-59", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x65\x78\x5c\x75\x30\x30\x61\x30\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-60", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x41\x20\x3c\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x3e\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-61", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x5c\x5c\x22\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "email-62", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x22\x65\x6d\x61\x69\x6c\x22\x3a\x22\x5c\x75\x64\x38\x30\x30\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "surrogate", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x22\x65\x6d\x61\x69\x6c\x22\x3a\x22\xff\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/create/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "CREATE", "name": "invalid-utf8", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x20\x20\x0a\x09")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "spaces", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x6e\x75\x6c\x6c")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x5b\x5d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x74\x72\x75\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "bool", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x31\x32\x33")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x22\x61\x62\x63\x22")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "string", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "truncated", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x31\x2c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "syntax", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "valid", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d\x67\x61\x72\x62\x61\x67\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "trailing", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "second-value", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x52\x4f\x4c\x45\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x4d\x45\x53\x53\x41\x47\x45\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "casefold", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x20\x22\x75\x6e\x6b\x6e\x6f\x77\x6e\x22\x3a\x20\x7b\x22\x69\x6e\x76\x61\x6c\x69\x64\x22\x3a\x20\x5b\x5d\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "unknown", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x6e\x75\x6c\x6c\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "role-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x31\x32\x33\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "role-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x7b\x7d\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "role-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x5b\x5d\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "role-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x66\x61\x6c\x73\x65\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "role-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "role-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x31\x2c\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "role-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x22\x72\x6f\x6c\x65\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "role-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "message-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x31\x32\x33\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "message-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x7b\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "message-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x5b\x5d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "message-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x66\x61\x6c\x73\x65\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "message-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "message-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x31\x2c\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "message-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "message-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x22\x72\x6f\x6c\x65\x22\x3a\x22\x5c\x75\x64\x38\x30\x30\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "surrogate", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x72\x6f\x6c\x65\x22\x3a\x20\x22\x61\x64\x6d\x69\x6e\x22\x2c\x20\x22\x6d\x65\x73\x73\x61\x67\x65\x22\x3a\x20\x22\x6e\x6f\x74\x65\x22\x2c\x22\x72\x6f\x6c\x65\x22\x3a\x22\xff\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/share/7", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "SHARE", "name": "invalid-utf8", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x20\x20\x0a\x09")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "spaces", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x6e\x75\x6c\x6c")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x5b\x5d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x74\x72\x75\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "bool", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x31\x32\x33")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x22\x61\x62\x63\x22")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "string", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "truncated", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x31\x2c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "syntax", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "valid", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d\x67\x61\x72\x62\x61\x67\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "trailing", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "second-value", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x54\x4f\x4b\x45\x4e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "casefold", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x75\x6e\x6b\x6e\x6f\x77\x6e\x22\x3a\x20\x7b\x22\x69\x6e\x76\x61\x6c\x69\x64\x22\x3a\x20\x5b\x5d\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "unknown", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "token-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x31\x32\x33\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "token-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x7b\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "token-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x5b\x5d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "token-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x66\x61\x6c\x73\x65\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "token-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "token-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x31\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "token-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "token-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x22\x5c\x75\x64\x38\x30\x30\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "surrogate", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x22\xff\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/lookup", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "LOOKUP", "name": "invalid-utf8", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x20\x20\x0a\x09")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "spaces", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x6e\x75\x6c\x6c")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x5b\x5d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x74\x72\x75\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "bool", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x31\x32\x33")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x22\x61\x62\x63\x22")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "string", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "truncated", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x31\x2c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "syntax", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "valid", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d\x67\x61\x72\x62\x61\x67\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "trailing", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "second-value", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x54\x4f\x4b\x45\x4e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x45\x4d\x41\x49\x4c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x55\x53\x45\x52\x4e\x41\x4d\x45\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x50\x41\x53\x53\x57\x4f\x52\x44\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "casefold", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x2c\x20\x22\x75\x6e\x6b\x6e\x6f\x77\x6e\x22\x3a\x20\x7b\x22\x69\x6e\x76\x61\x6c\x69\x64\x22\x3a\x20\x5b\x5d\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "unknown", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x6e\x75\x6c\x6c\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "token-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x31\x32\x33\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "token-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x7b\x7d\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "token-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x5b\x5d\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "token-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x66\x61\x6c\x73\x65\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "token-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "token-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x31\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "token-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "token-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x6e\x75\x6c\x6c\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x31\x32\x33\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x7b\x7d\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x5b\x5d\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x66\x61\x6c\x73\x65\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x65\x6d\x61\x69\x6c\x22\x3a\x31\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x2c\x22\x65\x6d\x61\x69\x6c\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x6e\x75\x6c\x6c\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "username-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x31\x32\x33\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "username-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x7b\x7d\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "username-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x5b\x5d\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "username-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x66\x61\x6c\x73\x65\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "username-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "username-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x31\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "username-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x2c\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "username-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x31\x32\x33\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x7b\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x5b\x5d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x66\x61\x6c\x73\x65\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x31\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x2c\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x2e\x2e\x62\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-170", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x2e\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-171", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x2e\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-172", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-173", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x78\x2e\x63\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-174", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x31\x2e\x32\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-175", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x20\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-176", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x5c\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-177", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x75\x30\x30\x30\x31\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-178", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x6e\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-179", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x74\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-180", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x75\x37\x35\x32\x38\x5c\x75\x36\x32\x33\x37\x5c\x75\x35\x34\x30\x64\x40\x5c\x75\x34\x66\x38\x62\x5c\x75\x35\x62\x35\x30\x2e\x5c\x75\x34\x65\x32\x64\x5c\x75\x35\x36\x66\x64\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-181", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x66\x6f\x6f\x2e\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-182", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x66\x6f\x6f\x7e\x62\x61\x72\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-183", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x2d\x66\x6f\x6f\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-184", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x66\x6f\x6f\x2d\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-185", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x66\x6f\x6f\x2e\x63\x6f\x6d\x2e\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-186", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x6c\x6f\x63\x61\x6c\x68\x6f\x73\x74\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-187", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x20\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-188", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x20\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-189", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x65\x78\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-190", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x61\x40\x65\x78\x5c\x75\x30\x30\x61\x30\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-191", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x41\x20\x3c\x61\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x3e\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-192", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x5c\x22\x61\x5c\x5c\x5c\x22\x62\x5c\x22\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "email-193", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x5c\x75\x30\x30\x65\x39\x5c\x75\x30\x30\x65\x39\x5c\x75\x30\x30\x65\x39\x5c\x75\x30\x30\x65\x39\x5c\x75\x30\x30\x65\x39\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-runes-194", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x5c\x75\x30\x30\x65\x39\x5c\x75\x30\x30\x65\x39\x5c\x75\x30\x30\x65\x39\x5c\x75\x30\x30\x65\x39\x5c\x75\x30\x30\x65\x39\x5c\x75\x30\x30\x65\x39\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-runes-195", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-runes-196", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x5c\x75\x64\x38\x33\x64\x5c\x75\x64\x65\x30\x30\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "password-runes-197", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x22\x5c\x75\x64\x38\x30\x30\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "surrogate", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x65\x6d\x61\x69\x6c\x22\x3a\x20\x22\x78\x40\x65\x78\x61\x6d\x70\x6c\x65\x2e\x63\x6f\x6d\x22\x2c\x20\x22\x75\x73\x65\x72\x6e\x61\x6d\x65\x22\x3a\x20\x22\x74\x65\x73\x74\x65\x72\x22\x2c\x20\x22\x70\x61\x73\x73\x77\x6f\x72\x64\x22\x3a\x20\x22\x61\x62\x63\x31\x32\x33\x34\x35\x22\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x22\xff\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/register", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "REGISTER", "name": "invalid-utf8", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x20\x20\x0a\x09")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "spaces", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x6e\x75\x6c\x6c")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x5b\x5d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x74\x72\x75\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "bool", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x31\x32\x33")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x22\x61\x62\x63\x22")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "string", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "truncated", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x31\x2c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "syntax", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "valid", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d\x67\x61\x72\x62\x61\x67\x65")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "trailing", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d\x7b\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "second-value", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x54\x4f\x4b\x45\x4e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "casefold", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x20\x22\x75\x6e\x6b\x6e\x6f\x77\x6e\x22\x3a\x20\x7b\x22\x69\x6e\x76\x61\x6c\x69\x64\x22\x3a\x20\x5b\x5d\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "unknown", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "token-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x31\x32\x33\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "token-number", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x7b\x7d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "token-object", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x5b\x5d\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "token-array", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x66\x61\x6c\x73\x65\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "token-false", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "token-empty", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x31\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "token-duplicate-error", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x6e\x75\x6c\x6c\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "token-duplicate-null", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x22\x5c\x75\x64\x38\x30\x30\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "surrogate", "raw": raw, "status": w.Code, "body": body})
	}
	{
		raw := []byte("\x7b\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x20\x22\x73\x6f\x6d\x65\x2d\x74\x6f\x6b\x65\x6e\x22\x2c\x22\x74\x6f\x6b\x65\x6e\x22\x3a\x22\xff\x22\x7d")
		w := httptest.NewRecorder()
		q := httptest.NewRequest("POST", "/accept", bytes.NewReader(raw))
		q.Header.Set("Content-Type", "application/json")
		r.ServeHTTP(w, q)
		var body any
		if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
			panic(w.Body.String())
		}
		rows = append(rows, map[string]any{"schema": "ACCEPT", "name": "invalid-utf8", "raw": raw, "status": w.Code, "body": body})
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(rows); err != nil {
		panic(err)
	}
}
