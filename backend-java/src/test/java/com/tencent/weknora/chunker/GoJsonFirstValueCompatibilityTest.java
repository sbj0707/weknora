package com.tencent.weknora.chunker;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GoJsonFirstValueCompatibilityTest {
    @Test void actualGoDecoderByteInputs() throws Exception {
        int count=0;
        try (var reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(
                getClass().getResourceAsStream("/go-json-first-value.tsv.gz")),StandardCharsets.UTF_8))) {
            for (String line; (line=reader.readLine())!=null;) {
                String[] fields=line.split("\t",-1);
                String expected=new String(Base64.getDecoder().decode(fields[1]),StandardCharsets.UTF_8);
                String actual="";
                int length=0;
                try {length=GoJsonFirstValue.length(Base64.getDecoder().decode(fields[0]));}
                catch (IOException ex) {actual=ex.getMessage();}
                assertEquals(expected,actual,"Go scanner input #"+count+" base64="+fields[0]);
                if (expected.isEmpty()) assertEquals(Integer.parseInt(fields[2]),length,"first-value boundary #"+count);
                count++;
            }
        }
        assertEquals(7039,count);
    }
}
