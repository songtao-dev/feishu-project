<div align="center">


# 📒 小简记 — 飞书生态个人效率平台

**基于飞书开放平台的全栈个人效率系统：记账 + 日记 + 笔记 + 待办 + AI 自然语言助手**

[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.10-brightgreen)](https://spring.io/projects/spring-boot)
[![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.5.6-blue)](https://baomidou.com/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.x-orange)](https://www.rabbitmq.com/)
[![Redis](https://img.shields.io/badge/Redis-7.x-red)](https://redis.io/)
[![飞书开放平台](https://img.shields.io/badge/飞书-开放平台-00D6B9)](https://open.feishu.cn/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](#许可证)

</div>

---

## 📖 项目简介

小简记是一个**深度集成飞书生态的个人效率平台**，覆盖记账、日记、笔记、待办四大核心场景，并通过 AI 大模型实现自然语言指令操作（"帮我删除第三条记录"）。系统将消费记录实时推送到飞书群并同步写入飞书多维表格，同时支持多人共享日记本、短信自动解析记账、收支统计分析等功能。

> 💡 **设计初衷**：将分散在多个 App 中的个人效率需求整合到一个平台，利用飞书生态实现"记录即同步"——记账消息自动推送到飞书群、数据自动写入多维表格，无需手动导出。

### 解决的核心问题

| 痛点                                             | 解决方案                                                     |
| ------------------------------------------------ | ------------------------------------------------------------ |
| 记账接口同步调用飞书 API，响应慢（2-4s）且易失败 | RabbitMQ 异步解耦 + 死信队列 + 幂等消费 + 兜底定时任务，接口 RT 降至 0.1s |
| 银行短信格式复杂，手动录入繁琐                   | 预编译正则 + AI 语义解析双模式，自动提取金额/商家/时间等 8 个字段 |
| 多用户数据隔离不严，存在越权风险                 | ThreadLocal 用户上下文 + 全链路 userId 过滤 + 操作前权限校验 |
| Redis 故障导致整个应用无法启动                   | RedisSafeTemplate 装饰器降级为纯 JWT 模式，可用性从 0% 提升到 100% |
| 浮点数计算金额有精度误差                         | BigDecimal 精确计算 + TreeMap 日期补全，统计图表零误差无断点 |
| AI 调用耗时 1-5s 阻塞请求线程                    | CompletableFuture 异步 + taskId 轮询，接口 RT 降至 5ms       |

---

## 🏗️ 系统架构

### 整体架构图

```
┌──────────────────────────────────────────────────────────────────┐
│                        客户端层                                    │
│   H5 页面 / 飞书群机器人 / SmsForwarder(短信转发) / 飞书多维表格    │
└──────────┬───────────────────┬───────────────────┬───────────────┘
           │                   │                   │
           ▼                   ▼                   ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│   Nginx 反向代理  │ │  飞书 Webhook    │ │  短信转发 API     │
└────────┬─────────┘ └────────┬─────────┘ └────────┬─────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌──────────────────────────────────────────────────────────────────┐
│                      Spring Boot 应用层                           │
│                                                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌───────────┐ │
│  │ 认证拦截器   │  │ Controller │  │  Service   │  │  Mapper   │ │
│  │ JWT+Redis  │  │  (13个)    │  │  (业务逻辑) │  │ (11个)    │ │
│  └────────────┘  └────────────┘  └────────────┘  └───────────┘ │
│                                                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐                │
│  │  MQ 消费者  │  │ 定时任务    │  │  AI 模块    │                │
│  │ (异步飞书)  │  │ (兜底/发布) │  │ (指令/解析) │                │
│  └────────────┘  └────────────┘  └────────────┘                │
└──────┬───────────────┬───────────────┬───────────────┬──────────┘
       │               │               │               │
       ▼               ▼               ▼               ▼
┌──────────┐   ┌──────────────┐  ┌──────────┐  ┌──────────────┐
│  MySQL   │   │  RabbitMQ    │  │  Redis   │  │  阿里云 OSS  │
│ (11张表) │   │ (死信队列)    │  │ (Token)  │  │ (图片/语音)  │
└──────────┘   └──────────────┘  └──────────┘  └──────────────┘
       │               │
       ▼               ▼
┌──────────────────────────────────────────────────────────────────┐
│                      飞书开放平台                                 │
│        自定义机器人(post富文本)  +  多维表格 CRUD API             │
└──────────────────────────────────────────────────────────────────┘
```

### 核心数据流：记账消息异步处理

```
用户发送短信/手动录入
        │
        ▼
┌───────────────────┐
│  Controller 接收   │  解析字段 → 存库(status=待处理) → 投MQ(recordId)
│  (同步, ~100ms)   │  立即返回 recordId
└─────────┬─────────┘
          │ convertAndSend
          ▼
┌───────────────────┐
│  RabbitMQ 主队列   │  msg.exchange → msg.queue
└─────────┬─────────┘
          │ @RabbitListener
          ▼
┌───────────────────┐     失败重试 3次(1s/2s/4s退避)
│  MessageConsumer  │──────────────────────────────┐
│  ①发飞书群消息     │                              │
│  ②写多维表格       │  重试耗尽                     │
│  ③更新状态         │                              │
└─────────┬─────────┘                              ▼
          │ 成功                          ┌───────────────┐
          ▼                               │  死信队列 DLQ  │
    status=成功                           │  (人工兜底)    │
                                          └───────────────┘

兜底：PendingMessageRetryJob 每60s扫描超时待处理记录，重新投MQ
```

---

## ✨ 核心特性

### 1. RabbitMQ 异步消息架构 + 三重保障消息零丢失

- Controller 只做「解析 + 存库 + 投 MQ」，接口 RT 从 2-4s 降至 **~0.1s**
- 主队列绑定死信交换机，Spring AMQP 自动重试 3 次（1s/2s/4s 指数退避）
- 幂等消费：`feishu_sent` / `bitable_sent` 状态字段跳过已成功步骤
- 兜底定时任务每 60s 扫描超时记录重新投递，MQ 宕机也不丢消息
- 消息体只传 `recordId`（Long），消费者查库取最新数据，避免消费过期快照

### 2. AI 大模型指令执行引擎

- 通过 Prompt Engineering 让通义千问返回结构化 JSON 指令（不依赖 Function Calling）
- 支持 `delete` / `update` / `query` 3 类操作 + `index` / `latest` / `merchant` / `id` 4 种定位方式
- 注入最近 20 条记录上下文，解决"第三条""刚才那条"等相对指代，准确率 > 95%
- `CompletableFuture.runAsync` 异步执行 + `ConcurrentHashMap` 存 taskId 结果，前端轮询
- 所有操作带 `userId` 过滤，AI 无法越权操作他人数据

### 3. JWT + Redis 双层认证 + Redis 降级策略

- JWT（JJWT 0.12.6，HMAC-SHA256）防篡改，Redis 存 token 支持登出失效
- `RedisSafeTemplate` 装饰器：`@Autowired(required=false)` + try/catch 降级
- Redis 不可用时自动降级为纯 JWT 模式，应用仍能启动和登录（可用性从 0% → 100%）
- `ThreadLocal` 用户上下文 + `afterCompletion` 清理，防止线程池复用导致 userId 串号

### 4. 飞书开放平台深度集成

- 自定义机器人 post 富文本消息推送（标题 + 多行 emoji 标签，PC/手机端美观）
- 多维表格 CRUD：写入记录后提取 `record_id` 存库，删除/更新时同步操作飞书
- `tenant_access_token` 内存缓存 + 提前 5 分钟刷新，命中率 > 99%
- 分类自动推断：根据商家名/渠道关键词匹配餐饮/交通/购物/住房/其他

### 5. 多人共享日记系统

- 私人/共享双模式：`groupId = NULL` 私人日记，`groupId != NULL` 组内可见
- 邀请码机制：组主生成唯一邀请码，用户凭码申请加入
- 三层权限：组内成员可查看 / 作者可改删自己的 / 组主可管理成员
- `@Scheduled(cron="0 0 * * * ?")` 每小时批量 UPDATE 发布草稿，50 篇从 1500ms 降至 30ms
- 软删除回收站：`deleted` 字段标记，支持恢复和彻底删除

### 6. 短信解析双模式

- 正则模式：8 个预编译 `Pattern` 常量解析银行短信固定格式，0 延时 0 成本
- AI 模式：通义千问语义解析任意自然语言（"买水果13块"），作为通用兜底
- `FieldHolder` 统一两种模式输出，`firstNonBlank()` 处理解析结果和前端字段优先级
- 金额清洗：去掉千分位逗号（`1,247.81` → `1247.81`），方向归一化映射

### 7. 全链路数据隔离

- `UserContext`（ThreadLocal\<Long\>）在拦截器绑定 userId，请求结束清理
- 所有 `LambdaQueryWrapper` 强制带 `.eq(MessageRecord::getUserId, userId)`
- 删除/更新前校验 `record.getUserId().equals(userId)`，不匹配返回"无权操作"
- AI 异步任务显式传 userId 参数（ThreadLocal 不跨线程传递）

### 8. OSS 媒体存储 + 精确统计分析

- 阿里云 OSS 按 `diary/{type}/{yyyy/MM/dd}/{uuid}.{ext}` 路径存储，按日期分目录 + UUID 防重名
- 统计模块用 `BigDecimal` 精确计算金额（`0.1 + 0.2 = 0.3` 无误差）
- `TreeMap` 日期补全：先填充所有日期为 0 再 merge 实际数据，图表连续无断点
- 支持周/月/自定义时间范围 + 环比对比（上期为 0 时特判避免除零）

---

## 🛠️ 技术栈

| 类别          | 技术                   | 版本   | 用途                              |
| ------------- | ---------------------- | ------ | --------------------------------- |
| **语言**      | Java                   | 17     | 后端开发                          |
| **框架**      | Spring Boot            | 3.2.10 | Web 应用框架                      |
| **ORM**       | MyBatis-Plus           | 3.5.6  | 数据访问层（Lambda 类型安全查询） |
| **数据库**    | MySQL                  | 8.0    | 关系型数据持久化（11 张表）       |
| **消息队列**  | RabbitMQ               | 3.x    | 异步解耦 + 死信队列 + 自动重试    |
| **缓存**      | Redis                  | 7.x    | JWT token 存储 + 登出失效         |
| **认证**      | JJWT                   | 0.12.6 | JWT 生成与解析（HMAC-SHA256）     |
| **密码加密**  | Spring Security Crypto | -      | BCrypt 密码哈希                   |
| **对象存储**  | 阿里云 OSS SDK         | 3.17.4 | 日记图片/语音存储                 |
| **AI 大模型** | 通义千问 qwen-turbo    | -      | 自然语言指令理解 + 语义解析       |
| **飞书集成**  | 飞书开放平台 API       | -      | 群机器人消息 + 多维表格 CRUD      |
| **工具库**    | Hutool                 | 5.8.31 | HTTP 客户端 + JSON 序列化         |
| **构建**      | Maven                  | 3.x    | 项目构建与依赖管理                |
| **部署**      | Nginx + systemd        | -      | 反向代理 + 进程托管               |

---

## 📁 项目结构

```
feishu-project/
├── src/main/java/com/code/feishu/
│   ├── FeishuSpringApplication.java      # 启动类 (@EnableScheduling)
│   ├── ai/                               # AI 模块（独立子包，可移植）
│   │   ├── client/AiClient.java          # 通义千问 HTTP 客户端
│   │   ├── config/AiConfig.java          # AI 配置
│   │   ├── dto/                          # AiCommandResult / AiCommandTask / AiParseResult
│   │   ├── prompt/PromptTemplates.java   # PARSE_SYSTEM / COMMAND_SYSTEM 提示词
│   │   └── service/                      # AiCommandService(指令执行) / AiParseService(语义解析)
│   ├── config/                           # 配置类
│   │   ├── RabbitMQConfig.java           # 队列/交换机/死信队列声明
│   │   ├── RedisSafeTemplate.java        # Redis 降级装饰器
│   │   ├── OssConfig.java                # 阿里云 OSS 配置
│   │   └── WebMvcConfig.java             # 拦截器注册 + CORS
│   ├── consumer/
│   │   └── MessageConsumer.java          # MQ 消费者（飞书发送 + 表格写入 + 幂等）
│   ├── context/
│   │   └── UserContext.java              # ThreadLocal 用户上下文
│   ├── controller/                       # 13 个 Controller
│   │   ├── MessageController.java        # 记账记录 CRUD + 发送 + 批量
│   │   ├── DiaryController.java          # 日记 CRUD + 回收站
│   │   ├── DiaryGroupController.java     # 日记组（共享/邀请码/成员管理）
│   │   ├── DiaryMediaController.java     # 日记媒体上传
│   │   ├── DiaryPublishConfigController.java # 定时发布配置
│   │   ├── NoteController.java           # 笔记管理
│   │   ├── NoteCategoryController.java   # 笔记分类
│   │   ├── TodoController.java           # 待办事项
│   │   ├── StatisticsController.java     # 收支统计 + 图表数据
│   │   ├── AiController.java             # AI 指令 + 语义解析
│   │   ├── UserController.java           # 注册/登录/登出/用户信息
│   │   └── DailyController.java          # 每日一句
│   ├── dto/                              # DiaryDTO / LoginDTO / MessageSendDTO
│   ├── entity/                           # 10 个实体类
│   │   ├── MessageRecord.java            # 记账记录（含 feishu_sent/bitable_sent 幂等字段）
│   │   ├── Diary.java / DiaryGroup.java  # 日记 + 日记组
│   │   ├── DiaryGroupMember.java         # 日记组成员
│   │   ├── DiaryMedia.java               # 日记媒体
│   │   ├── DiaryPublishConfig.java       # 定时发布配置
│   │   ├── Note.java / NoteCategory.java # 笔记 + 分类
│   │   ├── Todo.java                     # 待办
│   │   └── User.java                     # 用户
│   ├── interceptor/
│   │   └── AuthInterceptor.java          # JWT + Redis 认证拦截（含降级逻辑）
│   ├── job/                              # 定时任务
│   │   ├── PendingMessageRetryJob.java   # 每60s兜底重投超时消息
│   │   └── DiaryAutoPublishJob.java      # 每小时批量发布草稿日记
│   ├── mapper/                           # 11 个 Mapper（BaseMapper）
│   ├── service/                          # 业务服务
│   │   ├── FeishuBotService.java         # 飞书群机器人 post 富文本消息
│   │   ├── FeishuBitableService.java     # 多维表格 CRUD + token 缓存
│   │   ├── MessageParserService.java     # 短信解析（正则+AI双模式）
│   │   ├── OssService.java               # OSS 上传（按日期分目录+UUID）
│   │   └── UserService.java              # 用户注册登录（BCrypt+JWT）
│   ├── util/
│   │   └── JwtUtil.java                  # JWT 生成/解析/验签
│   ├── vo/                               # LoginVO / MessageParseVO / UserInfoVO
│   └── webhook/                          # 飞书事件回调处理
├── src/main/resources/
│   ├── application.properties            # 开发环境配置
│   ├── application-template.properties   # 配置模板（占位符，提交git）
│   └── mapper/                           # MyBatis XML
├── sql/                                  # 数据库版本管理（增量脚本）
│   ├── init.sql                          # v1 记账记录表
│   ├── v2_user.sql                       # v2 用户表
│   ├── v3_diary.sql                      # v3 日记表
│   ├── v4_diary_group.sql                # v4 日记组
│   ├── v4_diary_publish_config.sql       # v4 发布配置
│   ├── v5_diary_status.sql               # v5 日记状态
│   ├── v5_todo.sql                       # v5 待办
│   ├── v6_fill_category.sql              # v6 分类填充
│   ├── v7_soft_delete.sql                # v7 软删除
│   ├── v8_diary_media.sql                # v8 日记媒体
│   └── v9_note.sql                       # v9 笔记
├── pom.xml
├── build.bat                             # Windows 构建脚本
└── README.md
```

---

## 🚀 快速开始

### 环境要求

- **JDK** 17+
- **Maven** 3.6+
- **MySQL** 8.0+
- **RabbitMQ** 3.x+
- **Redis** 6.x+（可选，缺失时自动降级为纯 JWT 模式）
- **飞书自建应用**（App ID + App Secret，用于多维表格 API）
- **飞书自定义机器人**（Webhook URL，用于群消息推送）
- **通义千问 API Key**（用于 AI 指令和语义解析）
- **阿里云 OSS**（Bucket + AccessKey，用于媒体存储，可选）

### 1. 数据库初始化

```bash
# 按版本顺序执行增量脚本
mysql -u root -p < sql/init.sql
mysql -u root -p < sql/v2_user.sql
mysql -u root -p < sql/v3_diary.sql
# ... 依次执行到 v9_note.sql
```

### 2. 配置文件

```bash
# 复制模板并填写真实配置
cp src/main/resources/application-template.properties \
   src/main/resources/application-prod.properties

# 需要配置的关键项：
# spring.datasource.url/username/password   数据库连接
# spring.rabbitmq.host/port/username/password  RabbitMQ
# spring.data.redis.host/port/password      Redis（可选）
# feishu.app-id / feishu.app-secret         飞书自建应用
# feishu.bot-webhook                        群机器人 Webhook
# ai.api-key / ai.base-url / ai.model       通义千问
# aliyun.oss.endpoint/bucket/access-key-id  阿里云 OSS（可选）
# jwt.secret / jwt.expiration               JWT 密钥和过期时间
```

### 3. 启动中间件

```bash
# Docker 一键启动 RabbitMQ + Redis
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
docker run -d --name redis -p 6379:6379 redis:7
```

### 4. 启动应用

```bash
# 开发环境
mvn spring-boot:run

# 生产环境（打包后运行）
mvn clean package -DskipTests
java -jar target/feishu-project-1.0-SNAPSHOT.jar --spring.profiles.active=prod

# Windows 构建
build.bat
```

### 5. 验证

- 应用启动后访问 `http://localhost:8080`
- 注册账号 → 登录 → 记账/写日记/体验 AI 指令
- 发送一条记账记录，检查飞书群是否收到消息、多维表格是否新增行

---

## ⚙️ 核心配置说明

| 配置项                                    | 说明                           | 默认值          |
| ----------------------------------------- | ------------------------------ | --------------- |
| `server.port`                             | 服务端口                       | 8080            |
| `spring.datasource.*`                     | MySQL 连接                     | -               |
| `spring.rabbitmq.*`                       | RabbitMQ 连接                  | localhost:5672  |
| `spring.data.redis.*`                     | Redis 连接（可选）             | localhost:6379  |
| `spring.rabbitmq.listener.simple.retry.*` | 重试：3次 / 1s初始 / 2.0倍退避 | -               |
| `feishu.app-id`                           | 飞书自建应用 App ID            | -               |
| `feishu.app-secret`                       | 飞书自建应用 App Secret        | -               |
| `feishu.bot-webhook`                      | 群机器人 Webhook URL           | -               |
| `feishu.bitable.app-token`                | 多维表格 App Token             | -               |
| `feishu.bitable.table-id`                 | 多维表格 Table ID              | -               |
| `ai.api-key`                              | 通义千问 API Key               | -               |
| `ai.model`                                | AI 模型名称                    | qwen-turbo      |
| `aliyun.oss.*`                            | 阿里云 OSS 配置（可选）        | -               |
| `jwt.secret`                              | JWT 签名密钥（至少32字节）     | -               |
| `jwt.expiration`                          | Token 过期时间（毫秒）         | 604800000 (7天) |

---

## 📝 使用方式

### 1. 记账

- **短信自动记账**：通过 SmsForwarder 将银行消费短信转发到 `/api/send` 接口，系统自动解析 8 个字段（银行/卡号/时间/金额/余额/方向/渠道/商家），异步推送飞书群 + 写入多维表格
- **手动记账**：在页面填写金额、商家、分类等字段提交
- **AI 语音/文字记账**：输入"买水果花了13块"，AI 语义解析自动提取字段
- **批量导入**：支持一次提交多条记录，`Db.saveBatch` 批量插入

### 2. AI 自然语言操作

在对话界面输入自然语言指令：

- "帮我删除第三条记录" → AI 理解为 `delete + index=3`
- "把刚才那条金额改成50" → AI 理解为 `update + latest + fields.amount=50`
- "查一下这个月餐饮花了多少" → AI 理解为 `query` + 统计
- AI 返回结构化 JSON，后端校验权限后执行，结果反馈给用户确认

### 3. 日记

- **私人日记**：仅本人可见，支持图文混排
- **共享日记**：创建日记组 → 生成邀请码 → 好友凭码加入 → 组内成员共写
- **定时发布**：配置每天定点自动发布草稿（如凌晨 1 点）
- **媒体上传**：支持图片/语音，存储到阿里云 OSS
- **回收站**：软删除，支持恢复和彻底删除

### 4. 统计分析

- 按日/周/月/自定义范围查看收支趋势
- 分类占比饼图、每日趋势折线图
- 环比对比（与上一周期比较变化百分比）
- 所有金额用 BigDecimal 精确计算，日期自动补零保证图表连续

### 5. 飞书同步

- 每条记账记录自动推送飞书群（post 富文本格式，带 emoji 标签）
- 数据自动写入飞书多维表格，可在飞书内做透视表、图表、仪表盘
- 删除/更新记录时同步操作飞书表格（通过 `bitable_record_id` 关联）

### 📊 性能优化记录

| 优化项                                | 优化前                  | 优化后                    | 提升     |
| ------------------------------------- | ----------------------- | ------------------------- | -------- |
| 记账接口 RT（同步→MQ异步）            | 2000-4000ms             | 60-100ms                  | 40x      |
| AI 指令接口（同步→CompletableFuture） | 1000-5000ms             | ~5ms                      | 200x+    |
| tenant_access_token 获取              | 200-500ms/次            | ~0ms（缓存命中>99%）      | 质变     |
| 1万条数据按条件查询                   | 200ms（全表扫描）       | <5ms（索引命中）          | 40x      |
| 50条记录批量插入                      | 2500ms（50次SQL）       | 100ms（1次SQL）           | 25x      |
| 50篇草稿定时发布                      | 1500ms（50次UPDATE）    | 30ms（1次批量UPDATE）     | 50x      |
| Redis 故障时应用可用性                | 无法启动（0%）          | 正常启动+降级运行（100%） | 质变     |
| 金额计算精度                          | 浮点误差（0.1+0.2≠0.3） | BigDecimal 精确           | 质变     |
| OSS 文件重名覆盖                      | 有风险                  | UUID 命名 + 按日期分目录  | 0 冲突   |
| 飞书消息重复发送                      | 重试时重复发 N 次       | 幂等字段只发 1 次         | 消除重复 |

## 🗄️ 数据库设计

### 核心表概览

| 表名                     | 说明       | 关键字段                                                     |
| ------------------------ | ---------- | ------------------------------------------------------------ |
| `t_message_record`       | 记账记录   | user_id, amount(BigDecimal), direction, merchant, happen_time, status, feishu_sent, bitable_sent, bitable_record_id, deleted |
| `t_user`                 | 用户       | username, password(BCrypt), nickname, created_at             |
| `t_diary`                | 日记       | user_id, group_id, title, content, status(draft/published), deleted |
| `t_diary_group`          | 日记组     | name, owner_id, invite_code, created_at                      |
| `t_diary_group_member`   | 日记组成员 | group_id, user_id, role(owner/member), join_time             |
| `t_diary_media`          | 日记媒体   | diary_id, type(image/audio), url(OSS), created_at            |
| `t_diary_publish_config` | 发布配置   | user_id, publish_hour, enabled                               |
| `t_note`                 | 笔记       | user_id, category_id, title, content                         |
| `t_note_category`        | 笔记分类   | user_id, name, sort                                          |
| `t_todo`                 | 待办       | user_id, content, completed, sort                            |

### 索引设计

`t_message_record` 表建 4 个索引覆盖核心查询：

- `idx_create_time` — 兜底任务按时间扫描超时记录
- `idx_happen_time` — 统计按交易时间范围查询
- `idx_direction` — 统计按收支筛选
- `idx_status` — 兜底任务按状态查待处理记录

---

## 🔧 工程实践

### 1. 数据库版本管理

`sql/` 目录按版本号命名增量脚本（init → v2 → ... → v9），每个脚本只做增量变更不 DROP 旧表。线上升级按版本顺序执行，新环境从 init 开始跑全部脚本即可建库，0 数据丢失。

### 2. 配置外部化

分三个配置文件：`application-template.properties`（模板，提交 git，占位符）、`application-prod.properties`（生产，不提交 git）、`application.properties`（开发本地用）。敏感信息用环境变量占位，密钥不进 git。

### 3. 软删除设计

记账记录和日记均采用 `deleted` 字段软删除，查询带 `.eq(deleted, 0)` 过滤，回收站接口查 `deleted=1`，恢复接口设回 0。误删可恢复，数据不丢失。

### 4. 分层架构

严格分层：controller → service → mapper → entity，外加 dto/vo（传输对象）、config（配置）、interceptor（拦截器）、context（上下文）、consumer（MQ消费）、job（定时任务）、ai（AI 独立子包）。AI 模块独立可移植。

### 5. 全链路数据隔离

ThreadLocal 用户上下文 + 所有查询强制 userId 过滤 + 操作前权限校验，多用户数据 0 越权事故。异步任务显式传 userId 参数，不依赖 ThreadLocal 跨线程。

