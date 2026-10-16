// restore-kb salvages existing PostgreSQL soft-deleted records. It cannot
// recreate files, indexes, graph data, revisions, or physically deleted rows.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/hibiken/asynq"
	"github.com/jackc/pgx/v5"
)

type options struct {
	ID, EnvFile, Overlay, BackupDir string
	Local, Apply, Maintenance       bool
}

type config struct {
	DBURL string
	Redis asynq.RedisClientOpt
}

type tablePlan struct {
	Table, Predicate   string
	Total, Recoverable int64
}

type queueTask struct {
	Queue, State string
	Info         *asynq.TaskInfo
}

func main() {
	var o options
	flag.StringVar(&o.ID, "kb-id", "", "要恢复的知识库 UUID（必填）")
	flag.StringVar(&o.EnvFile, "env-file", ".env", "基础配置文件")
	flag.StringVar(&o.Overlay, "overlay", ".env.local", "覆盖配置文件，默认文件不存在时忽略")
	flag.StringVar(&o.BackupDir, "backup-dir", ".local-data/recovery", "执行前记录备份目录")
	flag.BoolVar(&o.Local, "local", false, "本机 dev 环境：将 postgres/redis 容器地址映射到 127.0.0.1")
	flag.BoolVar(&o.Apply, "apply", false, "执行恢复；不加此参数只进行只读检查")
	flag.BoolVar(&o.Maintenance, "maintenance-confirmed", false, "确认所有连接此库的 Go/Java 服务及任务 worker 已停止（保留数据库和 Redis）")
	flag.Parse()
	if err := validateOptions(o); err != nil {
		fail(err)
	}
	c, err := loadConfig(o)
	if err != nil {
		fail(err)
	}
	if err := run(o, c); err != nil {
		fail(err)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "恢复未完成：", err)
	os.Exit(1)
}

func validateOptions(o options) error {
	if _, err := uuid.Parse(o.ID); err != nil {
		return errors.New("请用 --kb-id 指定有效的知识库 UUID")
	}
	if o.Apply && !o.Maintenance {
		return errors.New("执行恢复前必须停止所有应用/worker，并添加 --maintenance-confirmed；请保留数据库和 Redis")
	}
	return nil
}

// Read env files without executing shell code. Existing process variables win.
func loadConfig(o options) (config, error) {
	v, err := readEnv(o.EnvFile)
	if err != nil {
		return config{}, fmt.Errorf("读取配置文件失败：%w", err)
	}
	if o.Overlay != "" {
		more, err := readEnv(o.Overlay)
		if err != nil && !(o.Overlay == ".env.local" && errors.Is(err, os.ErrNotExist)) {
			return config{}, err
		}
		for k, value := range more {
			v[k] = value
		}
	}
	for _, entry := range os.Environ() {
		k, value, ok := strings.Cut(entry, "=")
		if ok {
			v[k] = value
		}
	}
	get := func(k, fallback string) string {
		if v[k] != "" {
			return v[k]
		}
		return fallback
	}
	if get("DB_DRIVER", "postgres") != "postgres" {
		return config{}, errors.New("本脚本仅支持 PostgreSQL；SQLite 请在数据库副本上进行专门恢复")
	}
	dbURL := v["RESTORE_DATABASE_URL"]
	if dbURL == "" {
		host := get("DB_HOST", "127.0.0.1")
		if o.Local && host == "postgres" {
			host = "127.0.0.1"
		}
		u := url.URL{Scheme: "postgres", Host: net.JoinHostPort(host, get("DB_PORT", "5432")), Path: "/" + get("DB_NAME", "WeKnora"), User: url.UserPassword(get("DB_USER", "postgres"), v["DB_PASSWORD"])}
		q := url.Values{"sslmode": {get("DB_SSLMODE", "disable")}, "connect_timeout": {"10"}}
		u.RawQuery = q.Encode()
		dbURL = u.String()
	}
	redisDB, err := strconv.Atoi(get("REDIS_DB", "0"))
	if err != nil || redisDB < 0 {
		return config{}, errors.New("REDIS_DB 必须是非负整数")
	}
	addr := get("REDIS_ADDR", "127.0.0.1:6379")
	if o.Local {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return config{}, errors.New("REDIS_ADDR 必须为 host:port")
		}
		if host == "redis" {
			addr = net.JoinHostPort("127.0.0.1", get("REDIS_PORT", port))
		}
	}
	return config{DBURL: dbURL, Redis: asynq.RedisClientOpt{Addr: addr, Username: v["REDIS_USERNAME"], Password: v["REDIS_PASSWORD"], DB: redisDB, DialTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 10 * time.Second}}, nil
}

func readEnv(path string) (map[string]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	// Treat values literally rather than sourcing shell code or expanding
	// secrets containing $. A # within an unquoted word is NOT a comment,
	// matching this repository's shell-loaded .env password convention.
	v := map[string]string{}
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimPrefix(line, "export ")
		key, raw, ok := strings.Cut(line, "=")
		key, raw = strings.TrimSpace(key), strings.TrimSpace(raw)
		if !ok || key == "" {
			return nil, fmt.Errorf("配置文件 %s 第 %d 行必须是 KEY=VALUE（内容不输出）", path, lineNo)
		}
		value, err := envValue(raw)
		if err != nil {
			return nil, fmt.Errorf("配置文件 %s 第 %d 行格式不支持（内容不输出）", path, lineNo)
		}
		v[key] = value
	}
	return v, scanner.Err()
}

func envValue(raw string) (string, error) {
	if raw == "" {
		return "", nil
	}
	if raw[0] != '\'' && raw[0] != '"' {
		for i := 1; i < len(raw); i++ {
			if raw[i] == '#' && (raw[i-1] == ' ' || raw[i-1] == '\t') {
				raw = raw[:i]
				break
			}
		}
		return strings.TrimSpace(raw), nil
	}
	quote := raw[0]
	var result strings.Builder
	for i := 1; i < len(raw); i++ {
		if raw[i] == quote {
			rest := strings.TrimSpace(raw[i+1:])
			if rest != "" && !strings.HasPrefix(rest, "#") {
				return "", errors.New("unexpected quote suffix")
			}
			return result.String(), nil
		}
		if quote == '"' && raw[i] == '\\' && i+1 < len(raw) {
			i++
			switch raw[i] {
			case 'n':
				result.WriteByte('\n')
			case 'r':
				result.WriteByte('\r')
			case '"', '\\', '$':
				result.WriteByte(raw[i])
			default:
				result.WriteByte('\\')
				result.WriteByte(raw[i])
			}
		} else {
			result.WriteByte(raw[i])
		}
	}
	return "", errors.New("unterminated quote")
}

func run(o options, c config) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	conn, err := pgx.Connect(ctx, c.DBURL)
	if err != nil {
		return errors.New("数据库连接失败；请核对 DB_HOST/DB_PORT/DB_NAME 和凭证（连接串不会输出）")
	}
	defer conn.Close(context.Background())
	var dbName, schema string
	if err := conn.QueryRow(ctx, "SELECT current_database(), current_schema()").Scan(&dbName, &schema); err != nil {
		return err
	}
	fmt.Printf("数据库：%s；schema：%s；知识库：%s\n", dbName, schema, o.ID)
	access := pgx.ReadOnly
	if o.Apply {
		access = pgx.ReadWrite
	}
	tx, err := conn.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.Serializable, AccessMode: access})
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	if _, err := tx.Exec(ctx, "SET LOCAL lock_timeout = '5s'"); err != nil {
		return err
	}
	kbTable := pgx.Identifier{schema, "knowledge_bases"}.Sanitize()
	query := "SELECT tenant_id, name, deleted_at FROM " + kbTable + " WHERE id=$1"
	if o.Apply {
		query += " FOR UPDATE"
	}
	var tenantID int64
	var name string
	var deletedAt *time.Time
	if err := tx.QueryRow(ctx, query, o.ID).Scan(&tenantID, &name, &deletedAt); errors.Is(err, pgx.ErrNoRows) {
		return errors.New("此数据库中没有该知识库记录（包括软删除记录）。不能凭 ID 重建原数据；请定位正确数据库或先在独立环境恢复删除前备份")
	} else if err != nil {
		return err
	}
	if deletedAt == nil {
		fmt.Printf("知识库 %q 当前未删除，不修改任何数据。\n", name)
		return nil
	}
	if tenantID <= 0 {
		return errors.New("知识库缺少有效 tenant_id，拒绝恢复")
	}
	var liveTenant bool
	if err := tx.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM "+pgx.Identifier{schema, "tenants"}.Sanitize()+" WHERE id=$1 AND deleted_at IS NULL)", tenantID).Scan(&liveTenant); err != nil {
		return err
	}
	if !liveTenant {
		return errors.New("所属空间已删除或不存在，必须先恢复空间")
	}
	fmt.Printf("名称：%s；空间：%d；删除时间：%s\n", name, tenantID, deletedAt.Format(time.RFC3339))
	args := []any{o.ID, tenantID, *deletedAt}
	plans, err := makePlans(ctx, tx, schema, args)
	if err != nil {
		return err
	}
	docIDs, err := documentIDs(ctx, tx, schema, args)
	if err != nil {
		return err
	}
	for _, p := range plans {
		fmt.Printf("%-18s 残留记录 %d，拟恢复 %d\n", p.Table, p.Total, p.Recoverable)
	}
	fmt.Println("原始文件、图片、向量/关键词索引、图谱和 Wiki 历史版本未验证；本脚本不会重建它们。")
	inspector := asynq.NewInspector(c.Redis)
	defer inspector.Close()
	tasks, err := scanDeleteTasks(inspector, o.ID, tenantID, docIDs)
	if err != nil {
		return fmt.Errorf("删除队列检查失败；拒绝跳过：%w", err)
	}
	for _, t := range tasks {
		fmt.Printf("关联删除任务：queue=%s state=%s type=%s id=%s\n", t.Queue, t.State, t.Info.Type, t.Info.ID)
	}
	if !o.Apply {
		fmt.Println("只读检查结束，未修改数据库或队列。执行前请阅读 scripts/restore-kb/README.md。")
		return nil
	}
	servers, err := inspector.Servers()
	if err != nil {
		return fmt.Errorf("无法检查 worker：%w", err)
	}
	if len(servers) > 0 {
		return errors.New("Redis 中仍存在 Asynq worker 心跳；请完全停止应用/worker，等待心跳过期后重试")
	}
	for _, t := range tasks {
		if t.State == "active" {
			return errors.New("仍有 active 删除任务。请确认 worker 已退出并等待任务转入 retry，不能在执行中的清理任务旁恢复")
		}
		if t.Info.Type != "kb:delete" {
			return errors.New("还存在文档/索引删除任务。它们可能涉及其他知识库，请在任务管理中逐项处理后重试；本脚本只移除目标知识库的 kb:delete 任务")
		}
	}
	backup, err := saveBackup(ctx, tx, schema, args, plans, tasks, o.BackupDir)
	if err != nil {
		return fmt.Errorf("备份失败，未修改数据：%w", err)
	}
	fmt.Println("恢复前记录备份：", backup)
	// Redis and SQL are not one transaction. Remove backed-up deletion tasks
	// before making the KB visible. Any error keeps the SQL transaction rolled back.
	for _, t := range tasks {
		if err := inspector.DeleteTask(t.Queue, t.Info.ID); err != nil && !errors.Is(err, asynq.ErrTaskNotFound) {
			return fmt.Errorf("移除删除任务失败，数据库不恢复：%w", err)
		}
	}
	remaining, err := scanDeleteTasks(inspector, o.ID, tenantID, docIDs)
	if err != nil || len(remaining) > 0 {
		return errors.New("删除任务重新出现或复查失败，数据库不恢复；请确认所有服务均已停止")
	}
	// Only restore children deleted at or after this KB's deletion. A child
	// deleted earlier stays deleted, as do children of previously deleted docs.
	for _, p := range plans {
		q := "UPDATE " + pgx.Identifier{schema, p.Table}.Sanitize() + " SET deleted_at=NULL WHERE " + p.Predicate + " AND deleted_at >= $3"
		result, err := tx.Exec(ctx, q, args...)
		if err != nil {
			return fmt.Errorf("恢复 %s 失败，数据库事务将回滚：%w", p.Table, err)
		}
		fmt.Printf("已恢复 %s：%d 条\n", p.Table, result.RowsAffected())
	}
	if _, err := tx.Exec(ctx, "UPDATE "+kbTable+" SET deleted_at=NULL WHERE id=$1 AND tenant_id=$2 AND deleted_at=$3", args...); err != nil {
		return err
	}
	// Recompute rather than increment: cleanup may have reduced accounting
	// before it soft-deleted the docs, and re-running must not double-count.
	if _, err := tx.Exec(ctx, "UPDATE "+pgx.Identifier{schema, "tenants"}.Sanitize()+" SET storage_used=(SELECT COALESCE(SUM(k.storage_size),0) FROM "+pgx.Identifier{schema, "knowledges"}.Sanitize()+" k JOIN "+kbTable+" b ON b.id=k.knowledge_base_id AND b.tenant_id=k.tenant_id WHERE k.tenant_id=$1 AND k.deleted_at IS NULL AND b.deleted_at IS NULL) WHERE id=$1", tenantID); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("数据库提交失败，检查结果与备份后再重试：%w", err)
	}
	fmt.Println("残留记录已恢复。此结果不代表索引或文件完整；重启服务后检查文档，按需从原始文件重新解析/构建索引。分享和数据源保持原删除状态，请手动重新配置。")
	return nil
}

func makePlans(ctx context.Context, tx pgx.Tx, schema string, args []any) ([]tablePlan, error) {
	docs := pgx.Identifier{schema, "knowledges"}.Sanitize()
	scope := "knowledge_base_id=$1 AND tenant_id=$2"
	eligibleDoc := "knowledge_id IN (SELECT id FROM " + docs + " WHERE " + scope + " AND (deleted_at IS NULL OR deleted_at >= $3))"
	definitions := []tablePlan{
		{Table: "knowledges", Predicate: scope},
		{Table: "chunks", Predicate: scope + " AND " + eligibleDoc},
		{Table: "wiki_folders", Predicate: scope},
		{Table: "wiki_pages", Predicate: scope},
		{Table: "wiki_page_issues", Predicate: scope},
	}
	var plans []tablePlan
	for _, p := range definitions {
		var present bool
		if err := tx.QueryRow(ctx, "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=$1 AND table_name=$2)", schema, p.Table).Scan(&present); err != nil {
			return nil, err
		}
		if !present {
			if p.Table == "knowledges" || p.Table == "chunks" {
				return nil, fmt.Errorf("缺少必需表 %s", p.Table)
			}
			continue
		}
		q := "SELECT COUNT(*), COUNT(*) FILTER (WHERE deleted_at >= $3) FROM " + pgx.Identifier{schema, p.Table}.Sanitize() + " WHERE " + p.Predicate
		if err := tx.QueryRow(ctx, q, args...).Scan(&p.Total, &p.Recoverable); err != nil {
			return nil, err
		}
		plans = append(plans, p)
	}
	return plans, nil
}

func documentIDs(ctx context.Context, tx pgx.Tx, schema string, args []any) (map[string]bool, error) {
	rows, err := tx.Query(ctx, "SELECT id FROM "+pgx.Identifier{schema, "knowledges"}.Sanitize()+" WHERE knowledge_base_id=$1 AND tenant_id=$2 AND (deleted_at IS NULL OR deleted_at >= $3)", args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := map[string]bool{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids[id] = true
	}
	return ids, rows.Err()
}

func matchingDelete(info *asynq.TaskInfo, id string, tenant int64, docs map[string]bool) bool {
	if info.Type != "kb:delete" && info.Type != "index:delete" && info.Type != "knowledge:list_delete" {
		return false
	}
	var payload struct {
		TenantID    int64    `json:"tenant_id"`
		KBID        string   `json:"knowledge_base_id"`
		KnowledgeID string   `json:"knowledge_id"`
		IDs         []string `json:"knowledge_ids"`
	}
	if json.Unmarshal(info.Payload, &payload) != nil || payload.TenantID != tenant {
		return false
	}
	if payload.KBID == id || docs[payload.KnowledgeID] {
		return true
	}
	for _, doc := range payload.IDs {
		if docs[doc] {
			return true
		}
	}
	return false
}

func scanDeleteTasks(i *asynq.Inspector, id string, tenant int64, docs map[string]bool) ([]queueTask, error) {
	queues, err := i.Queues()
	if err != nil {
		return nil, err
	}
	states := []struct {
		name string
		list func(string, ...asynq.ListOption) ([]*asynq.TaskInfo, error)
	}{
		{"pending", i.ListPendingTasks}, {"scheduled", i.ListScheduledTasks}, {"retry", i.ListRetryTasks}, {"archived", i.ListArchivedTasks}, {"active", i.ListActiveTasks},
	}
	var tasks []queueTask
	for _, queue := range queues {
		for _, state := range states {
			for page := 1; ; page++ {
				infos, err := state.list(queue, asynq.Page(page), asynq.PageSize(100))
				if err != nil {
					return nil, err
				}
				for _, info := range infos {
					if matchingDelete(info, id, tenant, docs) {
						tasks = append(tasks, queueTask{Queue: queue, State: state.name, Info: info})
					}
				}
				if len(infos) < 100 {
					break
				}
			}
		}
	}
	return tasks, nil
}

func saveBackup(ctx context.Context, tx pgx.Tx, schema string, args []any, plans []tablePlan, tasks []queueTask, root string) (string, error) {
	dir := filepath.Join(root, args[0].(string)+"-"+time.Now().UTC().Format("20060102T150405.000000000Z"))
	if err := os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	if err := os.Chmod(dir, 0700); err != nil {
		return "", err
	}
	all := append([]tablePlan{{Table: "knowledge_bases", Predicate: "id=$1 AND tenant_id=$2 AND deleted_at=$3"}}, plans...)
	all = append(all, tablePlan{Table: "tenants", Predicate: "id=$2 AND $1::text IS NOT NULL AND $3::timestamptz IS NOT NULL"})
	for _, p := range all {
		q := "SELECT COALESCE(jsonb_agg(to_jsonb(t)),'[]'::jsonb) FROM " + pgx.Identifier{schema, p.Table}.Sanitize() + " t WHERE " + p.Predicate
		// Docs/wiki predicates do not otherwise reference $3 when snapshotting.
		q += " AND $3::timestamptz IS NOT NULL"
		var data []byte
		if err := tx.QueryRow(ctx, q, args...).Scan(&data); err != nil {
			return "", err
		}
		if err := os.WriteFile(filepath.Join(dir, p.Table+".json"), data, 0600); err != nil {
			return "", err
		}
	}
	data, err := json.MarshalIndent(tasks, "", "  ")
	if err != nil {
		return "", err
	}
	if err := os.WriteFile(filepath.Join(dir, "delete-tasks.json"), data, 0600); err != nil {
		return "", err
	}
	return dir, nil
}
