package com.mt.wms.stockin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PsmsSchemaVerifierTest {
    @Test void missingDisabledOrOldGuardsFailClosed(){
        assertDoesNotThrow(()->PsmsSchemaVerifier.validate(31,"20261104-v1",3));
        assertThrows(IllegalStateException.class,()->PsmsSchemaVerifier.validate(28,"20261104-v1",3));
        assertThrows(IllegalStateException.class,()->PsmsSchemaVerifier.validate(31,"old",3));
        assertThrows(IllegalStateException.class,()->PsmsSchemaVerifier.validate(31,"20261104-v1",2));
    }
    @Test void unreachableSchemaStopsStartupWithoutCreatingAnything(){
        var jdbc=mock(JdbcClient.class);when(jdbc.sql(anyString())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,()->new PsmsSchemaVerifier(jdbc).run(null));
        verify(jdbc).sql(argThat(sql->sql.stripLeading().startsWith("SELECT")));verifyNoMoreInteractions(jdbc);
    }
}
