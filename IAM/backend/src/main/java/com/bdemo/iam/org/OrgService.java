package com.bdemo.iam.org;

import com.bdemo.iam.org.domain.OrgNode;
import com.bdemo.iam.org.mapper.OrgMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrgService {

    private final OrgMapper orgMapper;

    public OrgService(OrgMapper orgMapper) {
        this.orgMapper = orgMapper;
    }

    public List<OrgNode> tree() {
        List<OrgNode> all = orgMapper.selectAll();
        Map<String, OrgNode> byId = new LinkedHashMap<>();
        for (OrgNode n : all) {
            n.setChildren(null);
            byId.put(n.getId(), n);
        }
        List<OrgNode> roots = new ArrayList<>();
        for (OrgNode n : all) {
            String pid = n.getParentId();
            if (pid == null || pid.isBlank() || !byId.containsKey(pid)) {
                roots.add(n);
            } else {
                byId.get(pid).addChild(n);
            }
        }
        return roots;
    }

    /**
     * 根据级联编码（Z-01/C-01/D-01/G-01）解析各层级名称与完整路径。
     */
    public OrgResolveResult resolve(List<String> orgCodes) {
        OrgResolveResult r = new OrgResolveResult();
        if (orgCodes == null || orgCodes.isEmpty()) {
            return r;
        }
        Map<String, OrgNode> byCode = new LinkedHashMap<>();
        for (OrgNode n : orgMapper.selectAll()) {
            byCode.put(n.getCode(), n);
        }
        StringBuilder path = new StringBuilder();
        for (String code : orgCodes) {
            OrgNode n = byCode.get(code);
            if (n == null) {
                continue;
            }
            switch (n.getType()) {
                case "zone" -> r.zoneCode = code;
                case "company" -> r.companyCode = code;
                case "dept" -> r.deptCode = code;
                case "group" -> r.groupCode = code;
                default -> {
                }
            }
            String name = n.getName();
            r.putName(n.getType(), name);
            if (path.length() > 0) {
                path.append(" / ");
            }
            path.append(name);
        }
        r.orgPath = path.toString();
        return r;
    }

    public static class OrgResolveResult {
        public String zone;
        public String company;
        public String dept;
        public String group;
        public String zoneCode;
        public String companyCode;
        public String deptCode;
        public String groupCode;
        public String orgPath;

        void putName(String type, String name) {
            switch (type) {
                case "zone" -> this.zone = name;
                case "company" -> this.company = name;
                case "dept" -> this.dept = name;
                case "group" -> this.group = name;
                default -> {
                }
            }
        }
    }
}
