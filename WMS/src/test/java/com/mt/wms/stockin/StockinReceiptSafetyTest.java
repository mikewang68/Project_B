package com.mt.wms.stockin;

import com.mt.wms.auth.*;
import com.mt.wms.masterdata.BusinessSequenceService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockinReceiptSafetyTest {
    private final StockinRepository repository=mock(StockinRepository.class);
    private final TenantContextService tenants=mock(TenantContextService.class);
    private final BusinessSequenceService sequence=mock(BusinessSequenceService.class);
    private final PsmsReceiptRecorder psms=mock(PsmsReceiptRecorder.class);
    private final StockinReceiptIdempotency idempotency=mock(StockinReceiptIdempotency.class);
    private final StockinService service=new StockinService(repository,tenants,sequence,psms,idempotency);
    private final WmsPrincipal user=new WmsPrincipal(1L,2L,"C","Company","u","User",List.of("ADMIN"),Set.of("stockin:write"),false);
    private final MockHttpSession session=new MockHttpSession();
    private final StockinModels.ReceiveRequest receipt=new StockinModels.ReceiveRequest("REQ",false,List.of(new StockinModels.ReceiveLineRequest(5L,BigDecimal.TEN,"L",null,List.of(),null)));
    private final StockinModels.ReceiveResult previous=new StockinModels.ReceiveResult("IR-ORIGINAL",null,1,BigDecimal.TEN);
    @BeforeEach void setUp(){when(tenants.current(user,session)).thenReturn(new AuthModels.TenantView("C","Company",new AuthModels.TenantOption(3L,"W","Warehouse"),new AuthModels.TenantOption(4L,"O","Owner"),List.of(),List.of()));}
    @Test void exactRetryAfterCompletionReturnsOriginalReceiptWithoutAnyInventoryWrite(){
        when(repository.lockOrder(6,2,3,4)).thenReturn(new StockinRepository.OrderLock(6L,"R","COMPLETED"));
        when(idempotency.replay(2,3,4,6L,"REQ","RECEIVE",receipt)).thenReturn(Optional.of(previous));
        assertSame(previous,service.receive(6,receipt,user,session));
        verify(repository).lockOrder(6,2,3,4);verifyNoMoreInteractions(repository);verifyNoInteractions(psms,sequence);verify(idempotency,never()).save(anyLong(),anyLong(),anyLong(),anyLong(),anyString(),anyString(),any(),any());
    }
    @Test void scanRetryIsResolvedBeforeChoosingAnotherOutstandingBatch(){
        var scan=new StockinModels.ScanRequest("BAR",BigDecimal.TEN,"L",null,List.of(),null,false,"REQ");
        when(idempotency.replay(2,3,4,6L,"REQ","SCAN",scan)).thenReturn(Optional.of(previous));
        assertSame(previous,service.scan(6,scan,user,session));verify(repository,never()).lineIdByBarcode(anyLong(),anyString());verifyNoInteractions(sequence,psms);
    }
    @Test void quickRetryIsResolvedBeforeCreatingAnotherOrder(){
        var quick=new StockinModels.QuickRequest("NORMAL","GOOD",BigDecimal.TEN,"L",null,null,null,null,null,null,null,null,List.of(),null,"REQ");
        when(idempotency.replay(2,3,4,null,"REQ","QUICK",quick)).thenReturn(Optional.of(previous));
        assertSame(previous,service.quick(quick,user,session));verifyNoInteractions(repository,sequence,psms);
    }
    @Test void changedPayloadWithSameKeyStopsBeforeStateOrWrites(){
        when(repository.lockOrder(6,2,3,4)).thenReturn(new StockinRepository.OrderLock(6L,"R","DRAFT"));
        when(idempotency.replay(2,3,4,6L,"REQ","RECEIVE",receipt)).thenThrow(PsmsInboundException.conflict("different"));
        assertEquals(409,assertThrows(PsmsInboundException.class,()->service.receive(6,receipt,user,session)).status());verifyNoInteractions(psms,sequence);
    }
    @Test void linkedOrderCannotBeForcedCompleteOrCancelled(){
        when(repository.lockOrder(6,2,3,4)).thenReturn(new StockinRepository.OrderLock(6L,"R","RECEIVING"));when(psms.linked(6)).thenReturn(true);when(repository.allReceived(6)).thenReturn(false);
        var request=new StockinModels.ActionRequest("ACTION","test");
        assertEquals(409,assertThrows(PsmsInboundException.class,()->service.complete(6,request,user,session)).status());
        assertEquals(409,assertThrows(PsmsInboundException.class,()->service.cancel(6,request,user,session)).status());verify(repository,never()).state(anyLong(),anyString(),anyBoolean());verify(repository,never()).cancel(anyLong(),anyString());
    }
    @Test void linkedReceiptValidationHappensBeforeNewInventoryOperation(){
        when(repository.lockOrder(6,2,3,4)).thenReturn(new StockinRepository.OrderLock(6L,"R","DRAFT"));
        doThrow(PsmsInboundException.invalid("PSMS_OVER_RECEIPT_FORBIDDEN","over receipt")).when(psms).beforeReceive(6,receipt);
        assertThrows(PsmsInboundException.class,()->service.receive(6,receipt,user,session));verifyNoInteractions(sequence);verify(repository,never()).lockLine(anyLong(),anyLong());
    }
    @Test void authorizationScopeIsCheckedBeforeReturningAnyStoredResult(){
        when(repository.lockOrder(6,2,3,4)).thenThrow(new IllegalArgumentException("out of scope"));
        assertThrows(IllegalArgumentException.class,()->service.receive(6,receipt,user,session));verifyNoInteractions(idempotency,psms,sequence);
    }
}
