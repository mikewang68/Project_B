# 可选虚拟样例 API（1.8.1）

仅server profile提供。`EHM_SAMPLE_DATA_ENABLED=false`为默认值。所有读请求需要已登录；写请求还需超级管理员、现有CSRF与Origin校验。开启样例不改变其他业务权限。

| 方法 | 路径（前缀 /api/ehm/v1） | 说明 |
|---|---|---|
| GET | /system/sample-data | 返回开关、样例包版本、是否导入、存储/时序适配器，不返回凭据 |
| POST | /system/sample-data/import | 固定body `{"bundle":"industrial-sample-v1"}`，仅插入缺失样例并保留已有修改，返回分类型新增数量 |
| POST | /system/sample-data/tick | 同上body，生成一轮SAMPLE-虚拟测点值，保留离线设备场景，返回数量和明确的虚拟来源说明 |

SAMPLE-命名空间预留给演示，禁止真实设备使用。没有删除/清空/重置API，不写账号和链上回执。首次导入与审计在同一业务事务；并发冲突失败后需重试，不能换ID生成重复包。

`sample`时序适配器必须与样例开关同时显式启用，只用于隔离演示；不同于正式openGemini，也不适合存储现场高频波形。业务库需先有既有aggregate_store，未新增独立SQL迁移。不要把本机H2文件或样例登录账号作为生产配置上传。
