package com.mt.wms.stockin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PsmsInboundServiceTest {
    private final PsmsInboundRepository repository=mock(PsmsInboundRepository.class);
    private final PsmsInboundSettings settings=new PsmsInboundSettings(true,"I".repeat(40),"O".repeat(40),"COMPANY","OWNER","PROJECT","REGION");
    private final PsmsJson json=new PsmsJson(new ObjectMapper().findAndRegisterModules());
    private final TenantContextService tenants=mock(TenantContextService.class);
    private final StockinService stockin=mock(StockinService.class);
    private final PsmsReceiptRecorder recorder=mock(PsmsReceiptRecorder.class);
    private final PsmsInboundService service=new PsmsInboundService(repository,settings,json,tenants,stockin,recorder);
    private final WmsPrincipal principal=new WmsPrincipal(7L,1L,"COMPANY","Company","user","User",List.of("MANAGER"),Set.of("stockin:read","stockin:write"),false);
    private final MockHttpSession session=new MockHttpSession();
    private static final OffsetDateTime AT=OffsetDateTime.parse("2026-11-04T16:00:00+08:00");
    static PsmsInboundModels.TaskRequest request(String event,String task,BigDecimal qty){return new PsmsInboundModels.TaskRequest("1.0",event,task,1,"PLAN","WORK","COMPANY","OWNER",AT,List.of(new PsmsInboundModels.TaskLineRequest("L1","STEEL",qty,"捆","B1",null)));}
    private PsmsInboundRepository.TaskRow row(String hash,Long warehouse,Long order,String status){return new PsmsInboundRepository.TaskRow(10L,"TASK",1,"EVENT",hash,"{}",1,2,"COMPANY","OWNER","PROJECT","REGION","PLAN","WORK",AT,status,warehouse,warehouse==null?null:"WH",order,order==null?null:"R001",order==null?0:1);}
    @BeforeEach void initialize(){when(repository.lockScope("COMPANY","OWNER")).thenReturn(new PsmsInboundRepository.Scope(1,2));when(tenants.current(principal,session)).thenReturn(new AuthModels.TenantView("COMPANY","Company",new AuthModels.TenantOption(3L,"WH","Warehouse"),new AuthModels.TenantOption(2L,"OWNER","Owner"),List.of(),List.of()));}
    @Test void acceptingTaskWritesOnlyAnInboxAndNeverStock(){
        var request=request("EVENT","TASK",new BigDecimal("100.0"));
        when(repository.insert(any(),any(),anyString(),anyString(),eq("PROJECT"),eq("REGION"))).thenReturn(10L);
        when(repository.validateGood(eq(1L),eq(2L),any())).thenReturn(90L);
        var result=service.accept(request);
        assertEquals("WAITING_WAREHOUSE",result.status());assertEquals("TASK",result.taskId());
        verify(repository).insertLine(eq(10L),any(),eq(90L));verifyNoInteractions(stockin,recorder);
    }
    @Test void sameEventReplaysAcceptedResultEvenAfterReceiving(){
        var request=request("EVENT","TASK",new BigDecimal("100"));
        when(repository.byEvent("EVENT")).thenReturn(Optional.of(row(json.hash(PsmsInboundService.normalize(request)),3L,20L,"FULLY_RECEIVED")));
        assertEquals("WAITING_WAREHOUSE",service.accept(request).status());
        verify(repository,never()).insert(any(),any(),anyString(),anyString(),eq("PROJECT"),eq("REGION"));verify(repository,never()).validateGood(anyLong(),anyLong(),any());verifyNoInteractions(stockin);
    }
    @Test void sameTaskOrEventWithDifferentPayloadConflicts(){
        when(repository.byEvent("EVENT")).thenReturn(Optional.of(row("other-payload",null,null,"WAITING_WAREHOUSE")));
        assertEquals(409,assertThrows(PsmsInboundException.class,()->service.accept(request("EVENT","TASK",BigDecimal.TEN))).status());
        verify(repository,never()).insert(any(),any(),anyString(),anyString(),eq("PROJECT"),eq("REGION"));
    }
    @Test void companyAndOwnerCannotBeChosenOutsideTheServiceBinding(){
        var r=request("EVENT","TASK",BigDecimal.TEN);
        var other=new PsmsInboundModels.TaskRequest(r.schemaVersion(),r.eventId(),r.taskId(),1,r.planId(),r.workOrderId(),"OTHER",r.ownerCode(),r.expectedArrivalAt(),r.lines());
        assertEquals(403,assertThrows(PsmsInboundException.class,()->service.accept(other)).status());verifyNoInteractions(repository);
    }
    @Test void duplicateLinesAndFractionalStockingUnitsAreRejected(){
        var r=request("EVENT","TASK",BigDecimal.TEN);
        var duplicate=new PsmsInboundModels.TaskRequest(r.schemaVersion(),r.eventId(),r.taskId(),1,r.planId(),r.workOrderId(),r.companyCode(),r.ownerCode(),r.expectedArrivalAt(),List.of(r.lines().get(0),r.lines().get(0)));
        assertEquals(400,assertThrows(PsmsInboundException.class,()->service.accept(duplicate)).status());
        assertEquals(422,assertThrows(PsmsInboundException.class,()->service.accept(request("EVENT","TASK",new BigDecimal("1.5")))).status());verifyNoInteractions(stockin);
    }
    @Test void numericAndTimezoneRepresentationsNormalizeBeforeFingerprint(){
        var a=request("EVENT","TASK",new BigDecimal("100"));
        var b=new PsmsInboundModels.TaskRequest(a.schemaVersion(),a.eventId(),a.taskId(),1,a.planId(),a.workOrderId(),a.companyCode(),a.ownerCode(),AT.withOffsetSameInstant(java.time.ZoneOffset.UTC),List.of(new PsmsInboundModels.TaskLineRequest("L1","STEEL",new BigDecimal("100.000"),"捆","B1","")));
        assertEquals(json.hash(PsmsInboundService.normalize(a)),json.hash(PsmsInboundService.normalize(b)));
    }
    @Test void confirmedTaskReturnsSameOrderWithoutCreatingAgain(){
        when(repository.find("TASK",1,2,true)).thenReturn(Optional.of(row("h",3L,20L,"WAREHOUSE_CONFIRMED")));
        var result=service.confirm("TASK",new PsmsInboundModels.ConfirmRequest("WH","OWNER"),principal,session);
        assertEquals(20L,result.wmsOrderId());verifyNoInteractions(stockin,recorder);
    }
    @Test void firstConfirmationCreatesOneNormalOrderAndAnAtomicWarehouseReceipt(){
        when(repository.find("TASK",1,2,true)).thenReturn(Optional.of(row("h",null,null,"WAITING_WAREHOUSE")));
        var line=new PsmsInboundRepository.TaskLine(100,"L1",90,"STEEL","捆",new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("100"),"B1",null,null);
        when(repository.lines(10)).thenReturn(List.of(line));when(repository.validateGood(eq(1L),eq(2L),any())).thenReturn(90L);
        var order=mock(StockinModels.OrderDetail.class);var orderLine=mock(StockinModels.LineView.class);
        when(order.id()).thenReturn(20L);when(order.orderCode()).thenReturn("R001");when(orderLine.id()).thenReturn(501L);when(order.lines()).thenReturn(List.of(orderLine));
        when(stockin.createForPsms(any(),eq(principal),eq(3L),eq(2L))).thenReturn(order);
        when(repository.byOrder(20,true)).thenReturn(Optional.of(row("h",3L,20L,"WAREHOUSE_CONFIRMED")));
        when(repository.byOrder(20,false)).thenReturn(Optional.of(row("h",3L,20L,"WAREHOUSE_CONFIRMED")));
        assertEquals(20L,service.confirm("TASK",new PsmsInboundModels.ConfirmRequest("WH","OWNER"),principal,session).wmsOrderId());
        var create=org.mockito.ArgumentCaptor.forClass(StockinModels.CreateRequest.class);
        verify(stockin).createForPsms(create.capture(),eq(principal),eq(3L),eq(2L));assertEquals("CUSTOM",create.getValue().source());assertEquals(new BigDecimal("100"),create.getValue().lines().get(0).plannedQty());
        verify(repository).linkLine(100,501);verify(repository).confirm(10,3,"WH",order);verify(recorder).confirmed(any());
    }
    @Test void materialIdentityChangesBetweenIntakeAndConfirmationBlockCreation(){
        when(repository.find("TASK",1,2,true)).thenReturn(Optional.of(row("h",null,null,"WAITING_WAREHOUSE")));
        when(repository.lines(10)).thenReturn(List.of(new PsmsInboundRepository.TaskLine(100,"L1",90,"STEEL","捆",BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.TEN,null,null,null)));
        when(repository.validateGood(eq(1L),eq(2L),any())).thenReturn(99L);
        assertEquals("PSMS_MATERIAL_CHANGED",assertThrows(PsmsInboundException.class,()->service.confirm("TASK",new PsmsInboundModels.ConfirmRequest("WH","OWNER"),principal,session)).code());verifyNoInteractions(stockin,recorder);
    }
    @Test void confirmationCannotUseAnotherWarehouseOrOwnerOrChangeSharedSession(){
        assertEquals(403,assertThrows(PsmsInboundException.class,()->service.confirm("TASK",new PsmsInboundModels.ConfirmRequest("OTHER","OWNER"),principal,session)).status());
        assertEquals(403,assertThrows(PsmsInboundException.class,()->service.confirm("TASK",new PsmsInboundModels.ConfirmRequest("WH","OTHER"),principal,session)).status());
        verifyNoInteractions(repository,stockin,recorder);assertFalse(session.getAttributeNames().hasMoreElements());
    }
    @Test void alreadyAssignedOtherWarehouseIsHidden(){
        when(repository.find("TASK",1,2,true)).thenReturn(Optional.of(row("h",99L,20L,"WAREHOUSE_CONFIRMED")));
        assertEquals(404,assertThrows(PsmsInboundException.class,()->service.confirm("TASK",new PsmsInboundModels.ConfirmRequest("WH","OWNER"),principal,session)).status());verifyNoInteractions(stockin);
    }
    @Test void tokenRebindingCannotReplayAnotherProjectTask(){
        var r=request("EVENT","TASK",BigDecimal.TEN);var old=row(json.hash(PsmsInboundService.normalize(r)),null,null,"WAITING_WAREHOUSE");
        var other=new PsmsInboundRepository.TaskRow(old.id(),old.taskId(),old.version(),old.eventId(),old.requestHash(),old.requestBody(),
            old.companyId(),old.ownerId(),old.companyCode(),old.ownerCode(),"OTHER_PROJECT",old.regionId(),old.planId(),old.workOrderId(),
            old.expectedArrivalAt(),old.status(),old.warehouseId(),old.warehouseCode(),old.wmsOrderId(),old.wmsOrderCode(),old.lastSequence());
        when(repository.byEvent("EVENT")).thenReturn(Optional.of(other));
        assertEquals(409,assertThrows(PsmsInboundException.class,()->service.accept(r)).status());verifyNoInteractions(stockin,recorder);
    }
    @Test void warehouseMustBelongToTheTasksFixedCentralTuple(){
        when(repository.find("TASK",1,2,true)).thenReturn(Optional.of(row("h",null,null,"WAITING_WAREHOUSE")));
        doThrow(new PsmsInboundException(403,"PSMS_WAREHOUSE_SCOPE_MISMATCH","tuple mismatch"))
            .when(repository).requireWarehouseScope(3L,1L,"PROJECT","REGION");
        assertEquals(403,assertThrows(PsmsInboundException.class,()->service.confirm("TASK",new PsmsInboundModels.ConfirmRequest("WH","OWNER"),principal,session)).status());
        verifyNoInteractions(stockin,recorder);
    }
    @Test void disablingDeliveryDoesNotHideAuthorizedTaskHistory(){
        var disabled=new PsmsInboundSettings(false,"","","COMPANY","OWNER","PROJECT","REGION");
        var paused=new PsmsInboundService(repository,disabled,json,tenants,stockin,recorder);
        var row=row("h",3L,20L,"FULLY_RECEIVED");
        when(repository.list(1,2,3)).thenReturn(List.of(row));
        when(repository.find("TASK",1,2,false)).thenReturn(Optional.of(row));
        assertEquals(1,paused.list(principal,session).size());assertEquals("FULLY_RECEIVED",paused.get("TASK",principal,session).status());
        verifyNoInteractions(stockin,recorder);
    }
    @Test void invalidOrSharedServiceCredentialsCannotEnableTheFeature(){
        assertThrows(IllegalStateException.class,()->new PsmsInboundSettings(true,"","","COMPANY","OWNER","PROJECT","REGION"));
        assertThrows(IllegalStateException.class,()->new PsmsInboundSettings(true,"X".repeat(40),"X".repeat(40),"COMPANY","OWNER","PROJECT","REGION"));
        var disabled=new PsmsInboundSettings(false,"","","","","","");assertFalse(disabled.accepts("Bearer "));
        assertTrue(settings.accepts("Bearer "+"I".repeat(40)));assertFalse(settings.accepts("Bearer "+"O".repeat(40)));
    }
}
