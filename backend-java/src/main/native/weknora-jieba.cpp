#include <jni.h>
#include "cppjieba/Jieba.hpp"
#include <string>
#include <vector>
static std::string bytes(JNIEnv* env,jbyteArray input){jsize n=env->GetArrayLength(input);std::string output(n,'\0');if(n)env->GetByteArrayRegion(input,0,n,reinterpret_cast<jbyte*>(&output[0]));return output;}
static void fail(JNIEnv* env,const char* message){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message);}
extern "C" JNIEXPORT jlong JNICALL Java_com_tencent_weknora_retrieval_GoJiebaSegmenter_create(JNIEnv* env,jclass,jbyteArray directory){try{std::string root=bytes(env,directory);return reinterpret_cast<jlong>(new cppjieba::Jieba(root+"/jieba.dict.utf8",root+"/hmm_model.utf8",root+"/user.dict.utf8",root+"/idf.utf8",root+"/stop_words.utf8"));}catch(const std::exception& ex){fail(env,"Jieba initialization failed");return 0;}}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_tencent_weknora_retrieval_GoJiebaSegmenter_cut(JNIEnv* env,jclass,jlong address,jbyteArray input){try{std::vector<std::string> words;reinterpret_cast<cppjieba::Jieba*>(address)->CutForSearch(bytes(env,input),words,true);jobjectArray result=env->NewObjectArray(words.size(),env->FindClass("[B"),nullptr);for(size_t i=0;i<words.size();i++){jbyteArray word=env->NewByteArray(words[i].size());if(!words[i].empty())env->SetByteArrayRegion(word,0,words[i].size(),reinterpret_cast<const jbyte*>(words[i].data()));env->SetObjectArrayElement(result,i,word);env->DeleteLocalRef(word);}return result;}catch(const std::exception& ex){fail(env,"Jieba segmentation failed");return nullptr;}}
extern "C" JNIEXPORT void JNICALL Java_com_tencent_weknora_retrieval_GoJiebaSegmenter_destroy(JNIEnv*,jclass,jlong address){delete reinterpret_cast<cppjieba::Jieba*>(address);}
