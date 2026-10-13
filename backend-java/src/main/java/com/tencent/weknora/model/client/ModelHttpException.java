package com.tencent.weknora.model.client;

import java.util.Locale;

/** Typed provider status; the raw body is inspected once and never retained in the exception. */
final class ModelHttpException extends ModelClientException {
    private final int status;private final boolean multimodalUnsupported;
    ModelHttpException(int status,String rawBody,ModelEndpoint endpoint){super(endpoint.safe("API request failed with status "+status+": "+rawBody));this.status=status;String body=rawBody.toLowerCase(Locale.ROOT);boolean image=body.contains("multimodal")||body.contains("image")||body.contains("vision");this.multimodalUnsupported=image&&(body.contains("not support")||body.contains("unsupported")||status==400);}
    int status(){return status;}boolean retryWithoutImages(){return multimodalUnsupported;}
}
