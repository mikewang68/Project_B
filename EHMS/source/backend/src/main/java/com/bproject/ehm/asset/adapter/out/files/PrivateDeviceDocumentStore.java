package com.bproject.ehm.asset.adapter.out.files;

import com.bproject.ehm.asset.ports.DeviceDocumentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;

/** Private blob adapter. Can be replaced by the shared file service without changing business APIs. */
@Component @Profile("server")
public class PrivateDeviceDocumentStore implements DeviceDocumentStore {
    private final Path root;
    public PrivateDeviceDocumentStore(@Value("${ehm.documents.root:${user.dir}/private-device-documents}") String root) {
        this.root=Path.of(root).toAbsolutePath().normalize();
    }
    private Path path(String id) {
        if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("无效资料编号");
        Path p=root.resolve(id+".blob");
        if(!p.getParent().equals(root)||Files.isSymbolicLink(root)||Files.isSymbolicLink(p))throw new IllegalArgumentException("资料存储路径不合法");
        return p;
    }
    public void put(String id,byte[] bytes) {
        try {
            Path p=path(id);Files.createDirectories(root);
            if(!root.toRealPath().equals(root))throw new IOException("Noncanonical document root");
            boolean posix=Files.getFileStore(root).supportsFileAttributeView("posix");
            if(posix)Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
            if(posix)Files.createFile(p,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            else Files.createFile(p);
            Files.write(p,bytes,StandardOpenOption.WRITE);
        } catch(IOException ex){throw new IllegalStateException("资料保存失败，请联系管理员核对受保护存储",ex);}
    }
    public byte[] get(String id) {
        try {Path p=path(id);if(!root.toRealPath().equals(root)||Files.size(p)>5*1024*1024)throw new IOException("Invalid file");return Files.readAllBytes(p);}
        catch(IOException ex){throw new IllegalStateException("资料文件暂不可用，请核对备份或存储状态",ex);}
    }
}
