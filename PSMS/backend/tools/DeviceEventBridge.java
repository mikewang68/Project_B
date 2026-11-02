import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synthetic RFID arrivals over the lab's existing RocketMQ; never addresses physical actuators. */
public class DeviceEventBridge {
  static final ObjectMapper JSON = new ObjectMapper();
  static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing configuration: " + key);
    return value;
  }
  static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
  static Connection database() throws SQLException {
    String url = required("DEVICE_DB_URL");
    // This demonstrator intentionally refuses shared business database names.
    if (!url.matches("jdbc:postgresql://[^/]+/codex_[A-Za-z0-9_]+(?:\\?.*)?"))
      throw new IllegalArgumentException("An isolated codex_ database is required");
    return DriverManager.getConnection(url, required("DEVICE_DB_USER"), required("DEVICE_DB_PASSWORD"));
  }
  static synchronized Map<String,Object> persist(MessageExt message, String topic) throws Exception {
    JsonNode event = JSON.readTree(message.getBody());
    String id = event.path("eventId").asText(), area = event.path("workArea").asText();
    UUID.fromString(id);
    if (!topic.equals(message.getTopic()) || !event.path("synthetic").asBoolean()
        || !"TRAIN_ARRIVAL".equals(event.path("eventType").asText())
        || !"CODEX-RFID-01".equals(event.path("deviceId").asText())
        || !Set.of("AREA-A", "AREA-B").contains(area)
        || !event.path("trainNo").asText().matches("TCODEX-[0-9]{3}"))
      throw new IllegalArgumentException("Unsupported device event");
    Instant occurred = Instant.parse(event.path("occurredAt").asText());
    if (Math.abs(Instant.now().getEpochSecond()-occurred.getEpochSecond()) > 900)
      throw new IllegalArgumentException("Event time is outside the validation window");
    String hash = sha(message.getBody()); boolean inserted = false;
    try (Connection c = database()) {
      c.setAutoCommit(false);
      try {
        String previous = null;
        try (PreparedStatement p = c.prepareStatement("SELECT payload_sha256 FROM codex_device_event WHERE event_id=?")) {
          p.setString(1,id); try (ResultSet r=p.executeQuery()) { if(r.next()) previous=r.getString(1); }
        }
        if (previous != null && !hash.equals(previous)) throw new IllegalArgumentException("Conflicting replay payload");
        if (previous == null) {
          try (PreparedStatement p = c.prepareStatement("INSERT INTO codex_device_event(event_id,device_id,occurred_at,topic,queue_id,queue_offset,message_id,payload_sha256) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1,id);p.setString(2,"CODEX-RFID-01");p.setTimestamp(3,Timestamp.from(occurred));
            p.setString(4,topic);p.setInt(5,message.getQueueId());p.setLong(6,message.getQueueOffset());
            p.setString(7,message.getMsgId());p.setString(8,hash);p.executeUpdate();
          }
          try (PreparedStatement p = c.prepareStatement("INSERT INTO plans(id,plan_batch_no,train_no,cargo_type,source_station,destination_station,arrive_time,track_no,work_area,status,supplements) VALUES(?,?,?,'STEEL','SYNTHETIC RFID','SYNTHETIC YARD',?,'SIM-G1',?,'PENDING_CONFIRM',?::jsonb)")) {
            p.setString(1,"EVT-"+id);p.setString(2,"DEVICE-"+id);p.setString(3,event.path("trainNo").asText());
            p.setTimestamp(4,Timestamp.from(occurred));p.setString(5,area);
            p.setString(6,JSON.writeValueAsString(Map.of("sourceSystem","SYNTHETIC_RFID_ROCKETMQ","eventId",id)));
            p.executeUpdate();
          }
          inserted = true;
        }
        c.commit();
      } catch (Exception e) { c.rollback(); throw e; }
    }
    return Map.of("eventId",id,"trainNo",event.path("trainNo").asText(),"topic",topic,
        "queueId",message.getQueueId(),"queueOffset",message.getQueueOffset(),"messageId",message.getMsgId(),
        "payloadSha256",hash,"newPlan",inserted,"persistedAt",Instant.now().toString());
  }
  public static void main(String[] args) throws Exception {
    String topic=required("DEVICE_TOPIC"), namesrv=required("DEVICE_NAMESRV"), broker=required("DEVICE_BROKER");
    if (!topic.matches("CODEX_DEADLINE_[A-Z0-9_]+")) throw new IllegalArgumentException("An isolated topic is required");
    Path output=Path.of(required("DEVICE_EVIDENCE_FILE"));
    var admin=new DefaultMQAdminExt("codex-admin-"+UUID.randomUUID()); admin.setNamesrvAddr(namesrv);
    // An existing topic is never adopted or reconfigured by this one-shot demonstrator.
    admin.start();
    try {
      if(admin.fetchAllTopicList().getTopicList().contains(topic)) throw new IllegalStateException("Choose a fresh isolated topic");
      admin.createAndUpdateTopicConfig(broker,new TopicConfig(topic,1,1,6));
    } finally { admin.shutdown(); }
    try(Connection c=database(); Statement s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS codex_device_event(event_id VARCHAR(64) PRIMARY KEY,device_id VARCHAR(64) NOT NULL,occurred_at TIMESTAMPTZ NOT NULL,topic VARCHAR(128) NOT NULL,queue_id INTEGER NOT NULL,queue_offset BIGINT NOT NULL,message_id VARCHAR(128) NOT NULL,payload_sha256 VARCHAR(64) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }
    List<Map<String,Object>> delivered=Collections.synchronizedList(new ArrayList<>());
    List<Map<String,Object>> sent=new ArrayList<>(); CountDownLatch done=new CountDownLatch(3);
    var consumer=new DefaultMQPushConsumer("codex-device-"+UUID.randomUUID()); consumer.setNamesrvAddr(namesrv);
    consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
    consumer.setConsumeThreadMin(1);consumer.setConsumeThreadMax(1);consumer.subscribe(topic,"*");
    consumer.registerMessageListener((MessageListenerConcurrently)(messages,context)->{
      for(MessageExt message:messages)try { delivered.add(persist(message,topic));done.countDown(); }
      catch(Exception failure){System.err.println("DEVICE_CONSUME_FAILURE "+failure.getClass().getSimpleName());return ConsumeConcurrentlyStatus.RECONSUME_LATER;}
      return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    });
    var producer=new DefaultMQProducer("codex-synthetic-"+UUID.randomUUID());producer.setNamesrvAddr(namesrv);
    try {
      consumer.start();producer.start();
      byte[][] events=new byte[2][];
      for(int i=0;i<2;i++) events[i]=JSON.writeValueAsBytes(Map.of("eventId",UUID.randomUUID().toString(),
          "eventType","TRAIN_ARRIVAL","deviceId","CODEX-RFID-01","synthetic",true,
          "sourceNode",required("DEVICE_SOURCE_NODE"),"occurredAt",Instant.now().toString(),
          "trainNo",String.format("TCODEX-%03d",i+1),"workArea",i==0?"AREA-A":"AREA-B"));
      for(byte[] body:new byte[][]{events[0],events[1],events[0]}) {
        var result=producer.send(new Message(topic,"SYNTHETIC",body));
        sent.add(Map.of("messageId",result.getMsgId(),"status",result.getSendStatus().name(),
            "queueOffset",result.getQueueOffset(),"payloadSha256",sha(body)));
      }
      boolean complete=done.await(75,TimeUnit.SECONDS);
      var evidence=new LinkedHashMap<String,Object>();evidence.put("syntheticData",true);evidence.put("transport","RocketMQ");
      evidence.put("topic",topic);evidence.put("sent",sent);evidence.put("consumed",delivered);
      evidence.put("complete",complete && delivered.size()==3 && delivered.stream().filter(e->Boolean.TRUE.equals(e.get("newPlan"))).count()==2);
      Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),StandardCharsets.UTF_8);
      System.out.println("DEVICE_EVIDENCE complete="+evidence.get("complete")+" sent="+sent.size()+" consumed="+delivered.size());
      if(!Boolean.TRUE.equals(evidence.get("complete"))) throw new IllegalStateException("Device chain did not complete");
    } finally {producer.shutdown();consumer.shutdown();}
  }
}
