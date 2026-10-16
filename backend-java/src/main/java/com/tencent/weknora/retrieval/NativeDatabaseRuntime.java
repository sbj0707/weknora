package com.tencent.weknora.retrieval;

import java.sql.DriverManager;
import java.sql.SQLException;

/** Establish the native library order before registering SQLite's vector callbacks. */
public final class NativeDatabaseRuntime {
    private static boolean initialized;
    private NativeDatabaseRuntime() {}

    public static synchronized void initialize() throws SQLException {
        if(initialized)return;
        // On macOS arm64, loading DuckDB JDBC 1.5.2 after sqlite-vec registration
        // can corrupt a reused SQLite connection on a vector BLOB read: CHECK
        // writes report SQLITE_NOMEM, and a minimal in-memory replay can crash.
        // Opening and closing an empty DuckDB loads its JNI runtime through the
        // public JDBC API before vec0 is registered. No data or extensions load.
        try(var connection=DriverManager.getConnection("jdbc:duckdb:")) {}
        initialized=true;
    }
}
