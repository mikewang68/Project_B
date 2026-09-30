package com.bdemo.iam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bdemo.iam.org.OrgService;
import com.bdemo.iam.org.domain.OrgNode;
import com.bdemo.iam.org.mapper.OrgMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrganizationBoundaryTest {
  OrgNode node(String id, String parent, String type, String status) {
    var n = new OrgNode();
    n.setId(id);
    n.setCode(id);
    n.setParentId(parent);
    n.setType(type);
    n.setStatus(status);
    n.setName(id);
    return n;
  }

  @Test
  void invalidOrInactiveOrCrossBranchPathIsNeverDowngraded() {
    var mapper = mock(OrgMapper.class);
    when(mapper.selectAll())
        .thenReturn(
            List.of(
                node("z", null, "zone", "active"),
                node("c", "z", "company", "active"),
                node("other", null, "zone", "active"),
                node("d", "c", "dept", "inactive")));
    var service = new OrgService(mapper);
    assertEquals("c", service.resolve(List.of("z", "c")).companyCode);
    for (var path :
        List.of(
            List.<String>of(),
            List.of("z"),
            List.of("unknown", "c"),
            List.of("other", "c"),
            List.of("z", "c", "d"),
            List.of("z", "c", "c")))
      assertThrows(com.bdemo.iam.common.BizException.class, () -> service.resolve(path));
  }
}
