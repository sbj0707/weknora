package com.tencent.weknora.model.client;

/** Go whitespace and special-token handling for protocol-owned outbound copies. */
final class ModelText {
    private ModelText(){}
    static String trim(String text){int start=0,end=text.length();while(start<end&&space(text.codePointAt(start)))start+=Character.charCount(text.codePointAt(start));while(end>start&&space(text.codePointBefore(end)))end-=Character.charCount(text.codePointBefore(end));return text.substring(start,end);}
    private static boolean space(int c){return c>=9&&c<=13||c==32||c==0x85||c==0xa0||c==0x1680||c>=0x2000&&c<=0x200a||c==0x2028||c==0x2029||c==0x202f||c==0x205f||c==0x3000;}
    static String neutralize(String text){String[][] tokens={{"<|","<\u200b|"},{"<｜","<\u200b｜"},{"<start_of_turn>","<\u200bstart_of_turn>"},{"<end_of_turn>","<\u200bend_of_turn>"},{"[INST]","[\u200bINST]"},{"[/INST]","[\u200b/INST]"},{"<<SYS>>","<\u200b<SYS>>"},{"<</SYS>>","<\u200b</SYS>>"}};for(String[] t:tokens)text=text.replace(t[0],t[1]);return text;}
}
