package com.tencent.weknora.chunker;

import com.tencent.weknora.docreader.HtmlTableNormalizer;
import com.tencent.weknora.security.CurrentAuth;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChunkerPreviewController {
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    @PreDestroy void close() {workers.shutdownNow();}
    @PostMapping("/api/v1/chunker/preview")
    public ResponseEntity<?> preview(HttpServletRequest http) throws IOException {
        CurrentAuth.get(http).requireRole("viewer");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        PreviewRequestDecoder.Request request;
        try {request=PreviewRequestDecoder.decode(http.getInputStream().readAllBytes());}
        catch (IOException ex) {return failure(400,"invalid request body: "+ex.getMessage());}
        if (RuneText.trim(request.text()).isEmpty()) return failure(400,"text is empty — paste a sample to preview chunking");
        if (RuneText.length(request.text())>65536) return ResponseEntity.status(413).body(Map.of("success",false,"error","text exceeds preview limit","limit",65536));
        String text=HtmlTableNormalizer.normalize(AdaptiveChunker.normalizeLineEndings(request.text()));
        SplitterConfig config=request.config().normalized();
        var computation=workers.submit(() -> {
            if (request.parentChild()) {
                var pair=AdaptiveChunker.deriveParentChildConfigs(config,request.parentSize(),request.childSize());
                var split=AdaptiveChunker.splitParentChildWithDiagnostics(text,pair.parent(),pair.child());
                return new AdaptiveChunker.Result(split.result().children().stream().map(TextSplitter.ChildChunk::chunk).toList(),split.diagnostics());
            }
            return AdaptiveChunker.splitWithDiagnostics(text,config);
        });
        AdaptiveChunker.Result split;
        try {split=computation.get(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch (TimeoutException ex) {return failure(504,"chunker preview timed out");}
        catch (InterruptedException ex) {Thread.currentThread().interrupt();return failure(504,"chunker preview timed out");}
        catch (java.util.concurrent.ExecutionException ex) {throw new IllegalStateException("Chunker preview failed",ex.getCause());}
        DocumentProfile profile=split.diagnostics().profile()==null?DocumentProfile.of(text):split.diagnostics().profile();
        String language=profile.detectedLangs()==null||profile.detectedLangs().isEmpty()?"mixed":profile.detectedLangs().getFirst();
        List<Chunk> chunks=split.chunks();
        int[] sizes=chunks.stream().mapToInt(chunk -> RuneText.length(chunk.content())).toArray();
        Map<String,Object> stats=stats(sizes);
        if (chunks.size()>500) stats.put("truncated_to",chunks.size());
        List<Map<String,Object>> results=new ArrayList<>();
        for (int i=0;i<Math.min(500,chunks.size());i++) {
            Chunk chunk=chunks.get(i);Map<String,Object> value=new LinkedHashMap<>();
            value.put("seq",chunk.seq());value.put("start",chunk.start());value.put("end",chunk.end());
            value.put("size_chars",sizes[i]);value.put("size_tokens_approx",TokenEstimator.approximate(sizes[i],language));
            if (!chunk.contextHeader().isEmpty()) value.put("context_header",chunk.contextHeader());
            value.put("content",chunk.content());results.add(value);
        }
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("selected_tier",split.diagnostics().selectedTier());data.put("tier_chain",split.diagnostics().tierChain());
        data.put("rejected",split.diagnostics().rejected());data.put("profile",profile);data.put("chunks",results);data.put("stats",stats);
        return ResponseEntity.ok(Map.of("success",true,"data",data));
    }
    private static ResponseEntity<?> failure(int status,String message) {return ResponseEntity.status(status).body(Map.of("success",false,"error",message));}
    private static Map<String,Object> stats(int[] sizes) {
        Map<String,Object> stats=new LinkedHashMap<>();
        int min=Integer.MAX_VALUE,max=0;double sum=0,sumSquared=0;
        for (int size:sizes) {min=Math.min(min,size);max=Math.max(max,size);sum+=size;sumSquared+=(double)size*size;}
        double average=sizes.length==0?0:sum/sizes.length,variance=sizes.length==0?0:Math.max(0,sumSquared/sizes.length-average*average);
        stats.put("count",sizes.length);stats.put("avg_chars",(int)(average+0.5));stats.put("min_chars",sizes.length==0?0:min);
        stats.put("max_chars",max);stats.put("stddev_chars",(int)(Math.sqrt(variance)+0.5));return stats;
    }
}
