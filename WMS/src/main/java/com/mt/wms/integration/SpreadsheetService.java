package com.mt.wms.integration;

import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.TenantContextService;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.masterdata.BusinessSequenceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
class SpreadsheetService {
    private static final Set<String> EXPORTS=Set.of("GOODS","PARTNERS","INVENTORY","STOCKIN","STOCKOUT","FINANCE");
    private static final Set<String> IMPORTS=Set.of("GOODS","PARTNERS","STOCKIN","STOCKOUT");
    private final IntegrationRepository repository;private final SpreadsheetWorker worker;private final TenantContextService tenants;private final BusinessSequenceService sequences;
    SpreadsheetService(IntegrationRepository repository,SpreadsheetWorker worker,TenantContextService tenants,BusinessSequenceService sequences){this.repository=repository;this.worker=worker;this.tenants=tenants;this.sequences=sequences;}
    List<IntegrationModels.TaskView> list(WmsPrincipal p,HttpSession session){Scope s=scope(p,session);return repository.tasks(s.c,s.w,s.o);}
    IntegrationModels.TaskView export(String raw,WmsPrincipal p,HttpSession session)throws Exception{String resource=type(raw,EXPORTS);Scope s=scope(p,session);String code=sequences.next("T",p,session),stored=UUID.randomUUID()+".xlsx";long id=repository.task(s.c,s.w,s.o,code,resource+"导出","EXPORT",resource,null,stored,p.userId());worker.exportTask(id);return repository.tasks(s.c,s.w,s.o).stream().filter(x->x.id()==id).findFirst().orElseThrow();}
    IntegrationModels.TaskView upload(String raw,MultipartFile file,WmsPrincipal p,HttpSession session)throws Exception{String resource=type(raw,IMPORTS);if(file==null||file.isEmpty())throw new IllegalArgumentException("请选择Excel文件");String original=file.getOriginalFilename()==null?"import.xlsx":file.getOriginalFilename();if(!original.toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw new IllegalArgumentException("只支持 .xlsx 文件");Scope s=scope(p,session);String code=sequences.next("T",p,session),stored=UUID.randomUUID()+".xlsx";Path path=worker.path(stored);Files.createDirectories(path.getParent());file.transferTo(path);long id=repository.task(s.c,s.w,s.o,code,resource+"导入","IMPORT",resource,original,stored,p.userId());worker.importTask(id);return repository.tasks(s.c,s.w,s.o).stream().filter(x->x.id()==id).findFirst().orElseThrow();}
    Path download(long id,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);IntegrationModels.TaskRow task=repository.task(id,s.c,s.w,s.o);if(!task.state().equals("COMPLETED"))throw new IllegalArgumentException("任务尚未完成，暂不能下载");return worker.path(task.storedFileName());}
    String downloadName(long id,WmsPrincipal p,HttpSession session){Scope s=scope(p,session);IntegrationModels.TaskRow t=repository.task(id,s.c,s.w,s.o);return t.taskCode()+"-"+t.resourceType()+".xlsx";}
    void template(String raw,OutputStream out)throws Exception{worker.template(type(raw,IMPORTS),out);}
    private Scope scope(WmsPrincipal p,HttpSession session){AuthModels.TenantView t=tenants.current(p,session);return new Scope(p.companyId(),t.currentWarehouse().id(),t.currentOwner().id());}
    private static String type(String raw,Set<String> allowed){String v=raw.toUpperCase(Locale.ROOT);if(!allowed.contains(v))throw new IllegalArgumentException("不支持的资源类型："+raw);return v;}
    private record Scope(long c,long w,long o){}
}
