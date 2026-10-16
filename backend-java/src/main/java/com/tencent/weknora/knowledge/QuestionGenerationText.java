package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Go Doc2Query prompt, line parsing, image evidence and index source identifiers. */
public final class QuestionGenerationText {
  private final ObjectMapper json;private final String prompt;private final SummaryGenerationText language;
  public QuestionGenerationText(ObjectMapper json){this(json,null);}
  public QuestionGenerationText(ObjectMapper json,String custom){this.json=json;language=new SummaryGenerationText(json,"{{language}}",1,1);try(var in=getClass().getResourceAsStream("/question-generation-prompt.json")){prompt=custom==null?json.readTree(Objects.requireNonNull(in)).path("prompt").asText():custom;}catch(IOException e){throw new UncheckedIOException(e);}}
  public boolean configured(){return !SummaryGenerationText.trim(prompt).isEmpty();}
  public String prompt(String content,String previous,String next,String title,String locale,int count,String instructions) {
    String context="";if(!previous.isEmpty()||!next.isEmpty()){context="<surrounding_context>\n";if(!previous.isEmpty())context+="<preceding_content>\n"+previous+"\n\n</preceding_content>\n\n";if(!next.isEmpty())context+="<following_content>\n"+next+"\n\n</following_content>\n\n";context+="</surrounding_context>\n\n";}
    var values=new HashMap<String,String>(Map.of("question_count",Integer.toString(count),"content",content,"context",context,"doc_name",title,"language",language.prompt(locale)));var today=java.time.LocalDate.now();values.put("current_time",today.toString());values.put("yesterday",today.minusDays(1).toString());values.put("current_week",today.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL,Locale.ENGLISH));
    Matcher m=Pattern.compile("\\{\\{([^{}]+)}}").matcher(SummaryGenerationText.trim(prompt));var out=new StringBuffer();while(m.find()){String key=m.group(1);String value=values.get(key);m.appendReplacement(out,Matcher.quoteReplacement(value==null?m.group():value));}m.appendTail(out);
    String instruction=SummaryGenerationText.trim(instructions);if(instruction.isEmpty())return out.toString();return SummaryGenerationText.trim(out.toString())+"\n\n<question_generation_business_instructions>\n"+instruction+"\n</question_generation_business_instructions>\nApply these business instructions only when they do not conflict with the system-owned output format, citation, safety, or factuality rules.";
  }
  public ObjectNode options(){return json.createObjectNode().put("temperature",.7).put("max_tokens",512).put("thinking",false);}
  public List<String> parse(JsonNode response,int count) {
    if(response==null)throw new IllegalArgumentException("question model returned no response");var questions=new ArrayList<String>();for(String line:response.path("content").asText().split("\n",-1)){line=SummaryGenerationText.trim(line);line=line.replaceFirst("^[0123456789.\\-*\\) ]+","");line=SummaryGenerationText.trim(line);if(!line.isEmpty()&&line.getBytes(StandardCharsets.UTF_8).length>5){questions.add(line);if(questions.size()>=count)break;}}return List.copyOf(questions);
  }
  static int count(int count){return count<=0?3:Math.min(count,10);}
  static String sourceId(String chunk,String question) {String value=chunk+"-"+question;if(value.getBytes(StandardCharsets.UTF_8).length<=64)return value;try{return chunk+"-q"+HexFormat.of().formatHex(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(question.getBytes(StandardCharsets.UTF_8)),12));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  static String enrich(String content,List<ObjectNode> images) {
    if(images.isEmpty())return content;var urls=new HashMap<String,ObjectNode>();for(var image:images)for(String key:List.of("url","original_url"))if(!image.path(key).asText().isEmpty())urls.put(image.path(key).asText(),image);
    var seen=new HashSet<String>();Matcher m=Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)").matcher(content);var out=new StringBuffer();while(m.find()){String url=m.group(2);seen.add(url);String block="<image url=\""+url+"\">\n<image_original>"+m.group()+"</image_original>\n"+inner(urls.get(url))+"</image>";m.appendReplacement(out,Matcher.quoteReplacement(block));}m.appendTail(out);var extras=new ArrayList<String>();for(var image:images)if(!seen.contains(image.path("url").asText())&&!seen.contains(image.path("original_url").asText())){String inner=inner(image),url=image.path("url").asText();if(url.isEmpty())url=image.path("original_url").asText();if(!inner.isEmpty())extras.add("<image url=\""+url+"\">\n"+inner+"</image>");}if(!extras.isEmpty()){if(!out.isEmpty())out.append('\n');out.append(String.join("\n",extras));}return out.toString();
  }
  static boolean extractable(String content) {
    StringBuilder out=new StringBuilder();int last=0;
    for(int i=0;i+1<content.length();i++){if(content.charAt(i)!='!'||content.charAt(i+1)!='['||escaped(content,i))continue;int alt=-1;for(int j=i+2;j+1<content.length();j++)if(content.charAt(j)==']'&&content.charAt(j+1)=='('&&!escaped(content,j)){alt=j;break;}if(alt<0)continue;
      int start=alt+2,depth=1,end=-1;boolean angle=false,seen=false;char quote=0;for(int j=start;j<content.length();j++){char c=content.charAt(j);if(c=='\\'){j++;continue;}if(!seen&&!space(c)){seen=true;if(c=='<'){angle=true;continue;}}if(angle){if(c=='>')angle=false;continue;}if(quote!=0){if(c==quote)quote=0;continue;}if((c=='"'||c=='\'')&&j>start&&space(content.charAt(j-1))){quote=c;continue;}if(c=='(')depth++;else if(c==')'&&--depth==0){end=j;break;}}
      if(end<0){i=alt;continue;}out.append(content,last,i);last=end+1;i=end;
    }
    out.append(content,last,content.length());return SummaryGenerationText.realTextLength(out.toString())>0;
  }
  private static boolean escaped(String value,int index){int n=0;for(int i=index-1;i>=0&&value.charAt(i)=='\\';i--)n++;return n%2!=0;}
  private static boolean space(char c){return c==' '||c=='\t'||c=='\n'||c=='\r';}
  private static String inner(JsonNode image){if(image==null)return "";String value="";for(String f:List.of("caption","ocr_text"))if(!image.path(f).asText().isEmpty()){String tag=f.equals("caption")?"image_caption":"image_ocr";value+="<"+tag+">"+image.path(f).asText()+"</"+tag+">\n";}return value;}
}
