package com.tencent.weknora.model.client;
import java.io.*;import java.nio.charset.StandardCharsets;
/** Go encoding/base64 StdEncoding syntax and CorruptInputError byte offsets. */
final class GoBase64 {
    static void validate(String text)throws IOException{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);int at=0;while(at<bytes.length){for(int j=0;j<4;j++){if(at==bytes.length){if(j==0)return;throw bad(at-j);}int value=bytes[at++]&255;if(value>='A'&&value<='Z'||value>='a'&&value<='z'||value>='0'&&value<='9'||value=='+'||value=='/')continue;if(value=='\n'||value=='\r'){j--;continue;}if(value!='='||j<2)throw bad(at-1);if(j==2){while(at<bytes.length&&(bytes[at]=='\n'||bytes[at]=='\r'))at++;if(at==bytes.length)throw bad(at);if(bytes[at]!='=')throw bad(at-1);at++;}while(at<bytes.length&&(bytes[at]=='\n'||bytes[at]=='\r'))at++;if(at<bytes.length)throw bad(at);return;}}}
    private static IOException bad(int offset){return new IOException("illegal base64 data at input byte "+offset);}
}
