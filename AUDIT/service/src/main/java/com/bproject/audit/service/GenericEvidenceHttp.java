package com.bproject.audit.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Bounded responses, no redirects or ambient proxy; never trusts remote Location headers. */
final class GenericEvidenceHttp {
    record Reply(int status,JsonNode body,String raw) {}
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().proxy(new ProxySelector(){
        public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
        public void connectFailed(URI uri,SocketAddress address,java.io.IOException e){}
    }).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    GenericEvidenceHttp(ObjectMapper mapper){this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    Reply call(TrustSources.Route route,String suffix,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(route.baseUrl()+suffix)).timeout(Duration.ofSeconds(5))
                .header("Authorization","Bearer "+route.token()).header("Accept","application/json");
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
        var response=http.send(request.build(),info->new HttpResponse.BodySubscriber<byte[]>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
            private java.util.concurrent.Flow.Subscription subscription;
            private long size;
            public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
            public void onNext(List<java.nio.ByteBuffer> buffers){
                size+=buffers.stream().mapToInt(java.nio.ByteBuffer::remaining).sum();
                if(size>65536){subscription.cancel();delegate.onError(new IllegalStateException("Oversized TRUST response"));}else delegate.onNext(buffers);
            }
            public void onError(Throwable t){delegate.onError(t);}
            public void onComplete(){delegate.onComplete();}
        });
        byte[] bytes=response.body();
        String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        return new Reply(response.statusCode(),raw.isBlank()?mapper.createObjectNode():mapper.readTree(raw),raw);
    }
}
