package com.bdemo.iam.navigation.service;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** A bounded, non-streaming body keeps the request timeout active until the probe body completes. */
final class BoundedReadinessBody implements HttpResponse.BodySubscriber<byte[]> {
  private final int limit;
  private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> result=new CompletableFuture<>();
  private Flow.Subscription subscription;
  BoundedReadinessBody(int limit){this.limit=limit;}
  public CompletionStage<byte[]> getBody(){return result;}
  public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
  public void onNext(List<ByteBuffer> buffers){
    if(result.isDone())return;
    for(var buffer:buffers){
      if(buffer.remaining()>limit-bytes.size()){
        subscription.cancel();result.completeExceptionally(new IllegalArgumentException("Readiness response exceeds limit"));return;
      }
      byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
    }
    subscription.request(1);
  }
  public void onError(Throwable failure){result.completeExceptionally(failure);}
  public void onComplete(){result.complete(bytes.toByteArray());}
}
