package com.bproject.ehm.asset.application;

import com.bproject.ehm.asset.domain.model.DeviceDossier;
import com.bproject.ehm.asset.domain.model.DeviceDossier.*;
import com.bproject.ehm.asset.ports.*;
import com.bproject.ehm.shared.error.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.*;
import java.security.MessageDigest;
import java.nio.charset.*;
import java.nio.ByteBuffer;
import java.util.*;

@Service @Profile("server")
public class DeviceDossierService {
    private final DeviceDossierRepository repository;
    private final DeviceDocumentStore files;
    private final AssetQueryFacade assets;
    public DeviceDossierService(DeviceDossierRepository repository,DeviceDocumentStore files,AssetQueryFacade assets){
        this.repository=repository;this.files=files;this.assets=assets;
    }
    public DeviceDossier get(String code){String normalized=com.bproject.ehm.asset.domain.model.Asset.normalizeCode(code);assets.get(normalized);return repository.find(normalized).orElse(DeviceDossier.empty(normalized));}
    public DeviceDossier update(String code,DeviceDossier request){
        var old=get(code);requireVersion(old,request.version());
        var basis=validateBasis(request.maintenanceBasis(),old.documents());
        return repository.save(new DeviceDossier(old.assetCode(),text(request.manufacturer(),120),text(request.model(),120),
            text(request.serialNumber(),120),date(request.commissionedOn()),text(request.processPosition(),160),
            text(request.externalCode(),120),text(request.notes(),2000),basis,old.documents(),old.version()+1,Instant.now()),old.version());
    }
    public DeviceDossier upload(String code,long version,String title,String kind,String revision,String filename,byte[] bytes){
        var old=get(code);requireVersion(old,version);
        if(old.documents().size()>=100)throw new ValidationException("每台设备最多登记100个资料版本，请联系管理员归档整理");
        if(bytes==null||bytes.length==0||bytes.length>5*1024*1024)throw new ValidationException("资料应为1字节至5MB");
        if(filename==null||filename.length()>160||filename.matches(".*[\\\\/\\p{Cntrl}].*"))throw new ValidationException("文件名不合法");
        String type=type(filename,bytes);
        if(kind==null||!Set.of("MANUAL","MAINTENANCE","FAULT_CODES","CALIBRATION","OTHER").contains(kind))throw new ValidationException("资料类别不合法");
        String name=text(title,160);if(name.isBlank())throw new ValidationException("请输入资料标题");
        String rev=text(revision,80);if(rev.isBlank())throw new ValidationException("请填写资料版本或日期");
        if(old.documents().stream().anyMatch(d->d.status().equals("ACTIVE")&&d.title().equals(name)&&d.revision().equals(rev)))
            throw new DomainConflictException("同名同版本资料已存在，请核对或使用新版本");
        String id=UUID.randomUUID().toString();files.put(id,bytes);
        var docs=new ArrayList<>(old.documents());docs.add(new DeviceDocument(id,name,kind,rev,filename,type,bytes.length,hash(bytes),"ACTIVE",Instant.now()));
        return repository.save(copy(old,docs,old.maintenanceBasis()),old.version());
    }
    public DeviceDossier archive(String code,String id,long version){
        var old=get(code);requireVersion(old,version);findDocument(old,id);
        if(old.maintenanceBasis()!=null&&id.equals(old.maintenanceBasis().sourceDocumentId()))
            throw new DomainConflictException("该资料正在作为维护依据，请先更新维护依据再归档");
        var docs=old.documents().stream().map(d->d.id().equals(id)?new DeviceDocument(d.id(),d.title(),d.kind(),d.revision(),d.filename(),d.contentType(),d.size(),d.sha256(),"ARCHIVED",d.createdAt()):d).toList();
        return repository.save(copy(old,docs,old.maintenanceBasis()),old.version());
    }
    public record Download(DeviceDocument metadata,byte[] bytes){}
    public Download download(String code,String id){
        var doc=findDocument(get(code),id);byte[] bytes=files.get(doc.id());
        if(!hash(bytes).equals(doc.sha256()))throw new DomainConflictException("资料完整性核验失败，已拒绝下载，请联系管理员");
        return new Download(doc,bytes);
    }
    public Map<String,Object> due(String code){
        var dossier=get(code);var b=dossier.maintenanceBasis();
        if(b==null)return Map.of("status","UNCONFIGURED","message","尚未登记维护依据，不自动套用其他设备周期","checkedAt",Instant.now());
        try{validateBasis(b,dossier.documents());}catch(ValidationException invalid){return Map.of("status","INVALID_BASIS","message","现有维护依据不完整或已失效："+invalid.getMessage(),"checkedAt",Instant.now());}
        Double elapsed=null;
        if(b.unit().equals("DAYS")&&b.lastCompletedAt()!=null)elapsed=Duration.between(b.lastCompletedAt(),Instant.now()).toSeconds()/86400d;
        if(!b.unit().equals("DAYS")&&b.currentReading()!=null&&b.lastServiceReading()!=null)elapsed=b.currentReading()-b.lastServiceReading();
        if(elapsed==null)return Map.of("status","MISSING_READING","message","已登记周期，但缺少上次完成时间或累计读数，不能判断到期","checkedAt",Instant.now());
        double remaining=Math.max(0,b.interval()-elapsed);
        String status=elapsed>=b.interval()?"DUE":remaining<=b.reminderLead()?"UPCOMING":"NOT_DUE";
        return Map.of("status",status,"elapsed",Math.round(elapsed*100)/100d,"remaining",Math.round(remaining*100)/100d,
                "unit",b.unit(),"sourceKind",b.sourceKind(),"sourceDocumentId",b.sourceDocumentId(),"checkedAt",Instant.now(),
                "message","按已登记依据计算；计数读数为人工登记，不是已核验的PLC累计值；不执行设备控制");
    }
    private DeviceDocument findDocument(DeviceDossier value,String id){return value.documents().stream().filter(d->d.id().equals(id)&&d.status().equals("ACTIVE")).findFirst().orElseThrow(()->new ResourceNotFoundException("资料不存在、已归档或不在当前设备范围"));}
    private DeviceDossier copy(DeviceDossier d,List<DeviceDocument> docs,MaintenanceBasis basis){return new DeviceDossier(d.assetCode(),d.manufacturer(),d.model(),d.serialNumber(),d.commissionedOn(),d.processPosition(),d.externalCode(),d.notes(),basis,docs,d.version()+1,Instant.now());}
    private void requireVersion(DeviceDossier old,long version){if(old.version()!=version)throw new DomainConflictException("资料已被其他人修改，请刷新后重试；当前输入尚未保存");}
    private MaintenanceBasis validateBasis(MaintenanceBasis b,List<DeviceDocument> docs){
        if(b==null)return null;
        if(b.unit()==null||!Set.of("DAYS","HOURS","CYCLES").contains(b.unit())||!Double.isFinite(b.interval())||b.interval()<=0||b.interval()>1000000
                ||!Double.isFinite(b.reminderLead())||b.reminderLead()<0||b.reminderLead()>=b.interval())throw new ValidationException("周期须为正数，提前提醒量须小于周期");
        if(b.sourceKind()==null||!Set.of("MANUFACTURER","OWNER_CONFIRMED","SIMULATED").contains(b.sourceKind()))throw new ValidationException("请选择依据来源");
        if(docs.stream().noneMatch(d->d.id().equals(b.sourceDocumentId())&&d.status().equals("ACTIVE")))throw new ValidationException("维护依据须关联本设备未归档的资料版本");
        if(b.lastCompletedAt()!=null&&b.lastCompletedAt().isAfter(Instant.now()))throw new ValidationException("上次维护完成时间不能在未来");
        for(Double v:Arrays.asList(b.lastServiceReading(),b.currentReading()))if(v!=null&&(!Double.isFinite(v)||v<0))throw new ValidationException("累计读数应为非负有限数值");
        if(b.currentReading()!=null&&b.lastServiceReading()!=null&&b.currentReading()<b.lastServiceReading())throw new ValidationException("当前累计读数不能小于上次维护读数；换表或清零须先核对履历");
        String section=text(b.sourceSection(),300);if(section.isBlank())throw new ValidationException("请填写维护依据的章节、页码或条款");
        return new MaintenanceBasis(b.sourceDocumentId(),section,b.sourceKind(),b.unit(),b.interval(),b.reminderLead(),b.lastCompletedAt(),b.lastServiceReading(),b.currentReading(),text(b.ownerTeam(),100));
    }
    private String type(String filename,byte[] bytes){
        String lower=filename.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".pdf")&&new String(bytes,0,Math.min(bytes.length,5),StandardCharsets.US_ASCII).equals("%PDF-"))return "application/pdf";
        if(lower.endsWith(".png")&&bytes.length>8&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))return "image/png";
        if((lower.endsWith(".jpg")||lower.endsWith(".jpeg"))&&bytes.length>3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)return "image/jpeg";
        if(lower.endsWith(".txt")){
            try {String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();if(text.indexOf(0)>=0)throw new CharacterCodingException();return "text/plain";}
            catch(CharacterCodingException ignored){}
        }
        throw new ValidationException("仅支持内容与扩展名一致的PDF、PNG、JPEG或UTF-8 TXT；不支持可执行文件和HTML/SVG");
    }
    private String date(String value){String v=text(value,10);if(!v.isBlank())try{LocalDate.parse(v);}catch(DateTimeException ex){throw new ValidationException("投运日期格式应为YYYY-MM-DD");}return v;}
    private String text(String value,int max){String v=value==null?"":value.trim();if(v.length()>max)throw new ValidationException("字段过长，最多"+max+"字");return v;}
    private String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
}
