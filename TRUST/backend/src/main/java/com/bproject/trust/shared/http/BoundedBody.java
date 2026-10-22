package com.bproject.trust.shared.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Enforce the limit before allocating each chunk, including chunked/error responses. */
public final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final int limit;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> result = new CompletableFuture<>();
  private Flow.Subscription subscription;

  public BoundedBody(int limit) {
    this.limit = limit;
  }

  public CompletionStage<byte[]> getBody() {
    return result;
  }

  public void onSubscribe(Flow.Subscription s) {
    subscription = s;
    s.request(1);
  }

  public void onNext(List<ByteBuffer> buffers) {
    for (var buffer : buffers) {
      if (buffer.remaining() > limit - bytes.size()) {
        subscription.cancel();
        result.completeExceptionally(new IOException("响应超过大小上限"));
        return;
      }
      byte[] chunk = new byte[buffer.remaining()];
      buffer.get(chunk);
      bytes.writeBytes(chunk);
    }
    subscription.request(1);
  }

  public void onError(Throwable e) {
    result.completeExceptionally(e);
  }

  public void onComplete() {
    result.complete(bytes.toByteArray());
  }
}
