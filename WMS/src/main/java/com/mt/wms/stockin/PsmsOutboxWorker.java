package com.mt.wms.stockin;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class PsmsOutboxWorker {
    private final PsmsInboundSettings settings;
    private final PsmsInboundRepository repository;
    private final TransactionTemplate transaction;
    private final PsmsJson json;
    private final HttpClient client;
    @org.springframework.beans.factory.annotation.Autowired
    PsmsOutboxWorker(PsmsInboundSettings settings,PsmsInboundRepository repository,TransactionTemplate transaction,PsmsJson json){this(settings,repository,transaction,json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());}
    PsmsOutboxWorker(PsmsInboundSettings settings,PsmsInboundRepository repository,TransactionTemplate transaction,PsmsJson json,HttpClient client){this.settings=settings;this.repository=repository;this.transaction=transaction;this.json=json;this.client=client;}
    @Scheduled(fixedDelay=5000,initialDelay=15000)
    void sendPending(){
        if(!settings.enabled())return;
        for(int i=0;i<5;i++){
            Optional<PsmsInboundRepository.Outbox> found=transaction.execute(tx->repository.claim(UUID.randomUUID().toString(),settings.companyCode(),settings.ownerCode(),settings.projectId(),settings.regionId()));
            if(found==null||found.isEmpty())return;
            var row=found.get();
            try{
                var request=HttpRequest.newBuilder(URI.create(PsmsInboundSettings.CALLBACK_URL)).timeout(Duration.ofSeconds(5))
                        .header("Authorization","Bearer "+settings.outboundToken()).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(row.payload())).build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()==200 && acknowledged(row,response.body())){
                    transaction.executeWithoutResult(tx->repository.delivered(row));
                }else{
                    boolean blocked=response.statusCode()>=400&&response.statusCode()<500&&response.statusCode()!=408&&response.statusCode()!=429;
                    String reason=response.statusCode()==200?"PSMS响应未确认原事件号和任务号":"PSMS返回HTTP "+response.statusCode();
                    transaction.executeWithoutResult(tx->repository.failed(row,blocked,reason));
                }
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();transaction.executeWithoutResult(tx->repository.failed(row,false,"发送被中断，将重试原事件"));return;
            }catch(Exception e){
                // Never persist credentials, request headers or untrusted response bodies in logs.
                transaction.executeWithoutResult(tx->repository.failed(row,false,"PSMS暂时不可达，将重试原事件"));
            }
        }
    }
    boolean acknowledged(PsmsInboundRepository.Outbox row,String body){
        try{var ack=json.mapper.readTree(body);return row.eventId().equals(ack.path("eventId").asText())&&row.taskId().equals(ack.path("taskId").asText());}
        catch(Exception invalid){return false;}
    }
}
