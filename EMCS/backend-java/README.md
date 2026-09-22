# EMCS Java 后端

JDK 17 + Spring Boot，连接 openGauss 6.0.5 和 Kvrocks 2.16.0。

```bash
mvn clean verify
mvn spring-boot:run
```

启动前配置数据库、缓存和 JWT 环境变量，模板见 `.env.example`；默认端口 18103。完整 SQL 导入、systemd、Nginx 和联调步骤见项目根目录 README。

云端模型配置：`config/agent.yml`。默认关闭，密钥通过环境变量提供。
