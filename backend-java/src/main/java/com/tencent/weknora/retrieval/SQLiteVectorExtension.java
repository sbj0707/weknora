package com.tencent.weknora.retrieval;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.sqlite.SQLiteConnection;

/** Operator-selected native sqlite-vec library; never accepts a model/document supplied path. */
public final class SQLiteVectorExtension {
    private final Path library;
    private final Map<Object,Boolean> loaded=Collections.synchronizedMap(new WeakHashMap<>());
    public SQLiteVectorExtension(Path library){
        if(library==null||!library.isAbsolute()||!Files.isRegularFile(library))throw new IllegalArgumentException("SQLite vector extension requires an existing absolute library path");
        this.library=library.normalize();
    }
    public void load(Connection connection)throws SQLException {
        NativeDatabaseRuntime.initialize();
        var nativeConnection=connection.unwrap(SQLiteConnection.class);var db=nativeConnection.getDatabase();
        synchronized(loaded){
            if(loaded.containsKey(db))return;
            // Independent readers/processors can share the same pooled physical connection.
            // Loading vec0 twice can fail its initialization; verify an existing registration first.
            try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT vec_version()")){
                if(!r.next()||!r.getString(1).equals("v0.1.6"))throw new SQLException("SQLite vector extension must match production Go sqlite-vec v0.1.6");
                loaded.put(db,Boolean.TRUE);return;
            }catch(SQLException ex){
                if(ex.getMessage()==null||!ex.getMessage().contains("no such function: vec_version"))throw ex;
            }
            db.enable_load_extension(true);
            try(PreparedStatement statement=connection.prepareStatement("SELECT load_extension(?)")){
                statement.setString(1,library.toString());statement.execute();
            }finally{db.enable_load_extension(false);}
            try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT vec_version()")){
                if(!r.next()||!r.getString(1).equals("v0.1.6"))throw new SQLException("SQLite vector extension must match production Go sqlite-vec v0.1.6");
            }
            loaded.put(db,Boolean.TRUE);
        }
    }
}
