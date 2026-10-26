package com.bdemo.iam.navigation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class BoundedReadinessBodyTest {
  @Test void rejectsExcessBytesBeforeAllocatingTheChunk(){
    var body=new BoundedReadinessBody(4);var subscription=mock(Flow.Subscription.class);body.onSubscribe(subscription);
    body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2,3})));body.onNext(List.of(ByteBuffer.wrap(new byte[]{4,5})));
    assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());verify(subscription).cancel();
  }
  @Test void bodyCompletesOnlyAfterAllChunksAreReceived(){
    var body=new BoundedReadinessBody(4);body.onSubscribe(mock(Flow.Subscription.class));
    body.onNext(List.of(ByteBuffer.wrap(new byte[]{1,2}),ByteBuffer.wrap(new byte[]{3,4})));
    assertFalse(body.getBody().toCompletableFuture().isDone());body.onComplete();
    assertArrayEquals(new byte[]{1,2,3,4},body.getBody().toCompletableFuture().join());
  }
}
