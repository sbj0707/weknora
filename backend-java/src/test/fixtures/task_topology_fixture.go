//go:build ignore

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Tencent/WeKnora/internal/application/service"
	"github.com/Tencent/WeKnora/internal/types"
	"github.com/Tencent/WeKnora/internal/types/interfaces"
	"os"
)

type repo struct {
	interfaces.SystemSettingRepository
	row    *types.SystemSetting
	broken bool
}

func (r repo) Get(_ context.Context, key string) (*types.SystemSetting, error) {
	if r.broken {
		return nil, errors.New("isolated settings unavailable")
	}
	if r.row != nil && r.row.Key == key {
		return r.row, nil
	}
	return nil, nil
}
func (r repo) List(context.Context) ([]*types.SystemSetting, error) {
	return nil, errors.New("keep production GetInt cold path for fixture")
}

type spec struct {
	Pool, Key, Env string
	Default        int
}
type C struct {
	Name        string         `json:"name"`
	Pool        string         `json:"pool"`
	Key         string         `json:"key"`
	Environment string         `json:"environment"`
	Default     int            `json:"default"`
	Present     bool           `json:"present"`
	Raw         string         `json:"raw"`
	Modified    string         `json:"modified"`
	Env         string         `json:"env"`
	Broken      bool           `json:"broken"`
	Resolved    int64          `json:"resolved"`
	Allocation  map[string]int `json:"allocation"`
}

func allocation(a types.WorkerPoolConcurrency) map[string]int {
	return map[string]int{"core": a.Core, "postprocess": a.PostProcess, "enrichment": a.Enrichment, "maintenance": a.Maintenance, "shared": a.Shared, "wiki": a.Wiki}
}
func main() {
	specs := []spec{{"core", "asynq.core_concurrency", "WEKNORA_ASYNQ_CORE_CONCURRENCY", 8}, {"postprocess", "asynq.postprocess_concurrency", "WEKNORA_ASYNQ_POSTPROCESS_CONCURRENCY", 2}, {"enrichment", "asynq.enrichment_concurrency", "WEKNORA_ASYNQ_ENRICHMENT_CONCURRENCY", 12}, {"maintenance", "asynq.maintenance_concurrency", "WEKNORA_ASYNQ_MAINTENANCE_CONCURRENCY", 4}, {"shared", "asynq.shared_concurrency", "WEKNORA_ASYNQ_SHARED_CONCURRENCY", 6}, {"wiki", "asynq.wiki_concurrency", "WEKNORA_WIKI_ASYNQ_CONCURRENCY", 8}}
	for _, s := range specs {
		os.Unsetenv(s.Env)
	}
	var cases []C
	for _, s := range specs {
		inputs := []C{{Name: "no row", Env: "17"}, {Name: "invalid env", Env: " 17"}, {Name: "positive quoted env", Env: "+03"}, {Name: "defaults"}, {Name: "negative env", Env: "-1"}, {Name: "DBfailure", Env: "11", Broken: true}}
		for _, raw := range []string{"0", "-1", "null", "17", "\"17\"", "17.0", "1e2", "true", "[]", "{}", "\"+03\"", "\" 17\"", "17 trailing", "\"9223372036854775808\"", "9223372036854775808", "-9223372036854775808", "", "  \n17\t ", fmt.Sprint(s.Default), "  " + fmt.Sprint(s.Default) + "\n", "\"" + fmt.Sprint(s.Default) + "\""} {
			inputs = append(inputs, C{Name: "raw=" + raw, Raw: raw, Env: "19", Present: true}, C{Name: "edited=" + raw, Raw: raw, Modified: "operator", Env: "19", Present: true})
		}
		inputs = append(inputs, C{Name: "Unicode bootstrap", Raw: fmt.Sprint(s.Default), Modified: "\u00a0\u0085", Env: "23", Present: true})
		for _, c := range inputs {
			c.Pool = s.Pool
			c.Key = s.Key
			c.Environment = s.Env
			c.Default = s.Default
			os.Setenv(s.Env, c.Env)
			var row *types.SystemSetting
			if c.Present {
				row = &types.SystemSetting{Key: s.Key, Value: types.JSON([]byte(c.Raw)), LastModifiedBy: c.Modified}
			}
			svc := service.NewSystemSettingService(repo{row: row, broken: c.Broken}, nil, nil, nil)
			c.Resolved = svc.GetInt(context.Background(), s.Key, s.Env, int64(s.Default))
			c.Allocation = allocation(types.ResolveWorkerPoolConcurrency(func(key, env string, fallback int) int {
				return int(svc.GetInt(context.Background(), key, env, int64(fallback)))
			}))
			cases = append(cases, c)
			os.Unsetenv(s.Env)
		}
	}
	weights := map[string]map[string]int{}
	for _, s := range specs {
		if s.Pool == "shared" {
			weights[s.Pool] = types.QueueWeightsForSharedPool()
		} else {
			weights[s.Pool] = types.QueueWeightsForPool(s.Pool)
		}
	}
	tasks := map[string]string{}
	for _, q := range types.QueueDefinitions() {
		for _, t := range q.TaskTypes {
			queue, ok := types.QueueForTaskType(t)
			if !ok {
				panic(t)
			}
			tasks[t] = queue
		}
	}
	for _, t := range []string{"", "document:processX", "unknown"} {
		q, _ := types.QueueForTaskType(t)
		tasks[t] = q
	}
	out := map[string]interface{}{"definitions": types.QueueDefinitions(), "weights": weights, "tasks": tasks, "defaults": allocation(types.DefaultWorkerPoolConcurrency()), "settings": cases}
	b, e := json.Marshal(out)
	if e != nil {
		panic(e)
	}
	path := "backend-java/src/test/java/com/tencent/weknora/task/go-task-topology.json"
	if len(os.Args) > 1 {
		path = os.Args[1]
	}
	os.WriteFile(path, b, 0600)
	fmt.Println("production settings", len(cases), "task types", len(tasks))
}
