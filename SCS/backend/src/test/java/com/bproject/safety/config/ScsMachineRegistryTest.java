package com.bproject.safety.config;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScsMachineRegistryTest {
  @TempDir Path temp;final ObjectMapper mapper=new ObjectMapper();Path file;String secret;ScsMachineRegistry registry;
  @BeforeEach void setup()throws Exception{Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),"POSIX 文件权限在 Windows 开发机不可用（Files.setPosixFilePermissions 抛 UnsupportedOperationException），设备注册表测试跳过；Linux/CI 正常执行");file=temp.resolve("sources.private.json");secret=UUID.randomUUID()+"-"+UUID.randomUUID();registry=new ScsMachineRegistry(mapper,file.toString());write(List.of(source("SIM-A",secret,"P-1")));}
  Map<String,Object> source(String id,String key,String object)throws Exception{return new HashMap<>(Map.of("id",id,"enabled",true,"keySha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))),"projectId","PROJECT_B","regionId","SHEN_TIE","subsystemCode","SCS","expiresAt",Instant.now().plusSeconds(3600).toString(),"routes",List.of("/api/v1/ingest/person-position"),"objects",Map.of("PERSONNEL",List.of(object))));}
  void write(List<Map<String,Object>> sources)throws Exception{Files.writeString(file,mapper.writeValueAsString(Map.of("version",1,"sources",sources)));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}
  ScsMachineRegistry.Binding authenticate(){return registry.authenticate("SIM-A",secret,"POST","/api/v1/ingest/person-position");}
  @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
  @Test void namedCredentialBindsExactlyOneTupleAndObjects(){var b=authenticate();assertEquals("SHEN_TIE",b.context().regionId());assertTrue(b.owns(ScsCentralScope.Root.PERSONNEL,"P-1"));assertFalse(b.owns(ScsCentralScope.Root.PERSONNEL,"P-2"));assertFalse(b.owns(ScsCentralScope.Root.COLLISION,"P-1"));}
  @Test void wrongSecretUnknownSourceAndBrowserRouteAreRejected(){assertThrows(Exception.class,()->registry.authenticate("SIM-A",UUID.randomUUID().toString(),"POST","/api/v1/ingest/person-position"));assertThrows(Exception.class,()->registry.authenticate("MISSING",secret,"POST","/api/v1/ingest/person-position"));assertThrows(Exception.class,()->registry.authenticate("SIM-A",secret,"GET","/ws/live"));assertThrows(Exception.class,()->registry.authenticate("SIM-A",secret,"POST","/api/v1/ai-events/ingest"));}
  @Test void revocationInvalidatesAnAlreadyBoundRequest()throws Exception{var b=authenticate();write(List.of());assertThrows(Exception.class,b::context);assertThrows(Exception.class,()->b.owns(ScsCentralScope.Root.PERSONNEL,"P-1"));}
  @Test void expiredAndUnassignedRegistrationsAreRejected()throws Exception{var s=source("SIM-A",secret,"P-1");s.put("expiresAt",Instant.now().minusSeconds(1).toString());write(List.of(s));assertThrows(Exception.class,this::authenticate);s.put("expiresAt",Instant.now().plusSeconds(60).toString());s.put("regionId","UNASSIGNED");write(List.of(s));assertThrows(Exception.class,this::authenticate);}
  @Test void sameTupleObjectsCannotBelongToTwoEnabledSources()throws Exception{write(List.of(source("SIM-A",secret,"P-1"),source("SIM-B",UUID.randomUUID()+"-"+UUID.randomUUID(),"P-1")));assertThrows(Exception.class,this::authenticate);}
  @Test void writableOrPublicRegistryAndSymlinkAreDenied()throws Exception{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-rw----"));assertThrows(Exception.class,this::authenticate);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(Exception.class,this::authenticate);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));Path link=temp.resolve("link.json");Files.createSymbolicLink(link,file);assertThrows(Exception.class,()->new ScsMachineRegistry(mapper,link.toString()).authenticate("SIM-A",secret,"POST","/api/v1/ingest/person-position"));}
  @Test void atomicSqlIncludesSourceObjectAllowlistInAdditionToTuple(){var b=authenticate();var req=new MockHttpServletRequest("POST","/api/v1/ingest/person-position");ScsMachineRegistry.bind(req,b);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));String sql=ScsScopedSql.apply("UPDATE safety.safety_personnel SET name=:name WHERE personnel_code=:id OR status='OLD'",ScsCentralScope.current());assertTrue(sql.contains("region_id = 'SHEN_TIE'"));assertTrue(sql.contains("personnel_code IN ('P-1')"));assertTrue(sql.contains("( personnel_code=:id OR status='OLD'"));String hidden=ScsScopedSql.apply("SELECT * FROM safety.collision_device",ScsCentralScope.current());assertTrue(hidden.contains("AND 1=0"));}
  @Test void bodyRejectsSameTupleOtherObjectForgedSourceAndClientTuple()throws Exception{var db=mock(NamedParameterJdbcTemplate.class);when(db.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(1L);var advice=new ScsMachineBodyAdvice(mapper,db);var b=authenticate();String p="/api/v1/ingest/person-position";advice.validate(b,p,mapper.readTree("{\"source\":\"SIM-A\",\"positions\":[{\"id\":\"P-1\",\"x\":1}]}"));for(String body:List.of("{\"positions\":[{\"id\":\"P-2\"}]}","{\"source\":\"SIM-B\",\"positions\":[{\"id\":\"P-1\"}]}","{\"positions\":[{\"id\":\"P-1\",\"region_id\":\"HA_TIE\"}]}"))assertThrows(Exception.class,()->advice.validate(b,p,mapper.readTree(body)));}
  @Test void existingIdOwnershipMustMatchAndUnknownObjectsAreNotAdopted()throws Exception{var db=mock(NamedParameterJdbcTemplate.class);when(db.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(0L);var advice=new ScsMachineBodyAdvice(mapper,db);assertThrows(Exception.class,()->advice.validate(authenticate(),"/api/v1/ingest/person-position",mapper.readTree("{\"positions\":[{\"id\":\"P-1\"}]}")));}
  @Test void namedAiEventSourceWithCameraObjectsAuthenticatesAndOwnsOnlyCameras()throws Exception{
    var s=source("SIM-A",secret,"CAM-01");s.put("routes",List.of("/api/v1/ai-events/ingest"));s.put("objects",Map.of("CAMERA",List.of("CAM-01")));write(List.of(s));
    var binding=registry.authenticate("SIM-A",secret,"POST","/api/v1/ai-events/ingest");
    assertTrue(binding.owns(ScsCentralScope.Root.CAMERA,"CAM-01"));assertFalse(binding.owns(ScsCentralScope.Root.PERSONNEL,"CAM-01"));
    assertThrows(Exception.class,()->registry.authenticate("SIM-A",secret,"POST","/api/v1/ai-events/ingest").owns(ScsCentralScope.Root.PERSONNEL,"P-1"));}
  @Test void aiEventIngestBodyRequiresOwnedCameraAndExistingRow()throws Exception{
    var s=source("SIM-A",secret,"CAM-01");s.put("routes",List.of("/api/v1/ai-events/ingest"));s.put("objects",Map.of("CAMERA",List.of("CAM-01")));write(List.of(s));
    var binding=registry.authenticate("SIM-A",secret,"POST","/api/v1/ai-events/ingest");
    var db=mock(NamedParameterJdbcTemplate.class);when(db.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(1L);
    var advice=new ScsMachineBodyAdvice(mapper,db);String p="/api/v1/ai-events/ingest";
    advice.validate(binding,p,mapper.readTree("{\"eventType\":\"未穿反光衣\",\"cameraCode\":\"CAM-01\",\"confidence\":94.5}"));
    when(db.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(0L);
    assertThrows(Exception.class,()->advice.validate(binding,p,mapper.readTree("{\"cameraCode\":\"CAM-01\"}")));
    when(db.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(1L);
    assertThrows(Exception.class,()->advice.validate(binding,p,mapper.readTree("{\"cameraCode\":\"CAM-99\"}")));
    assertThrows(Exception.class,()->advice.validate(binding,p,mapper.readTree("{\"eventType\":\"x\"}")));}
  @Test void namedAreaRequiresExistingOwnedRootBeforeCreatingOnlyRuntimeCache()throws Exception{
    var s=source("SIM-A",secret,"P-1");s.put("routes",List.of("/api/v1/ingest/zone-state"));s.put("objects",Map.of("AREA",List.of("SIM-AREA")));write(List.of(s));
    var binding=registry.authenticate("SIM-A",secret,"POST","/api/v1/ingest/zone-state");
    var request=new MockHttpServletRequest("POST","/api/v1/ingest/zone-state");ScsMachineRegistry.bind(request,binding);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    var refs=mock(ScsScopedReferenceData.class);when(refs.scopedMachineIngestAllowed(ScsCentralScope.Root.AREA,"SIM-AREA")).thenReturn(true);when(refs.machineAreaName("SIM-AREA")).thenReturn(Optional.of("SIM test area"));
    var publisher=mock(com.bproject.safety.common.realtime.DomainLivePublisher.class);
    var zones=new com.bproject.safety.module.zone.service.ZoneStateService(publisher,java.time.Clock.systemUTC());
    org.springframework.test.util.ReflectionTestUtils.setField(zones,"centralReferences",refs);
    assertTrue(zones.apply("SIM-AREA",true,false,null,null));assertFalse(zones.apply("UNREGISTERED",true,false,null,null));
    verify(publisher,times(1)).publish(anyString(),any());
    RequestContextHolder.resetRequestAttributes();assertFalse(zones.apply("LEGACY-UNKNOWN",true,false,null,null));
  }
}
