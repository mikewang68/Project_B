package com.mt.wms.stockin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PsmsReceiptRecorderTest {
    private final PsmsInboundRepository repository=mock(PsmsInboundRepository.class);
    private final PsmsJson json=new PsmsJson(new ObjectMapper().findAndRegisterModules());
    private PsmsInboundSettings settings(boolean enabled,String company,String owner,String project,String region){return new PsmsInboundSettings(enabled,"I".repeat(40),"O".repeat(40),company,owner,project,region);}
    private final PsmsReceiptRecorder recorder=new PsmsReceiptRecorder(repository,json,settings(true,"C","O","PROJECT","REGION"));
    private PsmsInboundRepository.TaskRow task(int sequence){return new PsmsInboundRepository.TaskRow(1,"T",1,"E","h","{}",10,20,"C","O","PROJECT","REGION","P","W",OffsetDateTime.now(),"WAREHOUSE_CONFIRMED",30L,"WH",40L,"R",sequence);}
    private PsmsInboundRepository.TaskLine line(long id,String line,BigDecimal received){return new PsmsInboundRepository.TaskLine(id,line,id,"STEEL"+id,"捆",new BigDecimal("100"),received,new BigDecimal("100").subtract(received),null,null,id);}
    private StockinModels.ReceiveRequest request(BigDecimal qty,boolean over){return new StockinModels.ReceiveRequest("KEY",over,List.of(new StockinModels.ReceiveLineRequest(1L,qty,"L",null,null,null)));}
    @Test void warehouseAndSixtyFortyEventsHaveScopeStableLinesAndIncreasingSequence(){
        recorder.confirmed(task(0));
        when(repository.byOrder(40,true)).thenReturn(Optional.of(task(1)),Optional.of(task(2)));
        when(repository.lines(1)).thenReturn(List.of(line(1,"L1",new BigDecimal("60"))),List.of(line(1,"L1",new BigDecimal("100"))));
        recorder.received(40,request(new BigDecimal("60"),false),new StockinModels.ReceiveResult("IR1",null,1,new BigDecimal("60")));
        recorder.received(40,request(new BigDecimal("40"),false),new StockinModels.ReceiveResult("IR2",null,1,new BigDecimal("40")));
        var events=ArgumentCaptor.forClass(PsmsInboundModels.InboundEvent.class);
        verify(repository,times(3)).appendEvent(any(),events.capture(),anyString());var all=events.getAllValues();
        assertEquals(List.of(1,2,3),all.stream().map(PsmsInboundModels.InboundEvent::sequence).toList());
        assertEquals(List.of("WAREHOUSE_CONFIRMED","PARTIALLY_RECEIVED","FULLY_RECEIVED"),all.stream().map(PsmsInboundModels.InboundEvent::eventType).toList());
        for(var e:all){assertEquals("C",e.companyCode());assertEquals("O",e.ownerCode());assertEquals("WH",e.warehouseCode());assertEquals("R",e.wmsOrderCode());}
        assertEquals(new BigDecimal("60"),all.get(1).lines().get(0).receivedDelta());assertEquals(new BigDecimal("40"),all.get(1).lines().get(0).remainingQty());
        assertEquals(new BigDecimal("100"),all.get(2).lines().get(0).receivedQty());assertEquals(3,all.stream().map(PsmsInboundModels.InboundEvent::eventId).distinct().count());
    }
    @Test void eventContainsUnchangedLinesWithZeroDelta(){
        when(repository.byOrder(40,true)).thenReturn(Optional.of(task(1)));
        when(repository.lines(1)).thenReturn(List.of(line(1,"L1",BigDecimal.TEN),line(2,"L2",BigDecimal.ZERO)));
        recorder.received(40,request(BigDecimal.TEN,false),new StockinModels.ReceiveResult("IR",null,1,BigDecimal.TEN));
        var event=ArgumentCaptor.forClass(PsmsInboundModels.InboundEvent.class);verify(repository).appendEvent(any(),event.capture(),anyString());
        assertEquals(2,event.getValue().lines().size());assertEquals(BigDecimal.ZERO,event.getValue().lines().get(1).receivedDelta());
    }
    @Test void linkedOrdersRejectOverReceiptFractionalQuantityAndForeignLines(){
        when(repository.byOrder(40,true)).thenReturn(Optional.of(task(1)));when(repository.lines(1)).thenReturn(List.of(line(1,"L1",new BigDecimal("60"))));
        for(var r:List.of(request(BigDecimal.ONE,true),request(new BigDecimal("0.5"),false),request(new BigDecimal("41"),false))){assertEquals(422,assertThrows(PsmsInboundException.class,()->recorder.beforeReceive(40,r)).status());}
        var foreign=new StockinModels.ReceiveRequest("K",false,List.of(new StockinModels.ReceiveLineRequest(999L,BigDecimal.ONE,"L",null,null,null)));
        assertEquals(409,assertThrows(PsmsInboundException.class,()->recorder.beforeReceive(40,foreign)).status());
        verify(repository,never()).appendEvent(any(),any(),anyString());
    }
    @Test void ordinaryStockinDoesNotProduceCrossSystemEvents(){
        when(repository.byOrder(anyLong(),anyBoolean())).thenReturn(Optional.empty());
        recorder.beforeReceive(40,request(BigDecimal.ONE,true));recorder.received(40,request(BigDecimal.ONE,true),new StockinModels.ReceiveResult("IR",null,1,BigDecimal.ONE));
        verify(repository,never()).appendEvent(any(),any(),anyString());verify(repository,never()).lines(anyLong());verify(repository,never()).markReceiptTransaction(anyLong());
    }
    @Test void transactionMarkerIsSetOnlyAfterLinkedReceiptValidation(){
        when(repository.byOrder(40,true)).thenReturn(Optional.of(task(1)));
        when(repository.lines(1)).thenReturn(List.of(line(1,"L1",new BigDecimal("60"))));
        when(repository.validateGood(eq(10L),eq(20L),any())).thenReturn(1L);
        recorder.beforeReceive(40,request(new BigDecimal("40"),false));
        var order=inOrder(repository);order.verify(repository).validateGood(eq(10L),eq(20L),any());order.verify(repository).markReceiptTransaction(40);
        clearInvocations(repository);
        assertThrows(PsmsInboundException.class,()->recorder.beforeReceive(40,request(new BigDecimal("41"),false)));
        verify(repository,never()).markReceiptTransaction(anyLong());
    }
    @Test void centralConfirmationMarksOnlyFullyReceivedLinkedOrderWithoutAnotherEvent(){
        var t=task(3);
        var full=new PsmsInboundRepository.TaskRow(t.id(),t.taskId(),t.version(),t.eventId(),t.requestHash(),t.requestBody(),
            t.companyId(),t.ownerId(),t.companyCode(),t.ownerCode(),t.projectId(),t.regionId(),t.planId(),t.workOrderId(),
            t.expectedArrivalAt(),"FULLY_RECEIVED",t.warehouseId(),t.warehouseCode(),t.wmsOrderId(),t.wmsOrderCode(),t.lastSequence());
        when(repository.byOrder(40,true)).thenReturn(Optional.of(t));
        assertThrows(PsmsInboundException.class,()->recorder.beforeComplete(40));verify(repository,never()).markReceiptTransaction(anyLong());
        when(repository.byOrder(40,true)).thenReturn(Optional.of(full));recorder.beforeComplete(40);
        verify(repository).markReceiptTransaction(40);verify(repository,never()).appendEvent(any(),any(),anyString());
    }
    @Test void disabledOrReboundServiceCannotAddStockOrEmitUndeliverableEvents(){
        when(repository.byOrder(40,true)).thenReturn(Optional.of(task(1)));
        var disabled=new PsmsReceiptRecorder(repository,json,settings(false,"C","O","PROJECT","REGION"));
        assertEquals(503,assertThrows(PsmsInboundException.class,()->disabled.beforeReceive(40,request(BigDecimal.ONE,false))).status());
        for(var binding:List.of(settings(true,"OTHER","O","PROJECT","REGION"),settings(true,"C","OTHER","PROJECT","REGION"),
                settings(true,"C","O","OTHER","REGION"),settings(true,"C","O","PROJECT","OTHER"))){
            var rebound=new PsmsReceiptRecorder(repository,json,binding);
            assertEquals(403,assertThrows(PsmsInboundException.class,()->rebound.beforeReceive(40,request(BigDecimal.ONE,false))).status());
        }
        verify(repository,never()).lines(anyLong());verify(repository,never()).markReceiptTransaction(anyLong());
        verify(repository,never()).appendEvent(any(),any(),anyString());
    }
    @Test void disabledServiceLeavesOrdinaryReceiptAndExistingFullConfirmationAvailable(){
        var disabled=new PsmsReceiptRecorder(repository,json,settings(false,"C","O","PROJECT","REGION"));
        when(repository.byOrder(40,true)).thenReturn(Optional.empty());
        assertDoesNotThrow(()->disabled.beforeReceive(40,request(BigDecimal.ONE,false)));
        verify(repository,never()).markReceiptTransaction(anyLong());
        var t=task(3);
        var full=new PsmsInboundRepository.TaskRow(t.id(),t.taskId(),t.version(),t.eventId(),t.requestHash(),t.requestBody(),
            t.companyId(),t.ownerId(),t.companyCode(),t.ownerCode(),t.projectId(),t.regionId(),t.planId(),t.workOrderId(),
            t.expectedArrivalAt(),"FULLY_RECEIVED",t.warehouseId(),t.warehouseCode(),t.wmsOrderId(),t.wmsOrderCode(),t.lastSequence());
        when(repository.byOrder(40,true)).thenReturn(Optional.of(full));assertDoesNotThrow(()->disabled.beforeComplete(40));
        verify(repository).markReceiptTransaction(40);verify(repository,never()).appendEvent(any(),any(),anyString());
    }
    @Test void outboxPersistenceFailureIsNotSwallowed(){
        doThrow(new IllegalStateException("database unavailable")).when(repository).appendEvent(any(),any(),anyString());
        assertThrows(IllegalStateException.class,()->recorder.confirmed(task(0)));
    }
}
