package com.tencent.weknora.storage;
import java.io.*;
import java.time.Duration;

public final class BackendScopedFileStore implements FileStore {
    private final String backend;private final FileStore inner;private final FileUrlSigner signer;private final String externalUrl;
    public BackendScopedFileStore(String backend,FileStore inner,FileUrlSigner signer,String externalUrl){this.backend=StoragePaths.trim(backend);if(this.backend.isEmpty())throw new IllegalArgumentException("storage backend ID is required");this.inner=java.util.Objects.requireNonNull(inner);this.signer=signer;this.externalUrl=externalUrl;}
    private String unwrap(String path)throws IOException{var scope=StoragePaths.scope(path);if(scope.isPresent()){if(!scope.get().backendId().equals(backend))throw new IOException("storage backend mismatch");return scope.get().path();}return path;}
    private String wrap(String path){return StoragePaths.wrap(backend,path);}
    public void checkConnectivity()throws IOException{inner.checkConnectivity();}
    public String saveFile(InputStream bytes,String filename,long tenant,String knowledge)throws IOException{return wrap(inner.saveFile(bytes,filename,tenant,knowledge));}
    public String saveBytes(byte[] bytes,long tenant,String filename,boolean temporary)throws IOException{return wrap(inner.saveBytes(bytes,tenant,filename,temporary));}
    public InputStream open(String path)throws IOException{return inner.open(unwrap(path));}
    public void delete(String path)throws IOException{inner.delete(unwrap(path));}
    public String copy(String path,long tenant,String knowledge)throws IOException{return wrap(inner.copy(unwrap(path),tenant,knowledge));}
    public String url(String path)throws IOException{String physical=unwrap(path),url=inner.url(physical),scoped=wrap(physical);if(url.equals(physical))return scoped;if(url.contains("/api/v1/files/presigned?")&&signer!=null&&externalUrl!=null)try{return signer.sign(externalUrl,scoped,FileUrlSigner.tenantFromPath(scoped),Duration.ZERO);}catch(IllegalStateException ignored){}return url;}
}
