package com.mt.wms.stockin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.iam.context.*;
import com.mt.wms.auth.*;
import com.mt.wms.auth.sso.WmsCentralScope;
import com.mt.wms.masterdata.BusinessSequenceService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;

class StockinConfirmationAuthorizationTest {
  final StockinRepository repo=mock(StockinRepository.class);
  final TenantContextService tenants=mock(TenantContextService.class);
  final BusinessSequenceService sequences=mock(BusinessSequenceService.class);
  final StockinService service=new StockinService(repo,tenants,sequences,mock(PsmsReceiptRecorder.class),mock(StockinReceiptIdempotency.class));
  final MockHttpSession session=new MockHttpSession();
  final WmsPrincipal actor=new WmsPrincipal(71L,11L,"test-company","Company","operator","Operator",List.of("OPERATOR"),Set.of("stockin:read","stockin:write"),false);
  final AtomicReference<String> state=new AtomicReference<>("NEW");
  @BeforeEach void setup() {
    when(tenants.current(actor,session)).thenReturn(new AuthModels.TenantView("test-company","Company",new AuthModels.TenantOption(22L,"warehouse","Warehouse"),new AuthModels.TenantOption(33L,"owner","Owner"),List.of(),List.of()));
    when(repo.lockOrder(44L,11L,22L,33L)).thenAnswer(i->new StockinRepository.OrderLock(44L,"TEST-INBOUND",state.get()));
    when(repo.lockLine(55L,44L)).thenReturn(new StockinRepository.LineLock(55L,44L,1,77L,BigDecimal.ONE,BigDecimal.ZERO,null,null,"ZP",null,null,"TEST-BATCH",BigDecimal.ZERO));
    when(repo.locationId(11L,22L,"STAGE")).thenReturn(66L);
    when(repo.lockBalance(11L,22L,33L,66L,77L,"TEST-BATCH","ZP","-","-")).thenReturn(Optional.of(new StockinRepository.BalanceRow(88L,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)));
    when(repo.allReceived(44L)).thenReturn(true);
    when(sequences.nextScoped("IR",11L,22L,33L)).thenReturn("TEST-RECEIPT");
    doAnswer(i->{state.set(i.getArgument(1));return null;}).when(repo).state(eq(44L),anyString(),anyBoolean());
    when(repo.order(44L,11L,22L,33L)).thenAnswer(i->new StockinRepository.OrderHeader(44L,"TEST-INBOUND",null,null,"NORMAL","CUSTOM",state.get(),null,null,BigDecimal.ZERO,null,OffsetDateTime.now(),null));
    when(repo.lines(44L)).thenReturn(List.of());when(repo.partner(null)).thenReturn(new StockinRepository.PartnerRow(null,null));
  }
  @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
  void context(Set<String> operations) {
    var triple=new ScopeTriple("PROJECT_TEST","REGION_TEST","WMS");var scopes=List.of(triple);var f=ScopeSql.build(ScopeSql.Mode.SCOPED,scopes);
    var c=new UserContext(2,"subject","operator","subject","Operator","L4_OPERATOR",false,"WMS",triple.projectId(),triple.regionId(),triple.subsystemCode(),ScopeSql.Mode.SCOPED,scopes,scopes,Set.of(),Set.of("WMS:stockin"),Map.of("WMS:stockin",operations),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    var request=new MockHttpServletRequest();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));WmsCentralScope.bind(c);
  }
  StockinModels.ReceiveResult receive(){return service.receive(44L,new StockinModels.ReceiveRequest("TEST-RECEIVE",false,List.of(new StockinModels.ReceiveLineRequest(55L,BigDecimal.ONE,"STAGE",null,List.of(),null))),actor,session);}
  @Test void completeQuantityWithEditOnlyRemainsUnconfirmed() {
    context(Set.of("VIEW","EDIT"));assertEquals("RECEIVING",receive().order().state());verify(repo).addInventory(88L,BigDecimal.ONE);verify(repo,never()).state(44L,"COMPLETED",true);
  }
  @Test void evenAuthorizedConfirmerMustInvokeConfirmationSeparately() {
    context(Set.of("VIEW","EDIT","CONFIRM"));assertEquals("RECEIVING",receive().order().state());
    assertEquals("COMPLETED",service.complete(44L,new StockinModels.ActionRequest("TEST-CONFIRM",null),actor,session).state());
    var order=inOrder(repo);order.verify(repo).state(44L,"RECEIVING",false);order.verify(repo).state(44L,"COMPLETED",true);
  }
  @Test void legacyReceiptKeepsExistingAutomaticCompletion() {
    assertEquals("COMPLETED",receive().order().state());verify(repo).state(44L,"COMPLETED",true);
  }
  @Test void incompleteV2ReceiptAlsoRemainsReceiving() {
    context(Set.of("VIEW","EDIT"));when(repo.allReceived(44L)).thenReturn(false);assertEquals("RECEIVING",receive().order().state());verify(repo,never()).state(44L,"COMPLETED",true);
  }
}
