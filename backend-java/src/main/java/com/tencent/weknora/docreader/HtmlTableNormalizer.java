package com.tencent.weknora.docreader;

import com.google.re2j.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native HTML-table normalization for OCR Markdown; conversion uses an HTML5 DOM. */
public final class HtmlTableNormalizer {
    private static final Pattern TABLE = Pattern.compile("(?is)<table\\b[^>]*>.*?</table>");
    private static final Pattern FENCE = Pattern.compile("(?s)(?:```|~~~)[^\\n]*\\n.*?(?:```|~~~)");
    private static final Pattern LAYOUT = Pattern.compile("(?is)\\s+(?:style|class|align|valign|width|height|bgcolor)\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern SPAN = Pattern.compile("(?i)\\b(?:row|col)span\\s*=\\s*[\"']?(?:[2-9]|\\d{2,})");
    private static final Pattern ROW = Pattern.compile("(?i)<tr\\b");
    private static final Pattern SEPARATOR = Pattern.compile("(?m)^\\s*\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?\\s*$");
    private static final Pattern ESCAPED_IMAGE = Pattern.compile("!\\\\\\[(.*?)\\\\?\\]\\(([^()\\n]*(?:\\([^)]*\\)[^()\\n]*)*)\\)");
    private static final Pattern COLLAPSE = Pattern.compile("[ \\t\\n\\r\\f]+");
    private static final Set<String> REMOVE = Set.of("head","script","style","link","meta","iframe","noscript","input","textarea");
    private HtmlTableNormalizer() { }

    public static String normalize(String markdown) {
        if (!markdown.toLowerCase(Locale.ROOT).contains("<table")) return markdown;
        List<int[]> fences = new ArrayList<>();
        var fence = FENCE.matcher(markdown);
        while (fence.find()) fences.add(new int[]{fence.start(),fence.end()});
        var matches = TABLE.matcher(markdown);
        StringBuilder output = new StringBuilder();
        int last=0;
        while (matches.find()) {
            output.append(markdown,last,matches.start());
            String block=matches.group();
            if (inside(matches.start(),fences)) {output.append(block);last=matches.end();continue;}
            while (!output.isEmpty() && output.charAt(output.length()-1)=='\n') output.setLength(output.length()-1);
            output.append("\n\n").append(normalizeTable(block));
            last=matches.end();
            while (last<markdown.length() && markdown.charAt(last)=='\n') last++;
            output.append("\n\n");
        }
        output.append(markdown,last,markdown.length());
        return output.toString();
    }
    private static boolean inside(int position,List<int[]> ranges) {
        for (int[] range:ranges) if (position>=range[0] && position<range[1]) return true;
        return false;
    }
    private static String normalizeTable(String block) {
        if (SPAN.matcher(block).find()) return fallback(block);
        Element table=Jsoup.parseBodyFragment(block).selectFirst("table");
        if (table==null || table.attr("role").equals("presentation")) return fallback(block);
        table.select("head,script,style,link,meta,iframe,noscript,input,textarea").remove();
        if (!table.select("table table").isEmpty()) {
            StringBuilder nested=new StringBuilder();
            for (Node child:table.childNodes()) nested.append(render(child));
            String converted=trim(nested.toString());
            return SEPARATOR.matcher(converted).find()?converted:fallback(block);
        }
        if (!table.select("h1,h2,h3,h4,h5,h6,hr,ul,ol,blockquote").isEmpty()) return fallback(block);
        normalizeInlineDom(table);
        List<Element> rows=new ArrayList<>(table.select("tr"));
        Element header=table.selectFirst("thead tr");
        if (header==null) {Element first=table.selectFirst("th");if (first!=null) header=first.parent();}
        List<List<String>> cells=new ArrayList<>();
        List<String> alignment=new ArrayList<>();
        if (header==null) cells.add(new ArrayList<>());
        else {cells.add(cells(header));rows.remove(header);}
        Element firstRow=header==null?(rows.isEmpty()?null:rows.getFirst()):header;
        if (firstRow!=null) for (Element cell:firstRow.select("td,th")) alignment.add(cell.attr("align"));
        for (Element row:rows) cells.add(cells(row));
        int count=0;
        for (List<String> row:cells) count=Math.max(count,row.size());
        if (count==0) return fallback(block);
        int[] widths=new int[count];java.util.Arrays.fill(widths,1);
        for (List<String> row:cells) for (int i=0;i<row.size();i++) {
            String content=row.get(i);
            if (content.contains("\n")) return fallback(block);
            widths[i]=Math.max(widths[i],content.codePointCount(0,content.length()));
        }
        StringBuilder result=new StringBuilder();
        appendRow(result,cells.getFirst(),widths);
        result.append('\n').append('|');
        for (int i=0;i<count;i++) {
            String align=i<alignment.size()?alignment.get(i):"";
            result.append(align.equals("left")||align.equals("center")?':':'-')
                    .append("-".repeat(widths[i])).append(align.equals("right")||align.equals("center")?':':'-').append('|');
        }
        for (List<String> row:cells.subList(1,cells.size())) {result.append('\n');appendRow(result,row,widths);}
        Element caption=table.selectFirst("caption");
        if (caption!=null) result.append("\n\n").append(trim(render(caption)));
        String converted=ESCAPED_IMAGE.matcher(trim(result.toString())).replaceAll("![$1]($2)");
        return SEPARATOR.matcher(converted).find()?converted:fallback(block);
    }
    private static List<String> cells(Element row) {
        List<String> output=new ArrayList<>();
        for (Element cell:row.select("td,th")) output.add(trim(render(cell)));
        return output;
    }
    private static void appendRow(StringBuilder result,List<String> row,int[] widths) {
        result.append('|');
        for (int i=0;i<widths.length;i++) {
            String value=i<row.size()?row.get(i):"";
            int length=value.codePointCount(0,value.length());
            result.append(' ').append(value).append(" ".repeat(widths[i]-length)).append(" |");
        }
    }
    private static String fallback(String block) {
        block=LAYOUT.matcher(block).replaceAll("");
        var rows=ROW.matcher(block);
        StringBuilder output=new StringBuilder();int last=0;
        while (rows.find()) {
            output.append(block,last,rows.start());
            if (rows.start()==0 || block.charAt(rows.start()-1)!='\n') output.append('\n');
            output.append(block,rows.start(),rows.end());last=rows.end();
        }
        output.append(block,last,block.length());
        return trim(output.toString());
    }
    private static boolean bold(String tag) {return tag.equals("b")||tag.equals("strong");}
    private static boolean italic(String tag) {return tag.equals("i")||tag.equals("em");}
    private static void normalizeInlineDom(Element root) {
        for (Element element:new ArrayList<>(root.children())) {
            normalizeInlineDom(element);
            if (bold(root.normalName()) && bold(element.normalName()) || italic(root.normalName()) && italic(element.normalName())) element.unwrap();
        }
        List<Node> children=new ArrayList<>(root.childNodes());
        for (int i=1;i<children.size();i++) {
            Node left=children.get(i-1),right=children.get(i);
            if (left instanceof Element a && right instanceof Element b
                    && (bold(a.normalName())||italic(a.normalName())) && (bold(b.normalName())||italic(b.normalName()))) {
                for (Node child:new ArrayList<>(b.childNodes())) a.appendChild(child);
                b.remove();children.set(i,a);
            }
        }
    }
    private static String render(Node node) {
        if (node instanceof TextNode text) return escape(COLLAPSE.matcher(text.getWholeText()).replaceAll(" "));
        if (!(node instanceof Element element)) return "";
        String tag=element.normalName();
        if (REMOVE.contains(tag)) return "";
        if (tag.equals("table")) return "\n\n"+normalizeTable(element.outerHtml())+"\n\n";
        if (tag.equals("img")) {
            String src=trim(element.attr("src"));if (src.isEmpty()) return "";
            String alt=element.attr("alt").replace('\n',' ');
            alt=alt.replace("[","\\[").replace("]","\\]");
            return "!["+alt+"]("+url(src)+title(element.attr("title"))+")";
        }
        if (Set.of("code","var","samp","kbd","tt").contains(tag)) {
            String code=element.wholeText();
            if (code.isEmpty()) return "";
            if (!trim(code).isEmpty()) code=COLLAPSE.matcher(trim(code)).replaceAll(" ");
            int max=0,current=0;
            for (char ch:code.toCharArray()) {if (ch=='`') {current++;max=Math.max(max,current);}else current=0;}
            String fence="`".repeat(max+1);
            if (code.startsWith("`")) code=" "+code;
            if (code.endsWith("`")) code+=" ";
            return fence+code+fence;
        }
        StringBuilder children=new StringBuilder();for (Node child:element.childNodes()) children.append(render(child));
        String content=children.toString();
        if (tag.equals("br")) return "\n";
        if (tag.equals("pre")) return "\n\n```\n"+content+"\n```\n\n";
        if (bold(tag)||italic(tag)) return delimit(content,bold(tag)?"**":"*");
        if (tag.equals("a")) {
            String href=trim(element.attr("href"));
            return surround(content,"["+trim(content).replace("]","\\]")+"]("+url(href)+(href.isEmpty()?"":title(element.attr("title")))+")");
        }
        if (Set.of("tr","p","div","section","article","header","footer","address","figure","figcaption").contains(tag)) return "\n\n"+content+"\n\n";
        return content;
    }
    private static String surround(String value,String body) {
        int start=0,end=value.length();
        while (start<end && GoUtf8.isSpace(value.codePointAt(start))) start+=Character.charCount(value.codePointAt(start));
        while (end>start && GoUtf8.isSpace(value.codePointBefore(end))) end-=Character.charCount(value.codePointBefore(end));
        return value.substring(0,start)+body+value.substring(end);
    }
    private static String delimit(String content,String delimiter) {
        if (trim(content).isEmpty()) return content;
        return surround(content,delimiter+trim(content)+delimiter);
    }
    private static String url(String value) {return value.replace(" ","%20").replace("(","%28").replace(")","%29");}
    private static String title(String value) {
        value=value.replace('\n',' ');
        if (value.isEmpty()) return "";
        if (!value.contains("\"")) return " \""+value+"\"";
        if (!value.contains("'")) return " '"+value+"'";
        return " \""+value.replace("\"","\\\"")+"\"";
    }
    private static String escape(String content) {
        content=content.replace("<","&lt;").replace(">","&gt;");
        StringBuilder output=new StringBuilder();
        for (int i=0;i<content.length();i++) {
            char ch=content.charAt(i);
            boolean escaped=ch=='\\'||ch=='|'||ch=='`'
                    || (ch=='*'||ch=='_') && i+1<content.length() && !GoUtf8.isSpace(content.codePointAt(i+1))
                    || ch=='[' && content.indexOf(']',i+1)>=0
                    || ch=='#' && trim(content.substring(0,i)).isEmpty() && i+1<content.length() && GoUtf8.isSpace(content.codePointAt(i+1))
                    || ch=='.' && content.substring(0,i).matches("[0-9]+") && i+1<content.length() && content.charAt(i+1)==' '
                    || i==0 && (ch=='-'||ch=='+'||ch=='=') && (content.startsWith("---")||content.startsWith("===")||content.startsWith("- ")||content.startsWith("+ "));
            if (escaped) output.append('\\');
            output.append(ch);
        }
        return output.toString();
    }
    private static String trim(String text) {
        int start=0,end=text.length();
        while (start<end && GoUtf8.isSpace(text.codePointAt(start))) start+=Character.charCount(text.codePointAt(start));
        while (end>start && GoUtf8.isSpace(text.codePointBefore(end))) end-=Character.charCount(text.codePointBefore(end));
        return text.substring(start,end);
    }
}
