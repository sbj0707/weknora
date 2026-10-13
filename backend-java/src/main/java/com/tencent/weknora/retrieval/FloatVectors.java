package com.tencent.weknora.retrieval;

import java.nio.*;
public final class FloatVectors {
    private FloatVectors(){}
    /** Go sqlite-vec SerializeFloat32 is little-endian IEEE754, without a header. */
    public static byte[] encode(float[] vector){
        ByteBuffer b=ByteBuffer.allocate(Math.multiplyExact(vector.length,4)).order(ByteOrder.LITTLE_ENDIAN);
        for(float f:vector)b.putInt(Float.floatToRawIntBits(f));return b.array();
    }
    public static float[] decode(byte[] blob,int dimensions){
        if(dimensions<0||blob.length!=Math.multiplyExact(dimensions,4))throw new IllegalArgumentException("invalid stored vector dimension");
        ByteBuffer b=ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);float[] out=new float[dimensions];
        for(int i=0;i<dimensions;i++)out[i]=Float.intBitsToFloat(b.getInt());return out;
    }
    public static void validate(float[] vector,int expected){
        if(vector==null||vector.length==0)throw new IllegalArgumentException("embedding vector is empty");
        if(expected>0&&vector.length!=expected)throw new IllegalArgumentException("embedding vector dimension mismatch");
        for(float f:vector)if(!Float.isFinite(f))throw new IllegalArgumentException("embedding vector contains a non-finite value");
    }
}
