package com.tencent.weknora.security;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Like Go, authentication updates last_used_at asynchronously at most once per minute per key. */
@Component
public class ApiKeyUsageTracker {
    private static final Logger LOG=LoggerFactory.getLogger(ApiKeyUsageTracker.class);
    private final ConcurrentMap<Long,Instant> touched=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1024),task->{Thread thread=new Thread(task,"tenant-api-key-last-used");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final JdbcTemplate jdbc;
    private final AuthRepository repository;
    public ApiKeyUsageTracker(JdbcTemplate jdbc,AuthRepository repository){this.jdbc=jdbc;this.repository=repository;}
    void touch(long id){
        Instant now=Instant.now();boolean[] reserved={false};
        touched.compute(id,(ignored,old)->{if(old==null||old.plusSeconds(60).isBefore(now)){reserved[0]=true;return now;}return old;});
        if(!reserved[0])return;
        try{executor.execute(()->{try{jdbc.update("UPDATE tenant_api_keys SET last_used_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",repository.timestampParameter(now),id);}catch(RuntimeException ex){touched.remove(id,now);LOG.warn("Failed to update tenant API key last_used_at (id={})",id);}});}
        catch(RejectedExecutionException ex){touched.remove(id,now);}
    }
    @PreDestroy public void close(){executor.shutdownNow();}
}
