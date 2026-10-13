package com.tencent.weknora.chunker;

import java.io.IOException;

/** Byte-level syntax validation matching encoding/json.Decoder's first-value behavior. */
public final class GoJsonFirstValue {
    private enum State { VALUE, ARRAY_FIRST, OBJECT_FIRST, KEY, STRING, ESCAPE, HEX,
        NEGATIVE, ZERO, INTEGER, DOT, FRACTION, EXPONENT, EXPONENT_SIGN, EXPONENT_DIGITS,
        LITERAL, END }
    private static final int OBJECT_KEY=0, OBJECT_VALUE=1, ARRAY_VALUE=2;
    private final int[] containers=new int[10000];
    private int depth,hex,literalPosition;
    private String literal;
    private State state=State.VALUE;
    private boolean completedContainer;
    private GoJsonFirstValue() { }

    public static int length(byte[] bytes) throws IOException {
        GoJsonFirstValue scan=new GoJsonFirstValue();
        boolean nonSpace=false;
        for (int i=0;i<bytes.length;i++) {
            int c=bytes[i]&255;
            nonSpace|=!space(c);
            if (scan.step(c)) return scan.completedContainer?i+1:i;
        }
        // Decoder reports unexpected EOF for incomplete values, regardless of
        // which lexical error its synthetic final space would have produced.
        try {if (scan.step(' ')) return bytes.length;}
        catch (IOException ignored) { }
        throw new IOException(nonSpace?"unexpected EOF":"EOF");
    }

    private boolean step(int c) throws IOException {
        while (true) {
            switch (state) {
                case VALUE -> {
                    if (space(c)) return false;
                    if (c=='{') {push(OBJECT_KEY,c);state=State.OBJECT_FIRST;}
                    else if (c=='[') {push(ARRAY_VALUE,c);state=State.ARRAY_FIRST;}
                    else if (c=='"') state=State.STRING;
                    else if (c=='-') state=State.NEGATIVE;
                    else if (c=='0') state=State.ZERO;
                    else if (c>='1'&&c<='9') state=State.INTEGER;
                    else if (c=='t'||c=='f'||c=='n') {
                        literal=c=='t'?"true":c=='f'?"false":"null";
                        literalPosition=1;state=State.LITERAL;
                    } else throw invalid(c,"looking for beginning of value");
                    return false;
                }
                case ARRAY_FIRST -> {
                    if (space(c)) return false;
                    if (c==']') return close();
                    state=State.VALUE;
                }
                case OBJECT_FIRST -> {
                    if (space(c)) return false;
                    if (c=='}') return close();
                    state=State.KEY;
                }
                case KEY -> {
                    if (space(c)) return false;
                    if (c!='"') throw invalid(c,"looking for beginning of object key string");
                    state=State.STRING;return false;
                }
                case STRING -> {
                    if (c=='"') state=State.END;
                    else if (c=='\\') state=State.ESCAPE;
                    else if (c<32) throw invalid(c,"in string literal");
                    return false;
                }
                case ESCAPE -> {
                    if (c=='u') {hex=4;state=State.HEX;}
                    else if (c=='b'||c=='f'||c=='n'||c=='r'||c=='t'||c=='\\'||c=='/'||c=='"') state=State.STRING;
                    else throw invalid(c,"in string escape code");
                    return false;
                }
                case HEX -> {
                    if (!(digit(c)||c>='a'&&c<='f'||c>='A'&&c<='F')) throw invalid(c,"in \\u hexadecimal character escape");
                    if (--hex==0) state=State.STRING;
                    return false;
                }
                case NEGATIVE -> {
                    if (c=='0') state=State.ZERO;
                    else if (c>='1'&&c<='9') state=State.INTEGER;
                    else throw invalid(c,"in numeric literal");
                    return false;
                }
                case INTEGER -> {if (digit(c)) return false;state=State.ZERO;}
                case ZERO -> {
                    if (c=='.') {state=State.DOT;return false;}
                    if (c=='e'||c=='E') {state=State.EXPONENT;return false;}
                    state=State.END;
                }
                case DOT -> {
                    if (!digit(c)) throw invalid(c,"after decimal point in numeric literal");
                    state=State.FRACTION;return false;
                }
                case FRACTION -> {
                    if (digit(c)) return false;
                    if (c=='e'||c=='E') {state=State.EXPONENT;return false;}
                    state=State.END;
                }
                case EXPONENT -> {
                    state=State.EXPONENT_SIGN;
                    if (c=='+'||c=='-') return false;
                }
                case EXPONENT_SIGN -> {
                    if (!digit(c)) throw invalid(c,"in exponent of numeric literal");
                    state=State.EXPONENT_DIGITS;return false;
                }
                case EXPONENT_DIGITS -> {if (digit(c)) return false;state=State.END;}
                case LITERAL -> {
                    if (c!=literal.charAt(literalPosition)) throw invalid(c,"in literal "+literal+" (expecting '"+literal.charAt(literalPosition)+"')");
                    if (++literalPosition==literal.length()) state=State.END;
                    return false;
                }
                case END -> {
                    if (depth==0) return true;
                    if (space(c)) return false;
                    int context=containers[depth-1];
                    if (context==OBJECT_KEY) {
                        if (c!=':') throw invalid(c,"after object key");
                        containers[depth-1]=OBJECT_VALUE;state=State.VALUE;
                    } else if (context==OBJECT_VALUE) {
                        if (c=='}') return close();
                        if (c!=',') throw invalid(c,"after object key:value pair");
                        containers[depth-1]=OBJECT_KEY;state=State.KEY;
                    } else {
                        if (c==']') return close();
                        if (c!=',') throw invalid(c,"after array element");
                        state=State.VALUE;
                    }
                    return false;
                }
            }
        }
    }
    private void push(int context,int c) throws IOException {
        if (depth==containers.length) throw invalid(c,"exceeded max depth");
        containers[depth++]=context;
    }
    private boolean close() {
        depth--;state=State.END;
        completedContainer=depth==0;
        return completedContainer;
    }
    private static boolean space(int c) {return c==' '||c=='\t'||c=='\r'||c=='\n';}
    private static boolean digit(int c) {return c>='0'&&c<='9';}
    private static IOException invalid(int c,String context) {return new IOException("invalid character "+quote(c)+" "+context);}
    private static String quote(int c) {
        String value=switch (c) {
            case '\'' -> "\\'";case '"' -> "\"";case '\\' -> "\\\\";
            case 7 -> "\\a";case 8 -> "\\b";case 9 -> "\\t";case 10 -> "\\n";
            case 11 -> "\\v";case 12 -> "\\f";case 13 -> "\\r";
            default -> c<32||c==127?String.format("\\x%02x",c):c>=128&&c<=160||c==173?String.format("\\u%04x",c):Character.toString(c);
        };
        return "'"+value+"'";
    }
}
