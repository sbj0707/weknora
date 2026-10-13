package com.tencent.weknora.docreader;

import java.nio.charset.StandardCharsets;

/** Content-based MIME signatures and order used by Go net/http.DetectContentType. */
final class GoContentSniffer {
    private GoContentSniffer() { }
    static String detect(byte[] input) {
        byte[] data = input.length > 512 ? java.util.Arrays.copyOf(input, 512) : input;
        int skip = 0;
        while (skip < data.length && whitespace(data[skip] & 255)) skip++;
        for (String tag : new String[]{"<!DOCTYPE HTML", "<HTML", "<HEAD", "<SCRIPT", "<IFRAME", "<H1", "<DIV", "<FONT", "<TABLE", "<A", "<STYLE", "<TITLE", "<B", "<BODY", "<BR", "<P", "<!--"}) {
            if (data.length < skip + tag.length() + 1) continue;
            boolean match = true;
            for (int i=0;i<tag.length();i++) {
                int value = data[skip+i] & 255;
                if (value >= 'a' && value <= 'z') value -= 32;
                if (value != tag.charAt(i)) { match = false; break; }
            }
            int end = data[skip+tag.length()] & 255;
            if (match && (end == ' ' || end == '>')) return "text/html; charset=utf-8";
        }
        if (prefix(data, skip, "<?xml")) return "text/xml; charset=utf-8";
        if (prefix(data, 0, "%PDF-")) return "application/pdf";
        if (prefix(data, 0, "%!PS-Adobe-")) return "application/postscript";
        if (data.length >= 4 && prefix(data, 0, "\376\377")) return "text/plain; charset=utf-16be";
        if (data.length >= 4 && prefix(data, 0, "\377\376")) return "text/plain; charset=utf-16le";
        if (data.length >= 4 && prefix(data, 0, "\357\273\277")) return "text/plain; charset=utf-8";
        if (prefix(data, 0, "\000\000\001\000") || prefix(data, 0, "\000\000\002\000")) return "image/x-icon";
        if (prefix(data, 0, "BM")) return "image/bmp";
        if (prefix(data, 0, "GIF87a") || prefix(data, 0, "GIF89a")) return "image/gif";
        if (prefix(data, 0, "RIFF") && prefix(data, 8, "WEBPVP")) return "image/webp";
        if (prefix(data, 0, "\211PNG\r\n\032\n")) return "image/png";
        if (prefix(data, 0, "\377\330\377")) return "image/jpeg";
        if (prefix(data, 0, "FORM") && prefix(data, 8, "AIFF")) return "audio/aiff";
        if (prefix(data, 0, "ID3")) return "audio/mpeg";
        if (prefix(data, 0, "OggS\000")) return "application/ogg";
        if (prefix(data, 0, "MThd\000\000\000\006")) return "audio/midi";
        if (prefix(data, 0, "RIFF") && prefix(data, 8, "AVI ")) return "video/avi";
        if (prefix(data, 0, "RIFF") && prefix(data, 8, "WAVE")) return "audio/wave";
        if (mp4(data)) return "video/mp4";
        if (prefix(data, 0, "\032E\337\243")) return "video/webm";
        if (data.length >= 36 && data[34] == 'L' && data[35] == 'P') return "application/vnd.ms-fontobject";
        if (prefix(data, 0, "\000\001\000\000")) return "font/ttf";
        if (prefix(data, 0, "OTTO")) return "font/otf";
        if (prefix(data, 0, "ttcf")) return "font/collection";
        if (prefix(data, 0, "wOFF")) return "font/woff";
        if (prefix(data, 0, "wOF2")) return "font/woff2";
        if (prefix(data, 0, "\037\213\010")) return "application/x-gzip";
        if (prefix(data, 0, "PK\003\004")) return "application/zip";
        if (prefix(data, 0, "Rar!\032\007\000") || prefix(data, 0, "Rar!\032\007\001\000")) return "application/x-rar-compressed";
        if (prefix(data, 0, "\000asm")) return "application/wasm";
        for (int i=skip;i<data.length;i++) {
            int value = data[i] & 255;
            if (value <= 8 || value == 11 || value >= 14 && value <= 26 || value >= 28 && value <= 31) return "application/octet-stream";
        }
        return "text/plain; charset=utf-8";
    }
    private static boolean whitespace(int value) { return value == 9 || value == 10 || value == 12 || value == 13 || value == 32; }
    private static boolean prefix(byte[] data, int at, String signature) {
        byte[] expected = signature.getBytes(StandardCharsets.ISO_8859_1);
        if (at + expected.length > data.length) return false;
        for (int i=0;i<expected.length;i++) if (data[at+i] != expected[i]) return false;
        return true;
    }
    private static boolean mp4(byte[] data) {
        if (data.length < 12) return false;
        long size = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(data).getInt());
        if (size > data.length || size % 4 != 0 || !prefix(data, 4, "ftyp")) return false;
        for (int at=8;at<size;at+=4) {
            if (at == 12) continue;
            if (prefix(data, at, "mp4")) return true;
        }
        return false;
    }
}
