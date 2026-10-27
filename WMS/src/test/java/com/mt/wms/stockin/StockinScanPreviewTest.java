package com.mt.wms.stockin;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.masterdata.BusinessSequenceService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockinScanPreviewTest {
    private final StockinRepository repository=mock(StockinRepository.class);
    private final TenantContextService tenants=mock(TenantContextService.class);
    private final BusinessSequenceService sequences=mock(BusinessSequenceService.class);
    private final MockHttpSession session=new MockHttpSession();
    private final WmsPrincipal reader=new WmsPrincipal(71L,11L,"test-company","Test company","reader","Reader",List.of("VIEWER"),Set.of("stockin:read"),false);
    private final StockinService service=new StockinService(repository,tenants,sequences);

    @BeforeEach void scope(){
        when(tenants.current(reader,session)).thenReturn(new AuthModels.TenantView("test-company","Test company",
                new AuthModels.TenantOption(22L,"test-warehouse","Warehouse"),new AuthModels.TenantOption(33L,"test-owner","Owner"),List.of(),List.of()));
    }
    private void order(){
        when(repository.order(44L,11L,22L,33L)).thenReturn(new StockinRepository.OrderHeader(44L,"TEST-INBOUND",null,null,"PURCHASE","CUSTOM","NEW",null,null,BigDecimal.ZERO,null,OffsetDateTime.now(),null));
        when(repository.partner(null)).thenReturn(new StockinRepository.PartnerRow(null,null));
    }
    private StockinModels.LineView line(long id,String barcode,String batch){
        return new StockinModels.LineView(id,1,"TEST-GOOD","Test good",barcode,BigDecimal.TEN,BigDecimal.ONE,new BigDecimal("9"),"TEST-LOCATION",null,"ZP",null,null,batch,BigDecimal.ZERO,null);
    }
    private void onlyReads(){
        verify(repository).order(44L,11L,22L,33L);verify(repository).lines(44L);verify(repository).partner(null);verifyNoMoreInteractions(repository);verifyNoInteractions(sequences);
    }
    @Test void readerCanResolveAllMatchingBatchesWithoutReceivingStock(){
        order();when(repository.lines(44L)).thenReturn(List.of(line(1L,"CODE","BATCH-A"),line(2L,"CODE","BATCH-B"),line(3L,"OTHER","BATCH-C")));
        var result=service.scanPreview(44L," CODE ",reader,session);
        assertEquals(44L,result.orderId());assertEquals("CODE",result.barcode());assertEquals(2,result.matches().size());assertEquals(List.of("BATCH-A","BATCH-B"),result.matches().stream().map(StockinModels.LineView::batchCode).toList());
        assertEquals(new BigDecimal("9"),result.matches().get(0).remainingQty());onlyReads();
    }
    @Test void rejectedScopeCannotReadOrderLines(){
        when(repository.order(44L,11L,22L,33L)).thenThrow(new IllegalArgumentException("Order is outside scope"));
        assertThrows(IllegalArgumentException.class,()->service.scanPreview(44L,"CODE",reader,session));
        verify(repository).order(44L,11L,22L,33L);verifyNoMoreInteractions(repository);verifyNoInteractions(sequences);
    }
    @Test void invalidCodesNeverReachTheDatabase(){
        for(String barcode:new String[]{null," ","X".repeat(129)})assertThrows(IllegalArgumentException.class,()->service.scanPreview(44L,barcode,reader,session));
        verifyNoInteractions(repository,sequences,tenants);
    }
    @Test void unmatchedCodeDoesNotWriteOrFallBackToAnotherOrder(){
        order();when(repository.lines(44L)).thenReturn(List.of(line(1L,"OTHER","BATCH-A")));
        assertThrows(IllegalArgumentException.class,()->service.scanPreview(44L,"CODE",reader,session));onlyReads();
    }
}
