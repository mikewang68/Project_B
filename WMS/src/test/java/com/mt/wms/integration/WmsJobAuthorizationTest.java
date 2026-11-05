package com.mt.wms.integration;

import com.bproject.iam.context.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsJobAuthorizationTest {
    UserContext context(Map<String,Set<String>> operations,boolean readonly){
        var triple=new ScopeTriple("PROJECT_B","SHEN_TIE","WMS");
        var filters=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(triple));
        return new UserContext(2,"subject","operator","subject","operator","L4_OPERATOR",readonly,"WMS",
            triple.projectId(),triple.regionId(),triple.subsystemCode(),ScopeSql.Mode.SCOPED,
            List.of(triple),List.of(triple),Set.of("job"),operations.keySet(),operations,Set.of(),"version",
            filters.my_info(),filters.my_project_zone_info(),filters.my_info_template(),filters.my_project_zone_info_template(),filters.scope_params());
    }
    @Test void explicitReadonlyExportStillRequiresTheUnderlyingResourceView(){
        var allowed=context(Map.of("WMS:integration",Set.of("EXPORT","DOWNLOAD"),"WMS:stockin",Set.of("VIEW")),true);
        assertDoesNotThrow(()->WmsJobAuthorization.require(allowed,"STOCKIN","EXPORT"));
        assertDoesNotThrow(()->WmsJobAuthorization.require(allowed,"STOCKIN","DOWNLOAD"));
        assertThrows(AccessDeniedException.class,()->WmsJobAuthorization.require(allowed,"STOCKOUT","EXPORT"));
        assertThrows(AccessDeniedException.class,()->WmsJobAuthorization.require(allowed,"STOCKIN","UPLOAD"));
    }
    @Test void regionalOperatorsCannotUseUploadToChangeCompanyCatalogs(){
        var allowed=context(Map.of("WMS:integration",Set.of("UPLOAD"),"WMS:master-data",Set.of("VIEW","ADD"),"WMS:stockin",Set.of("VIEW","ADD")),false);
        assertDoesNotThrow(()->WmsJobAuthorization.require(allowed,"STOCKIN","UPLOAD"));
        assertThrows(AccessDeniedException.class,()->WmsJobAuthorization.require(allowed,"GOODS","UPLOAD"));
        assertThrows(AccessDeniedException.class,()->WmsJobAuthorization.require(allowed,"PARTNERS","UPLOAD"));
    }
    @Test void revokedQueuedExportFailsBeforeReadingAnyBusinessRows(){
        var repository=mock(IntegrationRepository.class);var authorization=mock(WmsJobAuthorization.class);
        var worker=new SpreadsheetWorker(repository,System.getProperty("java.io.tmpdir"));
        ReflectionTestUtils.setField(worker,"authorization",authorization);
        var task=new IntegrationModels.TaskRow(1L,2L,3L,4L,5L,"T1","EXPORT","STOCKIN","PENDING",null,"test.xlsx");
        when(repository.task(1L)).thenReturn(task);
        doThrow(new AccessDeniedException("revoked")).when(authorization).require(task);
        worker.exportTask(1L);
        verify(repository,never()).exportRows(anyString(),anyLong(),anyLong(),anyLong());
        verify(repository).fail(1L,"revoked");
    }
}
