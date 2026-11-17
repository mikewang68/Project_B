package com.bproject.ehm.asset.application;
import com.bproject.ehm.asset.domain.model.DeviceDossier;
import com.bproject.ehm.asset.domain.model.DeviceDossier.*;
import com.bproject.ehm.asset.ports.*;
import com.bproject.ehm.shared.error.*;
import com.bproject.ehm.asset.adapter.out.files.PrivateDeviceDocumentStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DeviceDossierServiceTest {
    public static class WorkspaceTemp implements org.junit.jupiter.api.io.TempDirFactory {
        public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,org.junit.jupiter.api.extension.ExtensionContext context)throws java.io.IOException {
            Path parent=Path.of("target","dossier-file-tests").toAbsolutePath();java.nio.file.Files.createDirectories(parent);return java.nio.file.Files.createTempDirectory(parent,"case-");
        }
    }
    @TempDir(factory=WorkspaceTemp.class) Path directory;
    AtomicReference<DeviceDossier> saved; DeviceDossierService service; AssetQueryFacade assets;
    @BeforeEach void setup(){saved=new AtomicReference<>();var repo=mock(DeviceDossierRepository.class);assets=mock(AssetQueryFacade.class);
        when(repo.find("GT-01")).thenAnswer(i->Optional.ofNullable(saved.get()));when(repo.save(any(),anyLong())).thenAnswer(i->{saved.set(i.getArgument(0));return saved.get();});
        service=new DeviceDossierService(repo,new PrivateDeviceDocumentStore(directory.resolve("private").toString()),assets);
    }
    DeviceDossier upload(){return service.upload("GT-01",0,"厂家维护规程","MAINTENANCE","v1","manual.txt","每100运行小时检查。测试资料，非真实厂家参数。".getBytes(StandardCharsets.UTF_8));}
    DeviceDossier withBasis(DeviceDossier d,MaintenanceBasis b){return new DeviceDossier(d.assetCode(),"测试厂家","M1","S1","2026-01-01","试验工位","EXT-1","",b,d.documents(),d.version(),d.updatedAt());}
    @Test void uploadsRealBytesAndKeepsVersionAndDigest(){var d=upload();var doc=d.documents().get(0);var file=service.download("GT-01",doc.id());assertEquals(doc.sha256(),file.metadata().sha256());assertTrue(new String(file.bytes(),StandardCharsets.UTF_8).contains("100"));assertEquals(1,d.version());verify(assets,atLeastOnce()).get("GT-01");}
    @Test void greetingHasNothingToDoWithDossierAndEmptyIsNotInvented(){assertEquals(0,service.get("GT-01").version());assertEquals("UNCONFIGURED",service.due("GT-01").get("status"));}
    @Test void staleWriteAndDuplicateVersionAreRejected(){upload();assertThrows(DomainConflictException.class,()->service.upload("GT-01",0,"新手册","MANUAL","v2","a.txt",new byte[]{65}));assertThrows(DomainConflictException.class,()->service.upload("GT-01",1,"厂家维护规程","MAINTENANCE","v1","a.txt",new byte[]{65}));}
    @Test void noFakePdfHtmlOrTraversalFilenameIsAccepted(){for(String name:List.of("a.pdf","a.html","../a.txt","a\\b.txt"))assertThrows(ValidationException.class,()->service.upload("GT-01",0,"资料","OTHER","v1",name,"not pdf".getBytes(StandardCharsets.UTF_8)));}
    @Test void hourlyCycleHasTraceableSourceAndDueStatus(){var d=upload();String id=d.documents().get(0).id();service.update("GT-01",withBasis(d,new MaintenanceBasis(id,"第3页","SIMULATED","HOURS",100,10,null,0d,95d,"测试班组")));assertEquals("UPCOMING",service.due("GT-01").get("status"));var current=saved.get();service.update("GT-01",withBasis(current,new MaintenanceBasis(id,"第3页","SIMULATED","HOURS",100,10,null,0d,105d,"测试班组")));assertEquals("DUE",service.due("GT-01").get("status"));assertThrows(DomainConflictException.class,()->service.archive("GT-01",id,saved.get().version()));}
    @Test void missingReadingIsNotNormalAndBadMeterOrSourceIsRejected(){var d=upload();String id=d.documents().get(0).id();service.update("GT-01",withBasis(d,new MaintenanceBasis(id,"条款1","MANUFACTURER","CYCLES",100,5,null,null,null,"班组")));assertEquals("MISSING_READING",service.due("GT-01").get("status"));assertThrows(ValidationException.class,()->service.update("GT-01",withBasis(saved.get(),new MaintenanceBasis(id,"条款1","MANUFACTURER","HOURS",100,5,null,90d,80d,"班组"))));assertThrows(ValidationException.class,()->service.update("GT-01",withBasis(saved.get(),new MaintenanceBasis("other-asset","条款1","MANUFACTURER","DAYS",10,1,Instant.now(),null,null,"班组"))));}
    @Test void archiveRetainsHistoryButRevokesDownload(){var d=upload();String id=d.documents().get(0).id();var archived=service.archive("GT-01",id,d.version());assertEquals("ARCHIVED",archived.documents().get(0).status());assertThrows(ResourceNotFoundException.class,()->service.download("GT-01",id));}
    @Test void tamperingIsDetected(){var d=upload();String id=d.documents().get(0).id();try{java.nio.file.Files.writeString(directory.resolve("private").resolve(id+".blob"),"changed");}catch(Exception e){throw new RuntimeException(e);}assertThrows(DomainConflictException.class,()->service.download("GT-01",id));}
}
