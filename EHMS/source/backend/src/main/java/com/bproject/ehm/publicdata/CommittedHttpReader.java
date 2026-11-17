package com.bproject.ehm.publicdata;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class CommittedHttpReader {
    @FunctionalInterface public interface Fetch { byte[] get(String path)throws Exception; }
    private final CommittedInboxStore store;private final Fetch fetch;
    public CommittedHttpReader(CommittedInboxStore store,Fetch fetch){this.store=store;this.fetch=fetch;}
    public static Fetch network(PublicDataConfig config)throws Exception {
        String token=Files.readString(config.httpTokenFile()).trim();if(token.isEmpty()||token.contains("\n"))throw new IllegalArgumentException("TOKEN_FILE_INVALID");
        var client=HttpClient.newBuilder().proxy(new ProxySelector(){
            public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,java.net.SocketAddress a,java.io.IOException e){}
        }).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return path->{
            var request=HttpRequest.newBuilder(URI.create(config.httpBase().replaceAll("/$","")+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).GET().build();
            // Streaming cap avoids allocating an unbounded remote response. Error bodies are never logged.
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()){
                if(response.statusCode()!=200)throw new IllegalStateException("PUBLIC_HTTP_STATUS_"+response.statusCode());
                byte[] bytes=body.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new IllegalStateException("PUBLIC_HTTP_RESPONSE_TOO_LARGE");return bytes;
            }
        };
    }
    /** Per-device cursor, receipt identity and durable inbox commit before checkpoint advancement. */
    public int page(String device,int limit)throws Exception {
        if(!store.catalog.devices.containsKey(device)||limit<1||limit>100)throw new IllegalArgumentException("OWNER_DEVICE_AND_BOUNDED_LIMIT_REQUIRED");
        String feed=CommittedInboxStore.hash((store.config.httpBase()+"\n"+store.config.principal()+"\n"+store.config.policyRevision()+"\n"+device+"\n"+store.catalog.fingerprint).getBytes(StandardCharsets.UTF_8));
        String cursor=store.cursor(feed);String path="/api/device-data/v1/history?device_id="+q(device)+"&limit="+limit+(cursor==null?"":"&cursor="+q(cursor));
        var page=store.json.readTree(fetch.get(path));var items=page.path("items");
        if(!items.isArray()||items.size()>limit)throw new IllegalArgumentException("HISTORY_CONTRACT_REJECTED");int fresh=0;
        for(var item:items){
            String id=item.path("event_id").asText();byte[] raw=fetch.get("/api/device-data/v1/events?event_id="+q(id));JsonNode receipt=store.json.readTree(raw);
            if(!id.equals(receipt.path("event_id").asText())||!device.equals(receipt.path("event").path("device_id").asText())
                    ||!item.path("wire_sha256").asText().equals(receipt.path("wire_sha256").asText())
                    ||item.has("device_id")&&!device.equals(item.path("device_id").asText()))throw new IllegalArgumentException("HISTORY_RECEIPT_MISMATCH");
            var result=store.accept(raw,"HTTP",feed,item.path("cursor").asText());
            if(result==CommittedInboxStore.Accepted.QUARANTINED)throw new IllegalStateException("HISTORY_QUARANTINED_PROGRESS_RETAINED");
            if(result==CommittedInboxStore.Accepted.NEW)fresh++;
        }return fresh; // empty page deliberately never changes a cursor
    }
    private static String q(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
