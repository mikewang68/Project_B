package com.bproject.ehm.platform.security;
import com.bproject.iam.context.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ScopeProjectionTest {
 UserContext context(ScopeSql.Mode mode,boolean readOnly,List<ScopeTriple> active){var c=mock(UserContext.class);when(c.scopeMode()).thenReturn(mode);when(c.readOnly()).thenReturn(readOnly);when(c.activeScope()).thenReturn(active);when(c.allowedScopes()).thenReturn(active);return c;}
 @Test void globalAdminCannotBeProjectedAsWritable(){var s=AccountController.scopeView(context(ScopeSql.Mode.GLOBAL,false,List.of(new ScopeTriple("PROJECT_B","EHMS_DEMO","EHMS"))),"PROJECT_B");assertEquals(false,s.get("writeAllowed"));assertEquals("SINGLE_SCOPE_REQUIRED",s.get("writeBlockReason"));assertEquals("GLOBAL",s.get("mode"));}
 @Test void exactlyOneMatchingScopeCanBeProjectedWritable(){var s=AccountController.scopeView(context(ScopeSql.Mode.SCOPED,false,List.of(new ScopeTriple("PROJECT_B","EHMS_DEMO","EHMS"))),"PROJECT_B");assertEquals(true,s.get("writeAllowed"));assertEquals("IAM_VERIFIED_CONTEXT",s.get("source"));}
 @Test void readonlyOtherProjectAndMultipleScopesRemainBlocked(){var a=new ScopeTriple("PROJECT_B","EHMS_DEMO","EHMS");var b=new ScopeTriple("PROJECT_B","SHEN_TIE","EHMS");assertEquals(false,AccountController.scopeView(context(ScopeSql.Mode.SCOPED,true,List.of(a)),"PROJECT_B").get("writeAllowed"));assertEquals(false,AccountController.scopeView(context(ScopeSql.Mode.SCOPED,false,List.of(a)),"OTHER").get("writeAllowed"));assertEquals(false,AccountController.scopeView(context(ScopeSql.Mode.SCOPED,false,List.of(a,b)),"PROJECT_B").get("writeAllowed"));}
}
