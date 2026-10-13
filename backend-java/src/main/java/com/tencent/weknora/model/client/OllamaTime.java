package com.tencent.weknora.model.client;
import java.io.*;import java.time.*;
/** Go time.Time JSON timestamp validation; these unused SDK times are not returned by the calling SPI. */
final class OllamaTime {
    private static final String LAYOUT="2006-01-02T15:04:05Z07:00";
    private final String source;private int at;private OllamaTime(String source){this.source=source;}
    static void validate(String value)throws IOException{OllamaTime p=new OllamaTime(value);int year=p.number("2006",4,4,0,9999,"year"),month;p.literal("-");month=p.number("01",2,2,1,12,"month");p.literal("-");int day=p.number("02",2,2,1,31,"day");p.literal("T");p.number("15",1,2,0,23,"hour");p.literal(":");p.number("04",2,2,0,59,"minute");p.literal(":");p.number("05",2,2,0,59,"second");
        if(p.at<value.length()&&(value.charAt(p.at)=='.'||value.charAt(p.at)==',')){int begin=p.at++;while(p.at<value.length()&&digit(value.charAt(p.at)))p.at++;if(p.at==begin+1)p.at=begin;}
        if(p.at<value.length()&&value.charAt(p.at)=='Z')p.at++;else{int begin=p.at;if(p.at>=value.length()||(value.charAt(p.at)!='+'&&value.charAt(p.at)!='-'))throw p.parse("Z07:00",p.rest());p.at++;int hour=p.numberZone(begin,2);if(p.at>=value.length()||value.charAt(p.at)!=':')throw p.parse("Z07:00",value.substring(begin));p.at++;int minute=p.numberZone(begin,2);if(hour>24)throw p.range("time zone offset hour",value.substring(begin));if(minute>60)throw p.range("time zone offset minute",value.substring(begin));}
        try{LocalDate.of(year,month,day);}catch(DateTimeException ex){throw p.range("day","");}if(p.at!=value.length())throw new IOException("parsing time "+quote(value)+": extra text: "+quote(p.rest()));
    }
    private int numberZone(int begin,int size)throws IOException{int start=at;while(at<source.length()&&at-start<size&&digit(source.charAt(at)))at++;if(at-start!=size)throw parse("Z07:00",source.substring(begin));return Integer.parseInt(source.substring(start,at));}
    private int number(String layout,int minDigits,int maxDigits,int low,int high,String kind)throws IOException{int start=at;while(at<source.length()&&at-start<maxDigits&&digit(source.charAt(at)))at++;if(at-start<minDigits)throw parse(layout,source.substring(start));int value=Integer.parseInt(source.substring(start,at));if(value<low||value>high)throw range(kind,source.substring(at));return value;}
    private void literal(String literal)throws IOException{if(!source.startsWith(literal,at))throw parse(literal,rest());at+=literal.length();}
    private String rest(){return source.substring(at);}
    private IOException parse(String layout,String value){return new IOException("parsing time "+quote(source)+" as "+quote(LAYOUT)+": cannot parse "+quote(value)+" as "+quote(layout));}
    private IOException range(String kind,String value){return new IOException("parsing time "+quote(source)+": "+kind+" out of range");}
    private static boolean digit(char c){return c>='0'&&c<='9';}
    private static String quote(String s){try{return OllamaJson.JSON.writeValueAsString(s);}catch(IOException ex){throw new IllegalArgumentException(ex);}}
}
