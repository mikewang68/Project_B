import com.bproject.ehm.publicdata.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.rocketmq.acl.common.*;
import org.apache.rocketmq.client.consumer.*;
import org.apache.rocketmq.client.consumer.listener.*;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;

/** Isolated optional MQ transport. Uses Dong's vetted client jars; NOT added to the EHMS web classpath. */
public final class CommittedMqMain {
    public static void main(String[] args){
        try{run(args);}catch(Exception e){System.err.println("EHMS_MQ_NOT_COMPLETED: "+e.getClass().getSimpleName()+"; no credentials printed");System.exit(1);}
    }
    static void run(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("usage: PRIVATE_WORKER_CONFIG PRIVATE_MQ_CONFIG SECONDS MAX_DELIVERIES");
        int seconds=Integer.parseInt(args[2]),max=Integer.parseInt(args[3]);
        if(seconds<1||seconds>3600||max<1||max>10000)throw new IllegalArgumentException("INVALID_RUN_BOUNDS");
        var store=PublicDataMain.open(Path.of(args[0]));
        var config=PublicDataMain.JSON.readTree(Files.readAllBytes(Path.of(args[1])));var mq=config.path("mq");
        if(!store.config.principal().equals(config.path("consumer_id").asText())||!"b-device-committed-ehms-v1".equals(mq.path("group").asText())
            ||!"127.0.0.1:39887".equals(mq.path("nameserver").asText()))throw new IllegalArgumentException("EHMS_MQ_IDENTITY_REJECTED");
        // Check the binding and database availability BEFORE touching the official group offsets.
        store.verifyBinding();store.status();store.process(100);
        var consumer=new DefaultMQPushConsumer(mq.path("group").asText(),new AclClientRPCHook(new SessionCredentials(mq.path("accessKey").asText(),mq.path("secretKey").asText())),new org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely());
        consumer.setNamesrvAddr("127.0.0.1:39887");consumer.setVipChannelEnabled(false);consumer.setInstanceName("ehms-business-"+store.config.sourceId());
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);consumer.setConsumeThreadMin(1);consumer.setConsumeThreadMax(1);consumer.setConsumeMessageBatchMaxSize(1);
        consumer.subscribe("b-device-committed-v1","*");var deliveries=new AtomicInteger();var quarantined=new AtomicInteger();
        var stop=new CountDownLatch(1);var until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        consumer.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
            if(deliveries.get()>=max||System.nanoTime()>=until){stop.countDown();return ConsumeConcurrentlyStatus.RECONSUME_LATER;}
            try{
                for(var message:messages){
                    JsonNode envelope;
                    try{envelope=PublicDataMain.JSON.readTree(message.getBody());}
                    catch(Exception malformed){store.quarantineTransport(message.getBody(),"MALFORMED_JSON");quarantined.incrementAndGet();deliveries.incrementAndGet();continue;}
                    if(envelope==null||message.getKeys()==null||!java.util.Arrays.asList(message.getKeys().split(" ")).contains(envelope.path("event_id").asText())){
                        store.quarantineTransport(message.getBody(),"MQ_KEY_EVENT_ID_MISMATCH");quarantined.incrementAndGet();deliveries.incrementAndGet();continue;
                    }
                    var accepted=store.accept(message.getBody(),"MQ",null,null);
                    if(accepted==CommittedInboxStore.Accepted.QUARANTINED)quarantined.incrementAndGet();
                    // Durable quarantine is terminal reception, not business success. Original conflict is never overwritten.
                    deliveries.incrementAndGet();
                }
                if(deliveries.get()>=max)stop.countDown();
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }catch(Exception failure){System.err.println("EHMS_MQ_INBOX_NOT_COMMITTED");return ConsumeConcurrentlyStatus.RECONSUME_LATER;}
        });
        var processor=Executors.newSingleThreadScheduledExecutor();
        try{
            consumer.start();processor.scheduleWithFixedDelay(()->{try{store.process(50);}catch(Exception ex){System.err.println("EHMS_MQ_BUSINESS_RETRY_PENDING");}},0,1,TimeUnit.SECONDS);
            stop.await(seconds,TimeUnit.SECONDS);
        }finally{consumer.shutdown();processor.shutdown();processor.awaitTermination(35,TimeUnit.SECONDS);}
        store.process(100);
        System.out.println(PublicDataMain.JSON.writeValueAsString(java.util.Map.of("deliveries",deliveries.get(),"quarantinedDeliveries",quarantined.get(),"businessStatus",store.status())));
    }
}
