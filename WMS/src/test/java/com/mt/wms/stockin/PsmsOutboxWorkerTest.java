package com.mt.wms.stockin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.*;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PsmsOutboxWorkerTest {
    private final PsmsInboundSettings settings=new PsmsInboundSettings(true,"I".repeat(40),"O".repeat(40),"C","OWNER","PROJECT","REGION");
    private final PsmsInboundRepository repository=mock(PsmsInboundRepository.class);
    private final TransactionTemplate tx=mock(TransactionTemplate.class);
    private final HttpClient http=mock(HttpClient.class);
    private final PsmsJson json=new PsmsJson(new ObjectMapper().findAndRegisterModules());
    private final PsmsOutboxWorker worker=new PsmsOutboxWorker(settings,repository,tx,json,http);
    private final PsmsInboundRepository.Outbox row=new PsmsInboundRepository.Outbox(1,"EVENT","TASK","{\"eventId\":\"EVENT\",\"taskId\":\"TASK\"}",1,"LEASE");
    @BeforeEach void setup(){
        when(tx.execute(any())).thenAnswer(inv->((TransactionCallback<?>)inv.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        doAnswer(inv->{((Consumer<TransactionStatus>)inv.getArgument(0)).accept(new SimpleTransactionStatus());return null;}).when(tx).executeWithoutResult(any());
        when(repository.claim(anyString(),eq("C"),eq("OWNER"),eq("PROJECT"),eq("REGION"))).thenReturn(Optional.of(row),Optional.empty());
    }
    @SuppressWarnings("unchecked") private void respond(int code,String body)throws Exception{
        var response=(HttpResponse<String>)mock(HttpResponse.class);when(response.statusCode()).thenReturn(code);when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
    @Test void successfulAckMarksTheExactStoredEventDelivered()throws Exception{
        respond(200,"{\"eventId\":\"EVENT\",\"taskId\":\"TASK\",\"duplicate\":true}");worker.sendPending();
        verify(repository).delivered(row);verify(repository,never()).failed(any(),anyBoolean(),anyString());
        var request=ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals(PsmsInboundSettings.CALLBACK_URL,request.getValue().uri().toString());assertEquals("Bearer "+"O".repeat(40),request.getValue().headers().firstValue("Authorization").orElseThrow());
    }
    @Test void ordinaryHtml200DoesNotLoseTheOutboxEvent()throws Exception{
        respond(200,"<html>login</html>");worker.sendPending();verify(repository).failed(eq(row),eq(false),contains("未确认"));verify(repository,never()).delivered(any());
    }
    @Test void wrongTaskOrEventAckDoesNotMarkDelivery()throws Exception{
        respond(200,"{\"eventId\":\"OTHER\",\"taskId\":\"TASK\"}");worker.sendPending();verify(repository).failed(eq(row),eq(false),anyString());verify(repository,never()).delivered(any());
    }
    @Test void networkFailureKeepsSameEventForAutomaticRetry()throws Exception{
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("private transport details"));worker.sendPending();
        verify(repository).failed(row,false,"PSMS暂时不可达，将重试原事件");verify(repository,never()).delivered(any());
    }
    @Test void authenticationFailureIsVisibleAndNeedsExplicitRetry()throws Exception{
        respond(401,"sensitive server body must not be copied");worker.sendPending();verify(repository).failed(row,true,"PSMS返回HTTP 401");verify(repository,never()).delivered(any());
    }
    @Test void redirectIsNotAcceptedAsDelivery()throws Exception{
        respond(302,"redirect elsewhere");worker.sendPending();verify(repository).failed(row,false,"PSMS返回HTTP 302");verify(repository,never()).delivered(any());
    }
    @Test void disabledFeatureDoesNotReadDatabaseOrCallNetwork(){
        new PsmsOutboxWorker(new PsmsInboundSettings(false,"","","","","",""),repository,tx,json,http).sendPending();verifyNoInteractions(repository,http);
    }
}
