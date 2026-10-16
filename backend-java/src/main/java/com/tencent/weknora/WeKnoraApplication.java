package com.tencent.weknora;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class WeKnoraApplication {
    public static void main(String[] args) throws java.sql.SQLException {
        com.tencent.weknora.retrieval.NativeDatabaseRuntime.initialize();
        SpringApplication.run(WeKnoraApplication.class, args);
    }
}
