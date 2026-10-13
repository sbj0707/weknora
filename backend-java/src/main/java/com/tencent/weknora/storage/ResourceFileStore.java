package com.tencent.weknora.storage;

import java.io.*;
import java.time.Duration;
import java.util.*;

/** Tenant-bound stable references over physical providers. Domain owners must release their bindings before deleting. */
public final class ResourceFileStore implements FileStore {
    private final FileStore physical;private final ResourceCatalog catalog;private final long tenant;private final String external;
    public ResourceFileStore(FileStore physical,ResourceCatalog catalog,long ownerTenant,String externalUrl){if(ownerTenant<=0)throw new IllegalArgumentException("resource owner required");this.physical=Objects.requireNonNull(physical);this.catalog=Objects.requireNonNull(catalog);tenant=ownerTenant;external=StoragePaths.trim(externalUrl).replaceAll("/+$","");}
    public void checkConnectivity()throws IOException{physical.checkConnectivity();}
    private void owner(long requested)throws IOException{if(requested!=tenant)throw new IOException("resource owner tenant mismatch");}
    private String location(String ref)throws IOException{try{if(StoragePaths.resourceHandle(ref)!=null)return catalog.resolve(tenant,ref).physicalPath();if(!FileUrlSigner.tenantScope(ref,tenant))throw new IOException("physical path workspace mismatch");return ref;}catch(RuntimeException ex){throw new IOException("resolve stored resource",ex);}}
    private String register(String path,String filename,long size,boolean temporary,String hash)throws IOException{
        var metadata=GoMimeTypes.system().metadata(filename);String name=metadata.name(),mime=metadata.mime(),kind=metadata.kind();
        try{String ref=catalog.register(tenant,path,new ResourceCatalog.Registration(kind,mime,name,size,hash,temporary));
            // A future ingestion transaction can roll back after the write; remove the uncommitted physical object then.
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK)try{physical.delete(path);}catch(IOException ignored){}}});
            return ref;
        }catch(RuntimeException ex){try{physical.delete(path);}catch(IOException suppressed){ex.addSuppressed(suppressed);}throw new IOException("register stored resource",ex);}
    }
    public String saveFile(InputStream input,String filename,long owner,String knowledge)throws IOException{owner(owner);CountingInputStream count=new CountingInputStream(input);String path=physical.saveFile(count,filename,owner,knowledge),ref=register(path,filename,count.count,false,"");bindOrDelete(ref,knowledge);return ref;}
    public String saveBytes(byte[] bytes,long owner,String filename,boolean temporary)throws IOException{owner(owner);String path=physical.saveBytes(bytes,owner,filename,temporary);return register(path,filename,bytes.length,temporary,ResourceCatalog.hashBytes(bytes));}
    private void bindOrDelete(String ref,String knowledge)throws IOException{if(knowledge==null||knowledge.isEmpty())return;try{catalog.bind(tenant,ref,"knowledge",knowledge,"source_file");}catch(RuntimeException ex){try{delete(ref);}catch(IOException suppressed){ex.addSuppressed(suppressed);}throw new IOException("bind stored resource",ex);}}
    public InputStream open(String ref)throws IOException{return physical.open(location(ref));}
    public String url(String ref)throws IOException{String path=location(ref);if(StoragePaths.resourceHandle(ref)!=null&&!external.isEmpty())try{return external+"/r/"+catalog.createGrant(tenant,ref,Duration.ofHours(2));}catch(RuntimeException ex){throw new IOException("create resource read grant",ex);}return physical.url(path);}
    public void delete(String ref)throws IOException{String path=location(ref);physical.delete(path);if(StoragePaths.resourceHandle(ref)!=null)try{catalog.markDeleted(tenant,ref);}catch(RuntimeException ex){throw new IOException("mark stored resource deleted",ex);}}
    public String copy(String ref,long owner,String knowledge)throws IOException{owner(owner);String source=location(ref),path=physical.copy(source,owner,knowledge),copied=register(path,source,0,false,"");bindOrDelete(copied,knowledge);return copied;}
    private static final class CountingInputStream extends FilterInputStream {long count;CountingInputStream(InputStream input){super(input);}public int read()throws IOException{int value=in.read();if(value>=0)count++;return value;}public int read(byte[] bytes,int offset,int length)throws IOException{int read=in.read(bytes,offset,length);if(read>0)count+=read;return read;}}
}
