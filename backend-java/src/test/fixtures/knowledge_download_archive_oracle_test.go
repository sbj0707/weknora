//go:build weknora_batch_archive_oracle

// Mounted into internal/handler by a Go overlay, so the oracle invokes the real unexported helpers.
package handler

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	app "github.com/Tencent/WeKnora/internal/errors"
	"io"
	"os"
	"testing"
)

type javaArchiveFaultReader struct {
	io.Reader
	fault  string
	closed *int
}

func (r *javaArchiveFaultReader) Read(p []byte) (int, error) {
	if r.fault == "read" {
		return 0, errors.New("read failed")
	}
	if r.fault == "read-canceled" {
		return 0, context.Canceled
	}
	if r.fault == "read-deadline" {
		return 0, context.DeadlineExceeded
	}
	return r.Reader.Read(p)
}
func (r *javaArchiveFaultReader) Close() error {
	*r.closed++
	if r.fault == "close" {
		return errors.New("close failed")
	}
	return nil
}

type javaArchiveFaultWriter struct{}

func (javaArchiveFaultWriter) Write(p []byte) (int, error) { return 0, errors.New("output failed") }
func TestJavaDownloadArchiveOracle(t *testing.T) {
	target := os.Getenv("WEKNORA_BATCH_ARCHIVE_OUTPUT")
	if target == "" {
		t.Fatal("Explicit owned oracle output required")
	}
	rows := []any{}
	for _, fault := range []string{"ok", "open", "open-app", "open-canceled", "open-deadline", "read", "read-canceled", "read-deadline", "close", "output", "context-canceled", "context-deadline"} {
		for _, limit := range []int64{0, 3, 4, 5, 7, 8, 9} {
			if fault != "ok" && limit != 9 {
				continue
			}
			ctx := context.Background()
			if fault == "context-canceled" {
				var cancel context.CancelFunc
				ctx, cancel = context.WithCancel(ctx)
				cancel()
			}
			if fault == "context-deadline" {
				ctx = &javaArchiveDeadline{ctx}
			}
			buffer := &bytes.Buffer{}
			var writer io.Writer = buffer
			if fault == "output" {
				writer = javaArchiveFaultWriter{}
			}
			opened, closed := 0, 0
			entries := []knowledgeDownloadEntry{{ID: "a", FolderPath: "目录/CON"}, {ID: "b", FolderPath: "目录/CON"}}
			err := writeKnowledgeDownloadArchive(ctx, writer, entries, func(ctx context.Context, id string) (io.ReadCloser, string, error) {
				opened++
				switch fault {
				case "open":
					return nil, "", errors.New("open failed")
				case "open-app":
					return nil, "", app.NewBadRequestError("injected")
				case "open-canceled":
					return nil, "", context.Canceled
				case "open-deadline":
					return nil, "", context.DeadlineExceeded
				}
				value := "abcd"
				if id == "b" {
					value = "WXYZ"
				}
				return &javaArchiveFaultReader{Reader: bytes.NewBufferString(value), fault: fault, closed: &closed}, "a.txt", nil
			}, limit)
			var response any
			if err != nil {
				mapped := mapKnowledgeDownloadError(err)
				if a, ok := app.IsAppError(mapped); ok {
					response = map[string]any{"status": a.HTTPCode, "code": a.Code, "message": a.Message, "details": a.Details}
				} else {
					t.Fatalf("unmapped error %v", mapped)
				}
			}
			rows = append(rows, map[string]any{"fault": fault, "limit": limit, "error": response, "opened": opened, "closed": closed, "zip_base64": base64.StdEncoding.EncodeToString(buffer.Bytes())})
		}
	}
	for _, extra := range []int64{0, 1} {
		digest := sha256.New()
		opened, closed := 0, 0
		length := maxBatchDownloadBytes + extra
		err := writeKnowledgeDownloadArchive(context.Background(), digest, []knowledgeDownloadEntry{{ID: "a", FolderPath: "目录/CON"}}, func(context.Context, string) (io.ReadCloser, string, error) {
			opened++
			return &javaArchiveFaultReader{Reader: io.LimitReader(javaArchiveZeroReader{}, length), closed: &closed}, "a.txt", nil
		}, maxBatchDownloadBytes)
		var response any
		if err != nil {
			a, ok := app.IsAppError(mapKnowledgeDownloadError(err))
			if !ok {
				t.Fatal(err)
			}
			response = map[string]any{"status": a.HTTPCode, "code": a.Code, "message": a.Message, "details": a.Details}
		}
		rows = append(rows, map[string]any{"fault": "large-limit", "limit": maxBatchDownloadBytes, "source_bytes": length, "error": response, "opened": opened, "closed": closed, "zip_sha256": hex.EncodeToString(digest.Sum(nil))})
	}
	slots := []bool{}
	for i := 0; i < 5; i++ {
		slots = append(slots, tryAcquireBatchDownloadSlot())
	}
	for _, held := range slots {
		if held {
			releaseBatchDownloadSlot()
		}
	}
	reusable := tryAcquireBatchDownloadSlot()
	if reusable {
		releaseBatchDownloadSlot()
	}
	out := map[string]any{"cases": rows, "slots": slots, "reusable": reusable}
	data, err := json.Marshal(out)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(target, append(data, '\n'), 0600); err != nil {
		t.Fatal(err)
	}
}

type javaArchiveDeadline struct{ context.Context }

func (*javaArchiveDeadline) Err() error { return context.DeadlineExceeded }

type javaArchiveZeroReader struct{}

func (javaArchiveZeroReader) Read(p []byte) (int, error) { clear(p); return len(p), nil }
