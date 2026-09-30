package com.bdemo.iam.org.mapper;

import com.bdemo.iam.org.domain.OrgNode;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrgMapper {

  @Select(
      """
SELECT status, id, parent_id AS parentId, org_name AS name, org_type AS type, org_code AS code, path
FROM iam_org
WHERE status = 'active'
ORDER BY sort_order ASC, id ASC
""")
  List<OrgNode> selectAll();
}
