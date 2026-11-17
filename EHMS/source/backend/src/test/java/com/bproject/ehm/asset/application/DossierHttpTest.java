package com.bproject.ehm.asset.application;
import com.bproject.ehm.asset.adapter.in.web.DeviceDossierController;
import com.bproject.ehm.asset.adapter.out.files.PrivateDeviceDocumentStore;
import com.bproject.ehm.asset.domain.model.DeviceDossier;
import com.bproject.ehm.asset.ports.DeviceDossierRepository;
import com.bproject.ehm.shared.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class DossierHttpTest {
 @TempDir(factory=DeviceDossierServiceTest.WorkspaceTemp.class) Path root;
 MockMvc mvc;ObjectMapper json=new ObjectMapper().findAndRegisterModules();
 @BeforeEach void setup(){var saved=new AtomicReference<DeviceDossier>();var repo=mock(DeviceDossierRepository.class);
  when(repo.find("EQ-1")).thenAnswer(i->Optional.ofNullable(saved.get()));when(repo.save(any(),anyLong())).thenAnswer(i->{saved.set(i.getArgument(0));return saved.get();});
  var assets=mock(AssetQueryFacade.class);var service=new DeviceDossierService(repo,new PrivateDeviceDocumentStore(root.toString()),assets);
  mvc=MockMvcBuilders.standaloneSetup(new DeviceDossierController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
 }
 @Test void actualUploadDownloadAndArchiveContract()throws Exception{
  byte[] bytes="[虚拟]维修资料，供接口测试。".getBytes(StandardCharsets.UTF_8);
  var response=mvc.perform(multipart("/api/ehm/v1/devices/EQ-1/dossier/documents").file(new MockMultipartFile("file","规程.txt","text/plain",bytes))
      .param("version","0").param("title","测试维护规程").param("revision","v1").param("kind","MAINTENANCE")).andExpect(status().isCreated()).andReturn();
  String id=json.readTree(response.getResponse().getContentAsString()).at("/documents/0/id").asText();assertFalse(id.isBlank());
  mvc.perform(get("/api/ehm/v1/devices/EQ-1/dossier/documents/"+id+"/content")).andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"))
      .andExpect(header().exists("X-EHM-Document-Sha256")).andExpect(content().bytes(bytes));
  mvc.perform(delete("/api/ehm/v1/devices/EQ-1/dossier/documents/"+id).param("version","1")).andExpect(status().isOk());
  mvc.perform(get("/api/ehm/v1/devices/EQ-1/dossier/documents/"+id+"/content")).andExpect(status().isNotFound());
 }
 @Test void invalidFileAndMissingFieldsDoNotReturnFalseSuccess()throws Exception{
  mvc.perform(multipart("/api/ehm/v1/devices/EQ-1/dossier/documents").file(new MockMultipartFile("file","bad.pdf","application/pdf",new byte[]{1,2,3}))
    .param("version","0").param("title","假PDF").param("revision","v1").param("kind","MANUAL")).andExpect(status().isBadRequest());
  mvc.perform(multipart("/api/ehm/v1/devices/EQ-1/dossier/documents").param("version","0"))
    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_FIELD"));
 }
 @org.springframework.web.bind.annotation.RestController
 static class LimitProbe {
  @org.springframework.web.bind.annotation.GetMapping("/limit-test")
  void limit(){throw new org.springframework.web.multipart.MaxUploadSizeExceededException(5*1024*1024);}
 }
 @Test void multipartLimitHasAnActionable413InsteadOfInternalError()throws Exception{
  MockMvcBuilders.standaloneSetup(new LimitProbe()).setControllerAdvice(new GlobalExceptionHandler()).build()
   .perform(get("/limit-test")).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));
 }
}
