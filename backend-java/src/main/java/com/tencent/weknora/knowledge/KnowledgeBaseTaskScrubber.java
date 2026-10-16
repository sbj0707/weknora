package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.task.TaskTopology;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable operation fence followed by Go's whole-KB scope match in the native SQL queue. */
public final class KnowledgeBaseTaskScrubber {
  private final KnowledgeBaseDeleteSql sql;
  private final KnowledgeBaseTaskScope scopes;
  private final Clock clock;
  private final List<String> queues = TaskTopology.definitions().stream().map(TaskTopology.Definition::name).toList();

  public KnowledgeBaseTaskScrubber(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
    sql = new KnowledgeBaseDeleteSql(jdbc);
    scopes = new KnowledgeBaseTaskScope(json);
    this.clock = clock;
  }

  public void scrub(String base, List<String> documents, List<String> sources, CallContext call) {
    if (base == null || base.isEmpty()) return;
    // This must commit before queue inspection: recovery must not recreate Wiki triggers.
    try { sql.update(call, "DELETE FROM task_pending_ops WHERE scope=? AND scope_id=?", "knowledge_base", base); }
    catch (RuntimeException error) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Knowledge base durable task cleanup failed ({})", error.getClass().getSimpleName());
    }
    cancel(base, documents, sources, call);
  }

  /** KB/document/data-source IDs are globally unique; shared-tenant work can reference the same KB. */
  public int cancel(String base, List<String> documents, List<String> sources, CallContext call) {
    Set<String> ids = ids(documents), sourceIds = ids(sources);
    if ((base == null || base.isEmpty()) && ids.isEmpty() && sourceIds.isEmpty()) return 0;
    int changed = 0;
    // SQL cancel_requested is sticky across worker settlement/recovery, so active work cannot
    // escape into retry. A final queued sweep catches downstream work emitted during cancellation.
    for (String states : List.of("'pending','retry'", "'running'", "'pending','retry'")) {
      String after = "";
      for (;;) {
        call.check();
        var args = new ArrayList<Object>();
        args.add(after);
        args.addAll(queues);
        var rows = sql.query(call, "SELECT id,type,payload FROM java_task_jobs WHERE id>? AND status IN ("
            + states + ") AND queue IN (" + String.join(",", Collections.nCopies(queues.size(), "?"))
            + ") ORDER BY id LIMIT 500", args.toArray());
        if (rows.isEmpty()) break;
        for (var row : rows) {
          call.check();
          after = row.get("id").toString();
          if (scopes.matches(row.get("type").toString(), row.get("payload").toString(), base, ids, sourceIds))
            changed += sql.update(call, "UPDATE java_task_jobs SET cancel_requested=?,status=CASE WHEN status IN ('pending','retry')"
                + " THEN 'canceled' ELSE status END,updated_at=? WHERE id=? AND status IN (" + states + ") AND cancel_requested=?",
                true, clock.millis(), after, false);
        }
      }
    }
    return changed;
  }

  private static Set<String> ids(List<String> values) {
    var out = new HashSet<String>();
    if (values != null) for (String value : values) if (value != null && !value.isEmpty()) out.add(value);
    return out;
  }
}
