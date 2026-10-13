package com.tencent.weknora.docreader;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Native Go SimpleFormatReader equivalent; no Python service is needed for these formats. */
@Component
public final class SimpleFormatReader implements DocumentReader {
    private static final Set<String> TEXT = Set.of("md", "markdown", "txt", "text");
    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "gif", "bmp", "tiff", "webp");
    private static final Set<String> AUDIO = Set.of("mp3", "wav", "m4a", "flac", "ogg");

    public static boolean isSimpleFormat(String type) { String ft = normalize(type); return TEXT.contains(ft) || IMAGES.contains(ft) || AUDIO.contains(ft) || "csv".equals(ft) || "json".equals(ft); }
    public static boolean isImageFormat(String type) { return IMAGES.contains(normalize(type)); }
    public static boolean isAudioFormat(String type) { return AUDIO.contains(normalize(type)); }

    @Override public DocumentReadResult read(DocumentReadRequest request) {
        String ft = type(request);
        byte[] bytes = request.fileContent();
        if (TEXT.contains(ft)) return text(GoUtf8.decode(bytes));
        if ("csv".equals(ft)) return text(GoCsv.toMarkdown(bytes));
        if ("json".equals(ft)) return text(JsonDocumentConverter.toMarkdown(bytes));
        if (IMAGES.contains(ft)) {
            String name = request.fileName().isEmpty() ? "image.png" : request.fileName();
            String reference = ("images/" + name).replace(" ", "%20");
            return new DocumentReadResult("![" + name + "](" + reference + ")",
                    List.of(new DocumentReadResult.ImageRef(name, reference, GoContentSniffer.detect(bytes), "", bytes, true)), "", Map.of(), "", List.of());
        }
        if (AUDIO.contains(ft)) {
            String name = request.fileName().isEmpty() ? "audio.mp3" : request.fileName();
            return new DocumentReadResult("[Audio file: " + name + "]", List.of(), "", Map.of(), "", List.of(), true, bytes);
        }
        throw new IllegalArgumentException("unsupported simple format: " + ft);
    }

    /** Preserve standalone image evidence after an OCR reader returns only text. */
    public static DocumentReadResult ensureOriginalImage(DocumentReadRequest request, DocumentReadResult result) {
        String ft = type(request);
        if (!IMAGES.contains(ft) || request.fileContent().length == 0) return result;
        String name = request.fileName().isEmpty() ? "image." + ft : request.fileName();
        String reference = "images/" + name;
        if (result.markdownContent().contains(reference)) return result;
        String line = "![" + name + "](" + reference + ")";
        String markdown = result.markdownContent().codePoints().allMatch(GoUtf8::isSpace) ? line : line + "\n\n" + result.markdownContent();
        var images = new java.util.ArrayList<>(result.imageRefs());
        images.add(new DocumentReadResult.ImageRef(name, reference, GoContentSniffer.detect(request.fileContent()), "", request.fileContent(), true));
        return new DocumentReadResult(markdown, images, result.imageDirPath(), result.metadata(), result.error(), result.sourceBlocks(), result.isAudio(), result.audioData());
    }
    private static DocumentReadResult text(String value) { return new DocumentReadResult(value, List.of(), "", Map.of(), "", List.of()); }
    private static String type(DocumentReadRequest request) {
        String ft = normalize(request.fileType());
        if (!ft.isEmpty()) return ft;
        String name = request.fileName();
        int slash = name.lastIndexOf('/'), dot = name.lastIndexOf('.');
        return dot > slash ? normalize(name.substring(dot + 1)) : "";
    }
    private static String normalize(String type) { return (type.startsWith(".") ? type.substring(1) : type).toLowerCase(Locale.ROOT); }
}
