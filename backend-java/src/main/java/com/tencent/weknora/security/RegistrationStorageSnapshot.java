package com.tencent.weknora.security;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.Environment;

/** Exact Go StorageBackendFromEnvironment union; creation does not contact storage providers. */
record RegistrationStorageSnapshot(String provider, Map<String,Object> config) {
    static RegistrationStorageSnapshot from(Environment env, GoSecretCodec secrets) {
        String provider=AuthPreferences.trimGoSpace(env.getProperty("STORAGE_TYPE","")).toLowerCase(Locale.ROOT);
        if(provider.isEmpty())provider="local";
        Map<String,Object> config=new LinkedHashMap<>();
        if(provider.equals("local"))put(config,"path_prefix",AuthPreferences.trimGoSpace(env.getProperty("LOCAL_STORAGE_PATH_PREFIX","")));
        else {
            if(!java.util.Set.of("minio","cos","tos","s3","oss","obs").contains(provider))throw new ApiException(400,"INVALID_REQUEST","failed to create workspace").withAppCode(1000);
            String prefix=provider.toUpperCase(Locale.ROOT)+"_";
            for(String field:java.util.List.of("endpoint","region","bucket_name","path_prefix")) {
                if(provider.equals("cos")&&field.equals("endpoint")||provider.equals("minio")&&field.equals("region"))continue;
                put(config,field,env.getProperty(prefix+field.toUpperCase(Locale.ROOT),""));
            }
            String access=provider.equals("minio")?"ACCESS_KEY_ID":provider.equals("cos")?"SECRET_ID":"ACCESS_KEY";
            String secret=provider.equals("minio")?"SECRET_ACCESS_KEY":provider.equals("cos")?"SECRET_KEY":"SECRET_KEY";
            put(config,"access_key_id",secrets.encryptLegacyWhenConfigured(env.getProperty(prefix+access,"")));
            put(config,"secret_access_key",secrets.encryptLegacyWhenConfigured(env.getProperty(prefix+secret,"")));
            if(provider.equals("minio"))config.put("mode","remote");
            if(provider.equals("cos"))put(config,"app_id",env.getProperty(prefix+"APP_ID",""));
            if(java.util.Set.of("cos","tos","oss").contains(provider)) {
                put(config,"temp_bucket_name",env.getProperty(prefix+"TEMP_BUCKET_NAME",""));put(config,"temp_region",env.getProperty(prefix+"TEMP_REGION",""));
            }
            boolean ssl=provider.equals("minio")?"true".equalsIgnoreCase(env.getProperty(prefix+"USE_SSL","")):!"false".equalsIgnoreCase(env.getProperty(prefix+"USE_SSL",""));
            if(java.util.Set.of("minio","s3","obs").contains(provider)&&ssl)config.put("use_ssl",true);
            if(provider.equals("s3")&&"true".equalsIgnoreCase(env.getProperty(prefix+"FORCE_PATH_STYLE","")))config.put("force_path_style",true);
            if(provider.equals("oss")&&!env.getProperty(prefix+"TEMP_BUCKET_NAME","").isEmpty())config.put("use_temp_bucket",true);
        }
        return new RegistrationStorageSnapshot(provider,config);
    }
    private static void put(Map<String,Object> map,String key,String value){if(value!=null&&!value.isEmpty())map.put(key,value);}
}
