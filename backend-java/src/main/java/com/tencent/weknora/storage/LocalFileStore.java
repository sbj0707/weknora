package com.tencent.weknora.storage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Go local provider layout and legacy aliases; refuses symlink escape as an added guard. */
public final class LocalFileStore implements FileStore {
    private final Path base;private final String externalUrl;private final FileUrlSigner signer;private final long maximumBytes;
    private final AtomicLong lastName=new AtomicLong();
    public LocalFileStore(Path base,String externalUrl,FileUrlSigner signer,long maximumBytes){if(maximumBytes<1)throw new IllegalArgumentException("Invalid maximum file size");this.base=base.toAbsolutePath().normalize();this.externalUrl=Objects.toString(externalUrl,"").replaceAll("/+$","");this.signer=signer;this.maximumBytes=maximumBytes;}
    @Override public void checkConnectivity()throws IOException{if(!Files.isDirectory(base)||!Files.isReadable(base))throw new IOException("storage directory not accessible");}
    @Override public String saveFile(InputStream input,String filename,long tenant,String knowledge)throws IOException{return save(input,tenant,knowledge,name()+extension(filename));}
    @Override public String saveBytes(byte[] bytes,long tenant,String filename,boolean temporary)throws IOException{String safe=safeFilename(filename),ext=extension(safe);return save(new ByteArrayInputStream(bytes),tenant,"exports",safe.substring(0,safe.length()-ext.length())+"_"+name()+ext);}
    private String save(InputStream input,long tenant,String namespace,String filename)throws IOException{
        if(tenant<=0||namespace==null)throw new IOException("invalid storage ownership");Path tenantBase=base.resolve(Long.toString(tenant));Path directory=tenantBase.resolve(namespace).normalize();if(!directory.startsWith(tenantBase))throw new IOException("cross-tenant storage namespace denied");safe(directory);Files.createDirectories(directory);safe(directory);Path file=directory.resolve(filename);safe(file);
        boolean complete=false;try(var output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[65536];long total=0;for(int read;(read=input.read(buffer))!=-1;){total=Math.addExact(total,read);if(total>maximumBytes)throw new IOException("maximum file size exceeded");output.write(buffer,0,read);}complete=true;}finally{if(!complete)Files.deleteIfExists(file);}
        try{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));}catch(UnsupportedOperationException ignored){}return "local://"+base.relativize(file).toString().replace(File.separatorChar,'/');
    }
    @Override public InputStream open(String path)throws IOException{Path file=resolve(path);if(Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("storage object is not a file");return new SeekableFileInputStream(file);}
    @Override public void delete(String path)throws IOException{Files.delete(resolve(path));}
    @Override public String copy(String path,long tenant,String knowledge)throws IOException{String inner=StoragePaths.inner(path);if(inner.contains("://")&&!inner.startsWith("local://"))throw new IOException("cross-backend copy is not supported");try(var input=open(path)){return saveFile(input,path,tenant,knowledge);}}
    @Override public String url(String path)throws IOException{String normalized=path;if(!path.startsWith("local://"))normalized="local://"+base.relativize(Paths.get(path).toAbsolutePath().normalize()).toString().replace(File.separatorChar,'/');if(!externalUrl.isEmpty()&&signer!=null)try{return signer.sign(externalUrl,normalized,FileUrlSigner.tenantFromPath(normalized),Duration.ZERO);}catch(IllegalStateException|IllegalArgumentException ignored){}return normalized;}
    Path resolve(String path)throws IOException{
        if(path==null||path.indexOf('\0')>=0)throw new IOException("invalid file path");String text=StoragePaths.trim(path);
        if(text.startsWith("storage:")){String rest=text.substring(8).replaceFirst("^/+","");int slash=rest.indexOf('/');text=slash<0?"":rest.substring(slash+1);}
        Path candidate;if(text.startsWith("local:"))candidate=base.resolve(text.substring(6).replaceFirst("^/+",""));
        else {if(text.isEmpty()||text.equals("."))throw new IOException("invalid file path");Path raw=Paths.get(text).normalize();if(raw.isAbsolute())candidate=raw;else {String relative=raw.toString(),prefix=base.toString().replaceAll("^/+|/+$","");if(relative.startsWith(prefix+"/"))relative=relative.substring(prefix.length()+1);candidate=base.resolve(relative);}}
        return safe(candidate.normalize());
    }
    private Path safe(Path path)throws IOException{Path normalized=path.toAbsolutePath().normalize();if(!normalized.startsWith(base))throw new IOException("path traversal denied");for(Path current=normalized;current!=null&&current.startsWith(base);current=current.getParent())if(Files.isSymbolicLink(current))throw new IOException("symbolic storage path denied");return normalized;}
    private String name(){Instant now=Instant.now();long nanos=Math.addExact(Math.multiplyExact(now.getEpochSecond(),1000000000L),now.getNano());return Long.toString(lastName.updateAndGet(old->Math.max(nanos,old+1)));}
    private static String extension(String name){if(name==null)return "";int slash=name.lastIndexOf('/'),dot=name.lastIndexOf('.');return dot<=slash?"":name.substring(dot);}
    static String safeFilename(String name)throws IOException{if(name==null||name.isEmpty()||name.indexOf('\0')>=0)throw new IOException("fileName cannot be empty");Path filename=Paths.get(name).normalize().getFileName();if(filename==null)throw new IOException("invalid fileName");String safe=filename.toString();if(safe.isEmpty()||safe.equals(".")||safe.contains("..")||safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>255)throw new IOException("invalid fileName");return safe;}
}
