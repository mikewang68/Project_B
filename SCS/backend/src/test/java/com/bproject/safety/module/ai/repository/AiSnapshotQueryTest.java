package com.bproject.safety.module.ai.repository;

import static org.junit.jupiter.api.Assertions.*;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class AiSnapshotQueryTest {
  @Test void historyWindowAndCountsExcludeCancelledEvents() {
    var repository=new InMemoryAiEventRepository();
    var start=OffsetDateTime.parse("2025-01-02T00:00:00+08:00");
    for(int index=0;index<250;index++){
      var event=new DemoAiEvent();event.id="event-"+index;event.occurredAt=start.plusMinutes(index);
      event.statusCode=index%2==0?"PENDING":"CONFIRMED";event.health="NORMAL";
      event.area="area-a";event.camera="camera-a";repository.save(event);
    }
    var cancelled=new DemoAiEvent();cancelled.id="cancelled-newest";cancelled.occurredAt=start.plusDays(1);
    cancelled.statusCode="CANCELLED";cancelled.area="must-not-appear";cancelled.camera="must-not-appear";repository.save(cancelled);
    var recent=repository.findRecent(7);
    assertEquals(7,recent.size());assertEquals("event-249",recent.get(0).id);
    assertEquals(200,repository.findRecent(Integer.MAX_VALUE).size());
    assertEquals(250,repository.count());assertEquals(125,repository.counts().pending());
    assertEquals(125,repository.counts().confirmed());assertEquals(0,repository.counts().cameraFault());
    assertEquals(250,repository.countOccurredBetween(start,start.plusDays(1)));
    assertEquals(java.util.List.of("area-a"),repository.distinctAreas());
    assertEquals(java.util.List.of("camera-a"),repository.distinctCameras());
  }
}
