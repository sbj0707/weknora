package com.tencent.weknora.model;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Deployment baseline plus singleton console overlay, retaining the last valid generation. */
@Service
public class ModelCatalogRuntime {
    private static final Logger LOG=LoggerFactory.getLogger(ModelCatalogRuntime.class);
    private final JdbcTemplate jdbc;
    private final CatalogRuntime deployment;
    private CatalogRuntime current;
    private long applied=-1,failed=-1,lastPoll;
    public ModelCatalogRuntime(JdbcTemplate jdbc,@Value("${MODELS_CONFIG:}") String config,@Value("${weknora.model.config-dir:./config}") String directory) {
        this.jdbc=jdbc;
        CatalogRuntime baseline=CatalogRuntime.builtins();
        Path file=config.isEmpty()?Path.of(directory,"models.json"):Path.of(config);
        try {
            if(Files.exists(file))baseline=CatalogOverlay.apply(baseline,Files.readString(file),file.toAbsolutePath().getParent(),false);
        }catch(IOException|IllegalArgumentException ex){LOG.warn("Invalid model deployment catalog; retaining built-in generation");}
        deployment=baseline;current=baseline;
    }
    synchronized CatalogRuntime snapshot() {
        long now=System.nanoTime();
        if(applied<0||now-lastPoll>=5_000_000_000L)syncNow();
        return current;
    }
    synchronized void syncNow() {
        if(jdbc==null)return;
        lastPoll=System.nanoTime();
        try {
            List<Map<String,Object>> version=jdbc.queryForList("SELECT version FROM model_catalog_configs WHERE id=1");
            if(version.isEmpty())return;
            long next=((Number)version.getFirst().get("version")).longValue();
            if(next==applied||next==failed)return;
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT version,overlay FROM model_catalog_configs WHERE id=1");
            if(rows.isEmpty())return;
            long actual=((Number)rows.getFirst().get("version")).longValue();
            Object raw=rows.getFirst().get("overlay");
            String overlay=raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):Objects.toString(raw,"");
            try {
                CatalogRuntime candidate=CatalogOverlay.apply(deployment,overlay,null,true);
                current=candidate;applied=actual;failed=-1;
            }catch(IllegalArgumentException ex){failed=actual;LOG.warn("Invalid model console catalog version {}; retaining last valid generation",actual);}
        }catch(DataAccessException ex) {
            // Existing Go also retains its last valid catalog when schema/bootstrap/DB sync fails.
        }
    }
    long appliedVersion() {return applied;}
}
