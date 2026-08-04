# 多用户认证与数据隔离 实施计划

## 摘要

为飞书记账项目添加用户系统，实现多用户数据隔离。采用 **JWT + Redis** 方案：
- 用户登录后颁发 JWT（7 天有效），同时存入 Redis（支持服务端失效/登出）
- 请求通过 `Authorization: Bearer <token>` 头携带 token，拦截器校验后把 `userId` 放入 `ThreadLocal`
- 所有数据库查询/插入按 `userId` 过滤，实现用户间数据隔离
- `/api/sms` 接口（SmsForwarder 调用，无法登录）改用 **sms_key**（URL 参数 `?token=<sms_key>`）定位归属用户
- `sortNum`（AI 指令定位用编号）改为**按用户独立递增**
- **注册功能关闭**，账号由管理员在数据库手动创建
- 前端增加登录页，token 存 localStorage，所有 fetch 自动带 Authorization 头

## 决策清单（已确认）

| 决策点 | 结论 |
|--------|------|
| 注册策略 | **关闭注册**，`/api/register` 不实现或返回 403；账号手动 INSERT |
| Token 有效期 | **7 天** |
| Token 存储 | Redis，key=`auth:token:<jwt>`，value=userId，TTL=7 天 |
| 密码加密 | BCrypt（用 `spring-security-crypto`，不引入完整 Spring Security） |
| SMS 归属 | 用户表 `sms_key` 字段，SmsForwarder webhook URL 带 `?token=<sms_key>` |
| sortNum | **按用户独立递增**（`MAX(sort_num) WHERE user_id=?` + 1） |
| 前端 token | localStorage，401 时清空并跳登录页 |

## 当前状态分析

### 已有基础
- `User.java` 实体已存在（id, username, password, nickname, createTime），但**缺 `smsKey` 字段**
- `MessageRecord.java` 实体存在，但**缺 `userId` 字段**
- 前端 `index.html` 为 Vue3 + Vant + ECharts 单页，4 个 tab（记账/批量/记录/统计），所有 fetch 无 auth 头
- `pom.xml` **无** redis/jjwt/security 依赖，需新增

### 需改造的 API（按 userId 过滤）
- `MessageController`: `/api/send`、`/api/batch-send`、`/api/records`、`/api/records/{id}`(DELETE/PUT)、`/api/sms`(用 sms_key)
- `AiController`: `/api/ai-parse`、`/api/ai-command`、`/api/ai-command-async`、`/api/ai-command-result`
- `StatisticsController`: `/api/stats/*` 四个接口

### 不需改动
- `MessageConsumer`：消费时记录已带 userId（插入时写入），无需再过滤
- `PendingMessageRetryJob`：重试按 recordId 取记录，userId 已在记录上

## 实施步骤

### 第 1 步：pom.xml 新增依赖

文件：[pom.xml](file:///e:/code/feishu-project/pom.xml)

在 `<dependencies>` 内新增：
```xml
<!-- Redis：存储 JWT token，支持服务端失效 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- JWT 生成与解析 -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>

<!-- BCrypt 密码加密（仅 crypto 模块，不引入完整 Spring Security） -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

### 第 2 步：数据库 schema

文件：[sql/init.sql](file:///e:/code/feishu-project/sql/init.sql)

追加用户表 + 给消息记录表加 user_id 列。**注意**：用户已有数据，用 ALTER 而非重建。

新增 SQL 脚本（追加到 init.sql 末尾，或新建 `sql/v2_user.sql`）：
```sql
-- ===== 用户表 =====
CREATE TABLE IF NOT EXISTS `t_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(128) NOT NULL COMMENT 'BCrypt加密后的密码',
    `nickname`    VARCHAR(64)  NULL     COMMENT '昵称',
    `sms_key`     VARCHAR(64)  NULL     COMMENT 'SMS转发密钥（SmsForwarder调用/api/sms时带此key）',
    `create_time` DATETIME     NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_sms_key` (`sms_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ===== 消息记录表加 user_id =====
ALTER TABLE `t_message_record` ADD COLUMN `user_id` BIGINT NULL COMMENT '用户ID（数据隔离）' AFTER `id`;
CREATE INDEX `idx_user_id` ON `t_message_record` (`user_id`);

-- ===== 给已有数据补一个默认用户（避免老数据 user_id 为空） =====
-- 先手动 INSERT 一个默认用户，再把已有记录归到该用户
-- INSERT INTO t_user(username,password,nickname,sms_key) VALUES('admin','$2a$10$...','管理员','default-sms-key');
-- UPDATE t_message_record SET user_id=(SELECT id FROM t_user WHERE username='admin') WHERE user_id IS NULL;
```

> **执行方式**：用户在 Linux 虚拟机上 `mysql -u root -p feishu_book < sql/v2_user.sql` 执行。

### 第 3 步：实体类改造

#### 3.1 User.java 加 smsKey
文件：[src/main/java/com/code/feishu/entity/User.java](file:///e:/code/feishu-project/src/main/java/com/code/feishu/entity/User.java)

新增字段：
```java
/** SMS 转发密钥（SmsForwarder 调用 /api/sms 时以此定位用户） */
private String smsKey;
```

#### 3.2 MessageRecord.java 加 userId
文件：[src/main/java/com/code/feishu/entity/MessageRecord.java](file:///e:/code/feishu-project/src/main/java/com/code/feishu/entity/MessageRecord.java)

在 `id` 字段后新增：
```java
/** 用户ID（数据隔离用，所有查询按此过滤） */
private Long userId;
```

### 第 4 步：新增基础设施类

#### 4.1 UserMapper.java（新增）
路径：`src/main/java/com/code/feishu/mapper/UserMapper.java`
```java
package com.code.feishu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.code.feishu.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
```

#### 4.2 UserContext.java（新增）— ThreadLocal 存 userId
路径：`src/main/java/com/code/feishu/context/UserContext.java`
```java
package com.code.feishu.context;

public class UserContext {
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    public static void setUserId(Long userId) { USER_ID.set(userId); }
    public static Long getUserId() { return USER_ID.get(); }
    public static void clear() { USER_ID.remove(); }
}
```

#### 4.3 JwtUtil.java（新增）
路径：`src/main/java/com/code/feishu/util/JwtUtil.java`

职责：生成 / 解析 JWT。subject = userId 字符串，7 天过期。
- `generate(Long userId)` → 返回 JWT 字符串
- `parseUserId(String jwt)` → 返回 userId，解析失败返回 null
- 密钥从 `application.properties` 的 `jwt.secret` 读取（用 `@Value`）
- 用 JJWT 0.12.x API（`Jwts.builder().subject().signWith().expiration()`）

#### 4.4 RedisConfig.java（新增）
路径：`src/main/java/com/code/feishu/config/RedisConfig.java`

配置 `StringRedisTemplate`（key/value 都是 String，足够存 token）。Spring Boot 已自动配置 `StringRedisTemplate`，此类主要用于显式 Bean 声明和序列化确认（可省略，直接注入 `StringRedisTemplate` 即可）。**为简化，本步可跳过 RedisConfig，直接在 UserService 注入 `StringRedisTemplate`。**

#### 4.5 AuthInterceptor.java（新增）
路径：`src/main/java/com/code/feishu/interceptor/AuthInterceptor.java`

实现 `HandlerInterceptor`：
- `preHandle`：
  1. 从 `Authorization` 头取 `Bearer <token>`
  2. 用 JwtUtil 解析 userId
  3. 查 Redis `auth:token:<token>` 是否存在且值 == userId
  4. 校验通过 → `UserContext.setUserId(userId)`，return true
  5. 校验失败 → 返回 401 JSON `{"ok":false,"code":401,"msg":"未登录或token已失效"}`
- `afterCompletion`：`UserContext.clear()`（防止线程池线程复用导致 userId 串号）

#### 4.6 WebMvcConfig.java（新增）
路径：`src/main/java/com/code/feishu/config/WebMvcConfig.java`

实现 `WebMvcConfigurer`，注册拦截器：
```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Autowired private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/login",      // 登录
                "/api/ping",       // 健康检查
                "/api/sms",        // SmsForwarder 用 sms_key 认证
                "/api/register"    // 注册（已关闭，但预留）
            );
        // 静态资源（index.html 等）默认不被 /api/** 拦截，无需排除
    }
}
```

### 第 5 步：用户服务与接口

#### 5.1 UserService.java（新增）
路径：`src/main/java/com/code/feishu/service/UserService.java`

职责：
- `login(username, password)`：
  1. 按 username 查 User
  2. BCrypt 校验密码（`BCryptPasswordEncoder.matches`）
  3. 生成 JWT，存 Redis `auth:token:<jwt>` → userId，TTL 7 天
  4. 返回 LoginVO（token + userId + nickname）
- `logout(token)`：删除 Redis key
- `findBySmsKey(String smsKey)`：按 sms_key 查用户（/api/sms 用）
- `getById(Long id)`：查用户信息（脱敏，不含密码）

依赖注入：`UserMapper`、`JwtUtil`、`StringRedisTemplate`、`BCryptPasswordEncoder`（用 `@Bean` 配置或直接 `new BCryptPasswordEncoder()`）。

#### 5.2 UserController.java（新增）
路径：`src/main/java/com/code/feishu/controller/UserController.java`

接口：
- `POST /api/login` body=`{username, password}` → `{ok, token, userId, nickname}`
- `POST /api/logout` → `{ok:true}`（从 Header 取 token 删 Redis）
- `GET /api/user/info` → `{ok, userId, username, nickname, smsKey}`（已登录后可查自己的 sms_key，用于配置 SmsForwarder）

#### 5.3 DTO/VO（新增）
- `dto/LoginDTO.java`：username, password
- `vo/LoginVO.java`：token, userId, username, nickname
- `vo/UserInfoVO.java`：userId, username, nickname, smsKey

### 第 6 步：改造 MessageController

文件：[MessageController.java](file:///e:/code/feishu-project/src/main/java/com/code/feishu/controller/MessageController.java)

#### 6.1 `/api/send`
- `record.setUserId(UserContext.getUserId())` 在插入前设置
- `getNextSortNum()` 改为按 userId 过滤：`SELECT MAX(sort_num) FROM t_message_record WHERE user_id=?`

#### 6.2 `/api/batch-send`
- 同上，每条 record 设 userId
- `getNextSortNum()` 同样按 userId 过滤

#### 6.3 `/api/records`
- 查询加 `.eq(MessageRecord::getUserId, UserContext.getUserId())`

#### 6.4 `/api/records/{id}` DELETE/PUT
- selectById 后校验 `record.getUserId().equals(UserContext.getUserId())`，不匹配返回 403/404

#### 6.5 `/api/sms`（关键改造）
- 方法签名加 `@RequestParam(required=false) String token`
- 用 `userService.findBySmsKey(token)` 查用户
- 查不到 → 返回 `{"code":-1,"msg":"无效的 sms_key"}`
- `record.setUserId(user.getId())`
- `getNextSortNum()` 按该 userId 过滤
- **此接口被 AuthInterceptor 排除**，用 sms_key 认证而非 JWT

#### 6.6 `getNextSortNum()` 重构
```java
private int getNextSortNum(Long userId) {
    Integer max = recordMapper.selectObjs(
        new LambdaQueryWrapper<MessageRecord>()
            .select(MessageRecord::getSortNum)
            .eq(MessageRecord::getUserId, userId)
            .orderByDesc(MessageRecord::getSortNum)
            .last("LIMIT 1")
    ).stream().filter(o -> o instanceof Integer).map(o -> (Integer) o).findFirst().orElse(0);
    return max + 1;
}
```

### 第 7 步：改造 AiController + AiCommandService

#### 7.1 AiController.java
文件：[AiController.java](file:///e:/code/feishu-project/src/main/java/com/code/feishu/controller/AiController.java)

- `/api/ai-command`、`/api/ai-command-async`：从 `UserContext.getUserId()` 取 userId，传给 service
- `/api/ai-command-result`：taskId 是本会话生成的，无需 userId（任务存内存，5 分钟过期，风险可接受）。**可选增强**：在 task 里存 userId，查询时校验归属。

#### 7.2 AiCommandService.java
文件：[AiCommandService.java](file:///e:/code/feishu-project/src/main/java/com/code/feishu/ai/service/AiCommandService.java)

- `execute(String userInput)` → `execute(String userInput, Long userId)`
- `submitCommand(String)` → `submitCommand(String, Long userId)`，并在 AiCommandTask 里存 userId
- 查询最近记录上下文加 `.eq(MessageRecord::getUserId, userId)`
- `executeQuery` 的 selectList 加 userId 过滤
- `findTarget` 中 merchant 模糊查询的兜底 selectList 加 userId 过滤

### 第 8 步：改造 StatisticsController

文件：[StatisticsController.java](file:///e:/code/feishu-project/src/main/java/com/code/feishu/controller/StatisticsController.java)

- `queryByDateRange(start, end)` → 加 `userId` 参数，查询加 `.eq(MessageRecord::getUserId, userId)`
- 四个接口（summary/daily/category/compare）都从 `UserContext.getUserId()` 取 userId 传入

### 第 9 步：application.properties 配置

文件：[application.properties](file:///e:/code/feishu-project/src/main/resources/application.properties)

新增：
```properties
# ===== Redis 配置（存 JWT token） =====
spring.data.redis.host=10.13.27.249
spring.data.redis.port=6379
spring.data.redis.password=
spring.data.redis.database=0
spring.data.redis.timeout=5000ms

# ===== JWT 配置 =====
jwt.secret=feishu-book-jwt-secret-key-2026-please-change-in-production-at-least-32-chars
jwt.expire-days=7
```

> Redis 部署在虚拟机 10.13.27.249（与 MySQL/RabbitMQ 同机）。需在虚拟机 `apt install redis-server` 并 `sudo systemctl enable --now redis-server`。默认无密码，监听 127.0.0.1；需改 `/etc/redis/redis.conf` 的 `bind 0.0.0.0` 并重启以允许远程连接（与 MySQL 同样的处理）。

同步更新 `application-template.properties`。

### 第 10 步：前端改造

文件：[index.html](file:///e:/code/feishu-project/src/main/resources/static/index.html)

#### 10.1 增加登录态管理
在 Vue setup 顶部新增：
```js
const token = ref(localStorage.getItem('token') || '');
const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || 'null'));
const isLoggedIn = ref(!!token.value);
const loginForm = reactive({ username: '', password: '' });
const loginLoading = ref(false);
```

#### 10.2 新增登录页 UI
在 `<div id="app">` 最外层加 `v-if="!isLoggedIn"` 的登录卡片：
- 用户名、密码输入框
- 登录按钮
- 登录成功 → 存 localStorage，`isLoggedIn=true`，加载主界面

#### 10.3 封装 apiFetch 工具函数
替换所有裸 `fetch` 调用：
```js
const apiFetch = async (url, opts = {}) => {
  const headers = { ...(opts.headers || {}) };
  if (token.value) headers['Authorization'] = 'Bearer ' + token.value;
  const resp = await fetch(url, { ...opts, headers });
  if (resp.status === 401) {
    // token 失效，清空并回到登录页
    token.value = ''; userInfo.value = null; isLoggedIn.value = false;
    localStorage.removeItem('token'); localStorage.removeItem('userInfo');
    vant.showToast({ type: 'fail', message: '登录已失效，请重新登录' });
    throw new Error('401');
  }
  return resp;
};
```
把现有所有 `fetch(`/api/...`)` 替换为 `apiFetch(`/api/...`)`。

#### 10.4 导航栏加用户信息 + 登出
在 `.nav` 区域加：
```html
<div style="position:absolute; right:16px; font-size:12px; color:#999;" v-if="isLoggedIn">
  {{ userInfo?.nickname || userInfo?.username }}
  <span style="margin-left:8px; color:#1a1a1a; cursor:pointer;" @click="logout">退出</span>
</div>
```

#### 10.5 登录/登出方法
```js
const doLogin = async () => {
  loginLoading.value = true;
  try {
    const resp = await fetch('/api/login', {
      method: 'POST', headers: {'Content-Type':'application/json; charset=utf-8'},
      body: JSON.stringify(loginForm)
    });
    const data = await resp.json();
    if (data.ok) {
      token.value = data.token;
      userInfo.value = { userId: data.userId, username: data.username, nickname: data.nickname };
      localStorage.setItem('token', data.token);
      localStorage.setItem('userInfo', JSON.stringify(userInfo.value));
      isLoggedIn.value = true;
      vant.showToast({ type: 'success', message: '登录成功' });
    } else {
      vant.showToast({ type: 'fail', message: data.msg || '登录失败' });
    }
  } catch { vant.showToast({ type: 'fail', message: '请求失败' }); }
  finally { loginLoading.value = false; }
};

const logout = async () => {
  try { await apiFetch('/api/logout', { method: 'POST' }); } catch {}
  token.value = ''; userInfo.value = null; isLoggedIn.value = false;
  localStorage.removeItem('token'); localStorage.removeItem('userInfo');
};
```

#### 10.6 在「记录」或「统计」tab 显示 sms_key
可选：在用户信息区域或单独提示框显示当前用户的 `smsKey`，方便用户配置手机端 SmsForwarder 的 webhook URL：`http://10.13.27.249:8088/api/sms?token=<smsKey>`。通过 `/api/user/info` 接口获取。

## 假设与约束

1. **Redis 部署**：假设用户在虚拟机 10.13.27.249 上安装 Redis 并开放远程访问（与 MySQL 同流程）。若未安装，需先 `apt install redis-server`。
2. **已有数据迁移**：执行 `sql/v2_user.sql` 后，需手动 INSERT 第一个用户并用 UPDATE 把已有 `t_message_record` 的 `user_id` 填上，否则老数据查不到。
3. **SmsForwarder 配置变更**：用户需在手机端 SmsForwarder 的 webhook URL 末尾加 `?token=<自己的 sms_key>`。
4. **BCrypt 密码生成**：用户创建账号时需要 BCrypt 加密后的密码。提供一个临时方式：在后端写一个 `/api/gen-password?raw=xxx`（仅开发用，上线删除），或用在线 BCrypt 工具生成，或用 Python `passlib`。
5. **JWT 密钥**：`jwt.secret` 必须至少 32 字符，生产环境务必修改。
6. **拦截器不拦截静态资源**：`/api/**` 模式不匹配 `/index.html`，静态资源可正常访问。
7. **AiCommandTask 内存存储**：异步任务结果存内存（ConcurrentHashMap），多实例部署会失效。当前单机部署足够。如需多实例，改用 Redis 存任务结果。

## 验证步骤

### 编译验证
```bash
cd e:/code/feishu-project
mvn clean package -DskipTests
```
确保无编译错误。

### 数据库验证（虚拟机上）
```bash
mysql -u root -p feishu_book < sql/v2_user.sql
# 创建测试用户（密码 BCrypt 加密，明文 admin123）
INSERT INTO t_user(username,password,nickname,sms_key) 
VALUES('admin','$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy','管理员','test-sms-key-001');
SELECT * FROM t_user;
DESC t_message_record;  -- 确认 user_id 列存在
```

### Redis 验证
```bash
redis-cli ping  # 返回 PONG
```

### 接口验证
1. `POST /api/login` body=`{username:admin,password:admin123}` → 返回 token
2. `GET /api/user/info` 带 `Authorization: Bearer <token>` → 返回用户信息含 smsKey
3. `POST /api/send` 带 token → 记录的 user_id = 当前用户
4. `GET /api/records` 带 token → 只返回当前用户的记录
5. `POST /api/sms?token=test-sms-key-001` 不带 JWT → 短信归到 sms_key 对应用户
6. `POST /api/sms?token=wrong` → 返回「无效的 sms_key」
7. 不带 token 访问 `/api/records` → 401
8. `POST /api/logout` → Redis key 删除，再用旧 token 访问 → 401

### 前端验证
1. 打开页面 → 显示登录页
2. 登录成功 → 进入主界面，导航栏显示用户名
3. 记录/统计/AI 指令正常工作（数据隔离生效）
4. 退出 → 回到登录页
5. 清空 localStorage 后刷新 → 回到登录页

## 实施顺序（推荐）

1. pom.xml 加依赖 → `mvn clean compile` 确认依赖拉取
2. application.properties 加 Redis/JWT 配置
3. SQL 脚本（用户先在虚拟机执行）
4. 实体类改造（User 加 smsKey、MessageRecord 加 userId）
5. 新增基础设施：UserContext、JwtUtil、AuthInterceptor、WebMvcConfig
6. 新增 UserMapper、UserService、UserController、DTO/VO
7. 改造 MessageController（含 /api/sms 的 sms_key 认证）
8. 改造 AiController + AiCommandService
9. 改造 StatisticsController
10. 前端 index.html 改造（登录页 + apiFetch + Authorization 头）
11. 本地编译 → 部署到虚拟机 → 端到端验证

## 风险与回滚

- **风险**：改造涉及所有 API，若拦截器配置错误可能导致全部接口 401。
- **缓解**：拦截器先只拦截 `/api/records` 测试通过后再全量 `/api/**`；或先排除所有接口逐个加。
- **回滚**：git revert 本次提交即可，数据库 ALTER 的 user_id 列保留无害（NULL 表示历史数据）。
