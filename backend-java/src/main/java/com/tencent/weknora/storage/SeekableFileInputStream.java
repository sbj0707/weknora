package com.tencent.weknora.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;

/** Keeps local files seekable through resource/backend decorators without buffering the object. */
public final class SeekableFileInputStream extends InputStream {
  private final SeekableByteChannel channel;
  public SeekableFileInputStream(Path path) throws IOException {
    channel=Files.newByteChannel(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
  }
  public long size() throws IOException {return channel.size();}
  public void position(long position) throws IOException {channel.position(position);}
  @Override public int read() throws IOException {byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
  @Override public int read(byte[] bytes,int offset,int length)throws IOException {return channel.read(ByteBuffer.wrap(bytes,offset,length));}
  @Override public long skip(long count)throws IOException {if(count<=0)return 0;long old=channel.position(),next=old+Math.min(count,Math.max(0,channel.size()-old));channel.position(next);return next-old;}
  @Override public void close()throws IOException {channel.close();}
}
