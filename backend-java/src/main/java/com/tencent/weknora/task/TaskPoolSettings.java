package com.tencent.weknora.task;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Startup-only worker allocation. Mirrors GetInt and bootstrap default rows in Go's settings service. */
public final class TaskPoolSettings implements TaskTopology.SettingReader {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final Environment environment;
    public TaskPoolSettings(JdbcTemplate jdbc,ObjectMapper json,Environment environment){this.jdbc=jdbc;this.json=json;this.environment=environment;}
    @Override public long read(TaskTopology.Setting setting){
        try{
            var rows=jdbc.queryForList("SELECT value,last_modified_by FROM system_settings WHERE key=?",setting.key());
            if(!rows.isEmpty()){
                var row=rows.getFirst();String raw=Objects.toString(row.get("value"),""),modified=Objects.toString(row.get("last_modified_by"),"");
                JsonNode value=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
                boolean bootstrap=modified.codePoints().allMatch(TaskPoolSettings::space)&&compact(raw).equals(Integer.toString(setting.fallback()));
                if(!bootstrap&&value!=null){if(value.isNull())return 0;if(value.isIntegralNumber()&&value.canConvertToLong())return value.longValue();if(value.isTextual()){Long parsed=decimal(value.textValue());if(parsed!=null)return parsed;}}
            }
        }catch(org.springframework.dao.DataAccessException|java.io.IOException ignored){}
        Long value=decimal(environment.getProperty(setting.environment()));return value==null?setting.fallback():value;
    }
    private static Long decimal(String text){if(text==null||!text.matches("[+-]?[0-9]+"))return null;try{return Long.parseLong(text);}catch(NumberFormatException ignored){return null;}}
    private static String compact(String raw){StringBuilder out=new StringBuilder();boolean quoted=false,escape=false;for(char c:raw.toCharArray()){if(quoted){out.append(c);if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}else if(c=='"'){out.append(c);quoted=true;}else if(c!=' '&&c!='\t'&&c!='\r'&&c!='\n')out.append(c);}return out.toString();}
    private static boolean space(int cp){return cp>=9&&cp<=13||cp==32||cp==0x85||cp==0xa0||cp==0x1680||cp>=0x2000&&cp<=0x200a||cp==0x2028||cp==0x2029||cp==0x202f||cp==0x205f||cp==0x3000;}
}
