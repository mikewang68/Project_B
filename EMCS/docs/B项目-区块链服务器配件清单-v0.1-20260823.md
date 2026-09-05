# B项目 - 区块链服务器配件清单

> 版本：v0.1  
> 调研日期：2026-08-23  
> 数量：3 台物理服务器  
> 定位：联盟链/可信存证/审计证据链节点  
> 配置原则：三台同构、每台一个独立物理故障域、海光 x86 路线、信创合规材料随合同交付

## 一、结论

建议新增 3 台同构的 **H3C UniServer R4930 G7** 2U 机架服务器，采用海光 C86-4G 处理器、银河麒麟高级服务器操作系统 V11、256GB ECC 内存、企业级 SSD 分层和双 10GbE 网络。三台分别作为 `BC-01`、`BC-02`、`BC-03` 联盟链节点，不与现有 4 台私有云物理服务器混部。

这套配置面向 B 项目中的可信存证、审计证据链和关键业务摘要上链，不把视频、时序原始数据、业务附件或主业务数据库直接写入链。大对象仍放现有对象存储/数据库，链上只保存摘要、索引、签名、时间和审计状态。

当前只能表述为“满足信创采购方向的推荐配置”。最终合规结论必须以投标承诺、整机出厂配置单、CPU 与测评产品对应说明、操作系统许可及兼容认证、关键部件料号和履约验收材料为准。

## 二、单台固定配件清单

三台配置完全相同；下表数量均为单台数量。

| 类别 | 推荐配置 | 数量 | 参考链接 | 采购与验收要求 |
| --- | --- | ---: | --- | --- |
| 整机 | H3C UniServer R4930 G7，2U 机架式，支持海光 C86-4G、DDR5 ECC、PCIe 5.0、U.2 NVMe/SAS/SATA 热插拔盘、独立带外管理 | 1 | [H3C 原厂产品规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[R4930 G7 文档中心](https://www.h3c.com/cn/Service/Document_Software/Document_Center/Server/Catalog/Rack_server/R4930_G7/default.htm?category=1035079&subcategory=1035146) | 固化机箱背板、硬盘笼、转接卡、风扇和固件版本；不得用未书面确认的“同等型号”替换 |
| CPU | 海光 C86-4G 5440，双路配置；整机总物理核心数不低于 32 核 | 2 | [H3C 海光 C86-4G 平台规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[C86-4G 安全可靠测评公告](https://www.itsec.gov.cn/aqkkcp/cpgg/202405/t20240520_172866.html) | 供应商须给出 5440 与安全可靠测评产品“海光处理器 C86-4G”的对应说明；CPU 型号、核心数、主频、TDP 写入出厂配置单 |
| 内存 | 32GB DDR5 ECC RDIMM，组成 256GB | 8 | [H3C DDR5 ECC 插槽及容量规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[原厂配置咨询入口](https://www.h3c.com/cn/BizPortal/OnlineSurvey/OnLineFeedBackForBuyPage.aspx) | 两颗 CPU 对称插条；具体通道位置按 H3C 内存配置规则；同品牌、同料号、同频率、同 Rank |
| 系统盘 | 960GB 企业级 SSD，SAS/SATA 接口，支持掉电保护，耐写量不低于 1 DWPD | 2 | [H3C R4930 G7 存储规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[原厂配置咨询入口](https://www.h3c.com/cn/BizPortal/OnlineSurvey/OnLineFeedBackForBuyPage.aspx) | RAID1，理论可用约 960GB；公开规格只证明平台支持相应盘型，SSD 品牌、料号、PLP、DWPD、质保和固件必须在报价单中固化 |
| 账本/状态数据库盘 | 3.84TB 企业级 SAS SSD，支持掉电保护，耐写量不低于 1 DWPD | 5 | [H3C SAS/SATA/U.2 盘位规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[原厂配置咨询入口](https://www.h3c.com/cn/BizPortal/OnlineSurvey/OnLineFeedBackForBuyPage.aspx) | 4 盘组成 RAID10，理论可用约 7.68TB；第 5 盘作为全局热备；禁止消费级 SSD，具体盘料号须另行核价 |
| RAID 控制器 | H3C 兼容智能阵列控制器，缓存不低于 4GB，带超级电容/掉电缓存保护 | 1 | [H3C RAID、缓存及掉电保护规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[R4930 G7 技术文档](https://www.h3c.com/cn/Service/Document_Software/Document_Center/Server/Catalog/Rack_server/R4930_G7/default.htm?category=1035079&subcategory=1035146) | 支持 RAID 0/1/5/6/10/50/60；验收时检查控制器料号、缓存容量、超级电容状态、热备接管和重建告警 |
| 业务网卡 | 双口 10GbE SFP+ OCP 3.0 网卡 | 1 | [H3C OCP 3.0/网络适配器规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[H3C 服务器网卡说明](https://www.h3c.com/cn/d_202502/2354652_30005_0.htm) | 两端口跨交换机 Bond；P2P、RPC/管理流量逻辑隔离；网卡芯片、部件编码、驱动版本须与麒麟 V11 兼容 |
| 万兆光模块 | 与现网交换机兼容的 10G SFP+ 光模块 | 2 | [H3C 10G 系列光模块](https://www.h3c.com/cn/Products_And_Solution/InterConnect/Products/Switches/Products/ZHBX/GMK/GMK/10G/)；[H3C 光模块手册](https://zhiliao.h3c.com/EnclosureTechDoc/ziliao/17296716896279.pdf) | 到货前确认 SR/LR、波长、光纤类型、距离和交换机兼容矩阵；同时配 2 根匹配跳纤 |
| 万兆光纤跳线 | 与所选光模块匹配的 LC-LC 多模或单模双芯跳纤 | 2 | [H3C 10G 光模块接口与距离说明](https://www.h3c.com/cn/Products_And_Solution/InterConnect/Products/Switches/Products/ZHBX/GMK/GMK/10G/) | SR 通常选 OM3/OM4 多模，LR 通常选 OS2 单模；长度按机柜和走线架实测，不能在 SR/LR 未确定前锁定纤型 |
| 带外管理 | 独立 HDM 管理口 | 1 | [H3C HDM/FIST 管理能力及独立管理口](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[R4930 G7 文档中心](https://www.h3c.com/cn/Service/Document_Software/Document_Center/Server/Catalog/Rack_server/R4930_G7/default.htm?category=1035079&subcategory=1035146) | 管理网与业务网隔离；支持远程控制、传感器、告警、日志和固件升级 |
| 可信模块 | TPCM 3.0（或项目密码应用方案认可的 TPM2.0/TCM 模块） | 1 | [H3C 可信平台模块规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[原厂配置咨询入口](https://www.h3c.com/cn/BizPortal/OnlineSurvey/OnLineFeedBackForBuyPage.aspx) | 型号必须与整机兼容；用于可信启动、密钥保护和平台度量，不能只写“支持可选” |
| 电源 | 1300W 及以上原厂白金级热插拔电源 | 2 | [H3C 1300W/1600W/2000W/2700W/3200W 冗余电源规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/) | 1+1 冗余，分别接入两路 PDU；供应商按双 CPU、满盘配置核算功率，并书面确认约 3650m 海拔下的功率和散热降额 |
| 散热 | 原厂热插拔冗余高性能风扇组 | 1 套 | [H3C 风扇及工作温度规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[R4930 G7 运行与安装文档入口](https://www.h3c.com/cn/Service/Document_Software/Document_Center/Server/Catalog/Rack_server/R4930_G7/default.htm?category=1035079&subcategory=1035146) | 按双 CPU、满盘和高海拔配置，不接受缺风扇占位的降配交付 |
| 导轨及理线 | 与 800mm 深机箱匹配的原厂滑轨、理线架、电源线和标签 | 1 套 | [H3C 机箱尺寸及原厂规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[R4930 G7 安装文档入口](https://www.h3c.com/cn/Service/Document_Software/Document_Center/Server/Catalog/Rack_server/R4930_G7/default.htm?category=1035079&subcategory=1035146) | 电源插头制式、PDU 接口、机柜立柱间距和可用深度现场确认 |
| 操作系统 | 银河麒麟高级服务器操作系统 V11，x86_64，服务器版 | 1 套 | [麒麟软件 V11 官方产品页](https://www.kylinos.cn/productPc/server/serverMainV11/)；[V11 版本发布说明](https://www.kylinos.cn/issueNote/serverNote/2066705970141257729.html?activeId=2066705970141257729)；[安全可靠测评公告](https://www.itsec.gov.cn/aqkkcp/cpgg/202509/t20250912_235645.html) | 固化 V11 具体发行批次、内核、架构、许可方式和服务期；完成 R4930 G7、RAID、网卡、TPCM 及区块链软件兼容验证 |
| 原厂服务 | 整机 5 年原厂维保，硬盘不返还，7×24 报修，下一工作日或更高等级上门 | 1 套 | [H3C 技术维护服务入口](https://www.h3c.com/cn/home/right_float/contact_us/)；[H3C 保修权益查询](https://es.h3c.com/entitlement/)；[原厂服务询价入口](https://www.h3c.com/cn/BizPortal/OnlineSurvey/OnLineFeedBackForBuyPage.aspx) | 公开入口不证明报价已含五年服务；合同须覆盖 CPU、内存、SSD、RAID、网卡、光模块、电源和高原现场，并明确硬盘不返还及备件到场时限 |

## 三、三台汇总数量

| 配件 | 三台合计 | 参考链接 |
| --- | ---: | --- |
| H3C R4930 G7 2U 整机 | 3 台 | [原厂规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/) |
| 海光 C86-4G 5440 CPU | 6 颗 | [平台规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[测评公告](https://www.itsec.gov.cn/aqkkcp/cpgg/202405/t20240520_172866.html) |
| 32GB DDR5 ECC RDIMM | 24 条（768GB） | [原厂内存规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/) |
| 960GB 企业级 SSD | 6 块 | [原厂存储规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[配置咨询](https://www.h3c.com/cn/BizPortal/OnlineSurvey/OnLineFeedBackForBuyPage.aspx) |
| 3.84TB 企业级 SAS SSD | 15 块 | [原厂存储规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[配置咨询](https://www.h3c.com/cn/BizPortal/OnlineSurvey/OnLineFeedBackForBuyPage.aspx) |
| 带缓存和超级电容的 RAID 控制器 | 3 张 | [原厂 RAID 规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/) |
| 双口 10GbE SFP+ OCP 3.0 网卡 | 3 张 | [原厂网络规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)；[网卡说明](https://www.h3c.com/cn/d_202502/2354652_30005_0.htm) |
| 10G SFP+ 光模块 | 6 只 | [H3C 10G 光模块](https://www.h3c.com/cn/Products_And_Solution/InterConnect/Products/Switches/Products/ZHBX/GMK/GMK/10G/) |
| 万兆光纤跳线 | 6 根 | [接口、波长与距离说明](https://www.h3c.com/cn/Products_And_Solution/InterConnect/Products/Switches/Products/ZHBX/GMK/GMK/10G/) |
| TPCM 3.0/项目认可可信模块 | 3 个 | [原厂可信模块规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/) |
| 1300W 及以上冗余电源 | 6 个 | [原厂电源规格](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/) |
| 银河麒麟高级服务器操作系统 V11 | 3 套 | [产品页](https://www.kylinos.cn/productPc/server/serverMainV11/)；[测评公告](https://www.itsec.gov.cn/aqkkcp/cpgg/202509/t20250912_235645.html) |
| 5 年原厂维保 | 3 套 | [H3C 服务入口](https://www.h3c.com/cn/home/right_float/contact_us/)；[保修权益查询](https://es.h3c.com/entitlement/) |

建议另购集群级冷备件：1 块 960GB 同料号系统 SSD、1 块 3.84TB 同料号数据 SSD、1 只电源模块和 2 只 10G 光模块。冷备件不替代每台服务器内的数据盘热备。

## 四、节点分工与部署口径

| 节点 | 建议角色 | 数据与网络要求 |
| --- | --- | --- |
| BC-01 | 联盟链共识/记账节点 1、主接入端点之一 | 独立机架 U 位、独立管理地址、P2P 与 RPC 分网段 |
| BC-02 | 联盟链共识/记账节点 2、主接入端点之一 | 与 BC-01 跨交换机、跨 PDU；不得放在同一虚拟化宿主机 |
| BC-03 | 联盟链共识/记账节点 3、灾备查询端点 | 同步完整账本；备份任务错峰运行，不能把链副本等同于备份 |

建议软件基线为长安链 ChainMaker 或 FISCO BCOS 的当前受支持稳定版，启用国密证书/算法、节点双向认证、最小权限、管理接口隔离和审计日志。最终选型必须先在银河麒麟 V11 + 海光 C86-4G 实机上完成安装、国密、合约、SDK、备份恢复和滚动升级 PoC。

## 五、三节点共识的重要边界

三台服务器可以组成三节点 Raft/CFT 集群，在一个节点宕机时维持多数派；但三台服务器不能提供“容忍一个恶意或任意错误节点”的标准 BFT 能力。PBFT/TBFT 的典型容错关系是 `n >= 3f + 1`，若要容忍 `f = 1` 个拜占庭节点，至少需要 4 个独立共识节点，且应落在 4 个独立物理故障域。

因此采购决策应二选一：

1. 本期严格只买 3 台：采用 Raft/CFT，验收口径写“容忍 1 台宕机”，不要写“容忍 1 个拜占庭节点”。
2. 项目必须使用 PBFT/TBFT 且容忍 1 个异常/作恶节点：新增第 4 台同构服务器；在三台物理机上硬塞 4 个节点不能形成 4 个独立物理故障域，不建议作为生产验收方案。

## 六、容量与性能验收

- 单台系统盘：`2 × 0.96TB RAID1`，理论可用约 0.96TB。
- 单台账本盘：`4 × 3.84TB RAID10`，理论可用约 7.68TB，另有 1 块 3.84TB 热备。
- 三台账本盘物理可用容量合计约 23.04TB，但因每台保存完整副本，业务逻辑容量仍按单节点约 7.68TB 计算，不能按三台相加。
- 区块链副本不是备份。必须向现有异地备份系统输出加密备份、配置、证书公钥、创世块和恢复手册；节点私钥按密码应用方案单独保护。
- 验收至少覆盖：持续写入、峰值并发、国密签名验签、节点重启、单节点断网、RAID 降级/重建、热备接管、账本校验、备份恢复和滚动升级。
- 性能目标必须由业务方补齐：日上链笔数、单笔大小、峰值 TPS、账本保存年限、查询并发和合约复杂度。在这些参数确认前，7.68TB 是保守工程配置，不是最终容量证明。

## 七、信创合规采购条款

1. CPU 使用安全可靠测评有效期内的海光 C86-4G 产品，供应商提交具体 CPU 料号与公告产品的对应说明。
2. 操作系统固定为安全可靠测评有效期内的银河麒麟高级服务器操作系统 V11，不接受只写“麒麟系统”。
3. 整机厂商提交服务器与操作系统、RAID、SSD、网卡、可信模块的兼容性材料；区块链厂商提交海光 x86_64 + 麒麟 V11 适配或实机 PoC 报告。
4. 合同附件固化所有关键部件品牌、型号、料号、固件、容量、耐写量和服务编号；更换部件须经书面批准并重新验证兼容性。
5. 如果采购主体属于相关党政机关或为机关提供支持保障的事业单位，将《通用服务器政府采购需求标准（2023年版）》带“*”指标以及 CPU、操作系统安全可靠测评要求写入实质性采购条款和履约验收。
6. “国产品牌”“海光 CPU”“支持麒麟”均不能单独证明整机已满足全部信创要求；合规结论以采购文件、供应商承诺、出厂配置和到货验收证据链为准。

## 八、预算口径

建议按 **15万～20万元/台、三台 45万～60万元** 预留硬件、操作系统和五年原厂服务预算。该数字是立项级区间，不是供应商报价；企业级 SSD、硬盘不返还服务、高原上门、光模块和操作系统服务年限会显著影响成交价。

正式询价时要求供应商按本 BOM 同页报价，并分别列出未税/含税价、运输、高原安装、五年维保、操作系统许可和可选密码卡价格，禁止将不同页面的零部件价格拼成“精确整机价”。

## 九、依据与证据边界

- B 项目知识库：当前资料将总体平台定位为“云 + 边 + 端”、数据中台和数字孪生架构；未发现已确认的区块链服务器容量、TPS 或软件选型基线。因此本清单把区块链限定为可信存证/审计证据链，并保留业务参数待确认。
- 参考清单 `B项目-云服务器设备清单-v0.4-20260822.md`：明确原 4 台私有云服务器不含区块链服务器，并采用海光 x86 统一架构。本清单延续 x86 路线，但采用更新的海光四号平台。
- [H3C R4930 G7 官方产品页](https://www.h3c.com/cn/Products_And_Solution/Server/H3C/Products/RackServer/Products_Series/Dualway_Server/R4930_G7/)：确认支持海光 C86-4G、DDR5 ECC、PCIe 5.0、U.2 NVMe、智能 RAID、可信模块、冗余电源和国产操作系统。
- [安全可靠测评结果公告（2024年第1号）](https://www.itsec.gov.cn/aqkkcp/cpgg/202405/t20240520_172866.html)：海光处理器 C86-4G 为 II 级，自 2024-05-20 起有效三年。
- [安全可靠测评结果公告（2025年第3号）](https://www.itsec.gov.cn/aqkkcp/cpgg/202509/t20250912_235645.html)：银河麒麟高级服务器操作系统 V11（Linux Kernel 6.6）为 I 级，自 2025-09-12 起有效三年。
- [财政部、工业和信息化部《通用服务器政府采购需求标准（2023年版）》通知](https://www.mof.gov.cn/jrttts/202312/t20231226_3924137.htm)及[标准 PDF](https://gks.mof.gov.cn/guizhangzhidu/202312/P020231228403815920528.pdf)：规定适用采购人的“*”指标及 CPU、操作系统安全可靠测评要求，并强调履约验收。
- [ChainMaker 官方文档测试配置说明](https://docs.chainmaker.org.cn/v2.3.0_alpha/html/quickstart/FAQ.html)：其公开测试环境采用 32 核 CPU、64GB 内存、SSD、万兆网络和 TBFT/国密/1KB 存证数据。本清单提高到 256GB 和冗余企业级 SSD，是面向生产、容器、监控和增长余量的工程配置，并非由该测试直接推出。
- [ChainMaker TBFT 文档](https://docs.chainmaker.org.cn/v3.0.0/html/manage/TBFT%E5%BC%80%E6%BA%90%E5%BC%95%E6%93%8E.html)与 [FISCO BCOS PBFT 文档](https://fisco-bcos-documentation.readthedocs.io/zh-cn/stable/docs/design/consensus/pbft.html)：共同说明典型 BFT 共识的 `3f+1` 容错关系，是本清单提出“BFT 至少 4 个独立节点”的依据。

> 日期说明：参考文档正文写有“2026-08-24 价格核验”，晚于本次调研日期 2026-08-23 一天。本清单没有把该未来日期作为已完成的价格核验事实，只引用其架构范围和配置思路。
