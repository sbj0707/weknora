//go:build ignore

package main

import (
	"encoding/json"
	"os"
	"strings"
	"time"
)

func parseFilterTime(raw string) (time.Time, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return time.Time{}, nil
	}
	layouts := []string{time.RFC3339Nano, time.RFC3339, "2006-01-02 15:04:05", "2006-01-02"}
	var lastErr error
	for _, layout := range layouts {
		if t, err := time.ParseInLocation(layout, raw, time.Local); err == nil {
			return t, nil
		} else {
			lastErr = err
		}
	}
	return time.Time{}, lastErr
}

func main() {
	var inputs []string
	if err := json.Unmarshal(must(os.ReadFile("backend-java/src/test/resources/go-knowledge-filter-time-input.json")), &inputs); err != nil {
		panic(err)
	}
	out := []map[string]any{}
	for _, zone := range []string{"UTC", "Asia/Shanghai", "America/New_York", "Europe/Berlin"} {
		local, err := time.LoadLocation(zone)
		if err != nil {
			panic(err)
		}
		time.Local = local
		for _, raw := range inputs {
			t, e := parseFilterTime(raw)
			row := map[string]any{"zone": zone, "raw": raw}
			if e != nil {
				row["error"] = e.Error()
			} else {
				row["zero"] = t.IsZero()
				row["second"] = t.Unix()
				row["nano"] = t.Nanosecond()
			}
			out = append(out, row)
		}
	}
	json.NewEncoder(os.Stdout).Encode(out)
}
func must(b []byte, e error) []byte {
	if e != nil {
		panic(e)
	}
	return b
}
