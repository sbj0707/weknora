package com.tencent.weknora.docreader;
import java.util.*;
import com.tencent.weknora.storage.StoragePaths;
/** Registry metadata and availability, independently of transport or HTTP controllers. */
public final class ParserEngineRegistry {
    public record Availability(boolean available,String reason) {}
    public record Engine(String Name,String Description,List<String> FileTypes,boolean Available,String UnavailableReason) {
        public Engine { if(FileTypes!=null) FileTypes=List.copyOf(FileTypes); }
    }
    @FunctionalInterface public interface Probe {Availability check(String engine,Map<String,String> overrides);}
    private record Definition(String name,String description,List<String> types) {}
    private final Probe probes;
    private final Availability anydoc;
    public ParserEngineRegistry(Probe probes,Availability anydoc) {this.probes=Objects.requireNonNull(probes);this.anydoc=Objects.requireNonNull(anydoc);}
    private static List<String> types(String value){return List.of(value.split(" "));}
    private static final List<Definition> LOCAL=List.of(
        new Definition("builtin","DocReader built-in parser engine",types("docx doc pdf md markdown xlsx xls pptx ppt epub html htm mhtml xmind jpg jpeg png gif bmp tiff webp mp3 wav m4a flac ogg")),
        new Definition("simple","Simple format & image parsing (no external service required)",types("md markdown txt csv json jpg jpeg png gif bmp tiff webp mp3 wav m4a flac ogg")),
        new Definition("anydoc","anydoc in-process office document converter (no external service required)",types("csv doc docm docx epub odp ods odt pdf ppt pptm pptx rtf xls xlsm xlsx")),
        new Definition("weknoracloud","WeKnoraCloud document reader",types("docx doc pdf md markdown xlsx xls pptx ppt")),
        new Definition("mineru","MinerU self-hosted service",types("pdf jpg jpeg png bmp tiff doc docx ppt pptx")),
        new Definition("mineru_cloud","MinerU Cloud API",types("pdf jpg jpeg png bmp tiff doc docx ppt pptx")),
        new Definition("paddleocr_vl","PaddleOCR-VL self-hosted service",types("pdf jpg jpeg png bmp tiff")),
        new Definition("paddleocr_vl_cloud","PaddleOCR-VL Cloud API",types("pdf jpg jpeg png bmp tiff")));
    public List<Engine> list(boolean connected,Map<String,String> overrides,List<Engine> remote){
        Map<String,String> config=overrides==null?Map.of():overrides;
        List<Engine> discovery=remote==null?List.of():remote;
        Map<String,Engine> last=new HashMap<>();for(Engine engine:discovery)last.put(engine.Name(),engine);
        Set<String> local=new HashSet<>();List<Engine> result=new ArrayList<>();
        for(Definition definition:LOCAL){
            local.add(definition.name());Engine override=last.get(definition.name());
            String description=override!=null&&!override.Description().isEmpty()?override.Description():definition.description();
            List<String> fileTypes=override!=null&&override.FileTypes()!=null&&!override.FileTypes().isEmpty()?override.FileTypes():definition.types();
            Availability availability=availability(definition.name(),connected,config);
            result.add(new Engine(definition.name(),description,fileTypes,availability.available(),availability.reason()));
        }
        for(Engine engine:discovery)if(!local.contains(engine.Name()))result.add(engine);
        return List.copyOf(result);
    }
    private Availability availability(String name,boolean connected,Map<String,String> config){
        return switch(name){
            case "builtin"->new Availability(connected,connected?"":"DocReader service not connected");
            case "simple"->new Availability(true,"");
            case "anydoc"->anydoc;
            case "weknoracloud"->configured(!config.getOrDefault("weknoracloud_app_id","").isEmpty(),"WeKnora Cloud credentials not configured. Go to Settings → WeKnora Cloud to set up.");
            case "paddleocr_vl_cloud"->configured(!trim(config,"paddleocr_vl_cloud_token").isEmpty(),"PaddleOCR-VL Cloud Token not configured");
            case "mineru"->trim(config,"mineru_endpoint").isEmpty()?new Availability(false,"MinerU service not configured"):probes.check(name,config);
            case "mineru_cloud"->trim(config,"mineru_api_key").isEmpty()?new Availability(false,"MinerU API Key not configured"):probes.check(name,config);
            case "paddleocr_vl"->trim(config,"paddleocr_vl_endpoint").isEmpty()?new Availability(false,"PaddleOCR-VL service not configured"):probes.check(name,config);
            default->throw new IllegalArgumentException(name);
        };
    }
    private static String trim(Map<String,String> config,String key){return StoragePaths.trim(config.getOrDefault(key,""));}
    private static Availability configured(boolean present,String absent){return new Availability(present,present?"":absent);}
}
