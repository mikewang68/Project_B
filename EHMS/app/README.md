# 应用构建产物

公开仓库不提交预编译 JAR。请使用 Java 17 和 Maven 3.9.x 从源码构建：

```bash
cd ../source/backend
mvn clean package
cp target/ehm-demo-0.1.0.jar ../../app/ehm-service.jar
```

构建完成后，再按照根目录的 `README-server-deployment.md` 部署。
