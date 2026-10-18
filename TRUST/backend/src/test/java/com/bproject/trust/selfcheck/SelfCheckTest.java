package com.bproject.trust.selfcheck;

import static org.junit.jupiter.api.Assertions.*;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfCheckTest {
  @TempDir Path temp;
  Path file(String name,String content) throws Exception {
    Path p=temp.resolve(name); Files.createDirectories(p.getParent()); Files.writeString(p,content); return p;
  }
  Path config() throws Exception { return file("source.json","""
      {"sourceId":"selfcheck","orgId":"synthetic-org","sourceSystem":"synthetic-system",
       "operations":["read","submit","retry"],"allowedEventTypes":["BUSINESS_EVENT"]}
      """); }
  String request() { return """
      {"schemaVersion":1,"sourceEventId":"example-1","eventType":"BUSINESS_EVENT",
       "businessObjectId":"sample-1","occurredAt":"2026-10-13T00:00:00Z","payload":{"n":1}}
      """; }
  @Test void offlineAndSummaryHaveCiCodesAndNoRequestContents() throws Exception {
    Path input=file("event.json",request()), profile=config(), report=temp.resolve(".local/run.json");
    assertEquals(0,SelfCheck.run("check","--config",profile.toString(),"--report",report.toString(),input.toString()));
    String content=Files.readString(report);
    assertFalse(content.contains("example-1")); assertFalse(content.contains("sample-1"));
    assertEquals(0,SelfCheck.run("report","--input",report.toString(),"--out",temp.resolve(".local/summary").toString()));
    var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
    assertEquals("testsuite",factory.newDocumentBuilder().parse(temp.resolve(".local/summary.xml").toFile()).getDocumentElement().getTagName());
    assertEquals(2,SelfCheck.run("check","--config",profile.toString(),"--report",report.toString(),input.toString()));
  }
  @Test void invalidRequestFailsAndMissingInputDoesNotPass() throws Exception {
    Path input=file("event.json",request().replace("\"schemaVersion\":1","\"schemaVersion\":2")),profile=config();
    Path report=temp.resolve(".local/invalid.json");
    assertEquals(1,SelfCheck.run("check","--config",profile.toString(),"--report",report.toString(),input.toString()));
    assertEquals(1,SelfCheck.run("report","--input",report.toString(),"--out",temp.resolve(".local/bad-summary").toString()));
    Path missing=temp.resolve(".local/missing.json");
    assertEquals(2,SelfCheck.run("check","--config",profile.toString(),"--report",missing.toString(),temp.resolve("absent.json").toString()));
    assertEquals(2,SelfCheck.run("report","--input",missing.toString(),"--out",temp.resolve(".local/incomplete").toString()));
  }
  @Test void profileRejectsProductionCredentialAndUnknownFields() throws Exception {
    Path profile=config();
    file("source.json",Files.readString(profile).replace("\"sourceId\"","\"token\":\"secret\",\"sourceId\""));
    assertThrows(IllegalArgumentException.class,()->Profile.read(profile));
  }
  @Test void emptyOrInconsistentReportsCannotPass() throws Exception {
    Path report=file(".local/empty.json",Json.write(Reports.record("local-simulation","normal",true,0,List.of())));
    assertEquals(2,Reports.render(report,temp.resolve(".local/empty-summary")));
    file(".local/false.json",Json.write(Reports.record("offline","input-contract",true,0,List.of(Map.of("name","request-1","passed",false,"reason","bad")))));
    assertEquals(1,Reports.render(temp.resolve(".local/false.json"),temp.resolve(".local/false-summary")));
    assertEquals(2,SelfCheck.run("report","--input",temp.resolve("missing.json").toString(),"--out",temp.resolve(".local/missing").toString()));
  }
  @Test void truncatedScenarioAndOverflowCannotBecomeGreen() throws Exception {
    Path partial=file(".local/truncated.json",Json.write(Reports.record("local-simulation","normal",true,0,List.of(Map.of("name","accepted","passed",true,"reason","observed")))));
    assertEquals(2,Reports.render(partial,temp.resolve(".local/truncated-summary")));
    String overflow=Files.readString(partial).replace("\"exitCode\":0","\"exitCode\":4294967296");
    Path invalid=file(".local/overflow.json",overflow);
    assertEquals(2,SelfCheck.run("report","--input",invalid.toString(),"--out",temp.resolve(".local/overflow-summary").toString()));
  }
  @Test void rejectsPublicOutputsUnknownOptionsAndInvalidDurations() throws Exception {
    Path profile=config(),input=file("event.json",request());
    assertEquals(2,SelfCheck.run("check","--config",profile.toString(),"--report",Path.of("public-selfcheck-test.json").toString(),input.toString()));
    assertEquals(2,SelfCheck.run("check","--network","yes"));
    for(String seconds:List.of("0","601","NaN"))
      assertEquals(2,SelfCheck.run("serve","--config",profile.toString(),"--seconds",seconds,"--connection",temp.resolve(".local/c.json").toString(),"--report",temp.resolve(".local/s.json").toString()));
  }
  @Test void multiFileReadFailureLeavesIncompleteRecord() throws Exception {
    Path p=config(),input=file("event.json",request()),report=temp.resolve(".local/partial.json");
    assertEquals(2,SelfCheck.run("check","--config",p.toString(),"--report",report.toString(),input.toString(),temp.resolve("absent").toString()));
    assertFalse(Json.MAPPER.readTree(Files.readAllBytes(report)).path("completed").asBoolean());
  }
}
