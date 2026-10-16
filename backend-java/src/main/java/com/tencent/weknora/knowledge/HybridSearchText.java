package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Go Unicode simple lowercase and bytewise string ordering. */
final class HybridSearchText {
  private static final Map<Integer,Integer> LOWER=new HashMap<>();
  static{try(var in=HybridSearchText.class.getResourceAsStream("/go-source-unicode.json")){for(var pair:new ObjectMapper().readTree(in).path("lower"))LOWER.put(pair.get(0).asInt(),pair.get(1).asInt());}catch(Exception e){throw new ExceptionInInitializerError(e);}}
  static String normalized(String raw){var text=new StringBuilder();SummaryGenerationText.trim(raw).codePoints().forEach(cp->text.appendCodePoint(LOWER.getOrDefault(cp,cp)));return text.toString();}
  static int compare(String a,String b){return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
}
