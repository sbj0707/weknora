package com.tencent.weknora.knowledge;

import com.tencent.weknora.model.client.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Shared manual sub-batch slots; output positions stay tied to the original sources. */
public final class ManualEmbeddingPool implements AutoCloseable {
 private final ExecutorService workers;
 public ManualEmbeddingPool(int concurrency){if(concurrency<1)throw new IllegalArgumentException("Invalid CONCURRENCY_POOL_SIZE");workers=Executors.newFixedThreadPool(concurrency,Thread.ofVirtual().name("manual-embedding-",0).factory());}
 float[][] embed(EmbeddingModel model,CallContext call,List<String> texts,int size){
  if(size<1)throw new IllegalArgumentException("Invalid BATCH_EMBED_SIZE");float[][] result=new float[texts.size()][];var error=new AtomicReference<RuntimeException>();var jobs=new ArrayList<Future<?>>();
  try{
   for(int start=0;start<texts.size();start+=size){final int offset=start;var batch=List.copyOf(texts.subList(start,Math.min(start+size,texts.size())));jobs.add(workers.submit(()->{if(error.get()!=null)return;try{call.check();var values=model.embed(call,batch,false);if(values==null||values.length!=batch.size())throw new IllegalArgumentException("embedding response count does not match inputs");for(int i=0;i<values.length;i++)result[offset+i]=values[i];}catch(RuntimeException failure){error.compareAndSet(null,failure);}}));}
   for(var job:jobs)for(;;){call.check();try{job.get(25,TimeUnit.MILLISECONDS);break;}catch(TimeoutException retry){}catch(InterruptedException interrupted){Thread.currentThread().interrupt();call.check();throw new CancellationException("manual embedding interrupted");}catch(ExecutionException failure){throw new ModelClientException("manual embedding worker failed");}}
   if(error.get()!=null)throw error.get();call.check();return result;
  }catch(RuntimeException failure){call.cancel();jobs.forEach(job->job.cancel(true));throw failure;}
 }
 @Override public void close(){workers.shutdownNow();}
}
