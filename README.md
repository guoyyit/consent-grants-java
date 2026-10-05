# Consent Grants — 访问同意与授权授予生命周期管理

在用户向第三方客户端授权之后，本服务负责：

- 按**细粒度权限项**逐项收敛用户的同意（GRANTED / DENIED），授权范围可增可减；
- 在**范围定义（scope definition）版本演进**时，让历史同意既不静默失效、也不被错误放大；
- 撤销同意后让已签发凭据在下游校验中**确定失效**，未完成的传播可从持久化位置恢复并收敛；
- 在同意、撤销、凭据签发并发时给出**唯一且自洽**的结果；
- 区分用户端与管理端的可见范围，越权访问被拒绝并**可审计**；
- 在缓存与数据库之间保持一致，范围变更 / 撤销会使相关缓存失效。

技术栈：Java 17、Spring Boot 3.4、Spring JDBC、H2（文件 / 内存）、JUnit 5。

---

## 1. 核心概念与同意语义

### 1.1 范围定义版本（Scope Version）

权限目录是**版本化、不可变**的。每个版本包含一组权限项（`scope_item`）：

| 字段 | 含义 |
| --- | --- |
| `key` | 权限项标识，如 `contacts.read` |
| `status` | `ACTIVE` / `DEPRECATED` / `RETIRED` |
| `replaces` | 该项等价替换的旧版本权限项（改名 / 迁移） |
| `splitInto` | 该项在新版本中被拆分成哪些后继权限项 |

种子定义见 `src/main/resources/scope-definitions.json`（含 `v1` 与一个发生新增 / 拆分 / 废弃的 `v2`）。
已发布版本**不可修改**：重复发布会原样返回既有定义，保证历史同意始终绑定其做出时的那一版定义。

### 1.2 同意提交是“全量状态”，按 (user, client) 唯一收敛

- 每个 `(user_id, client_id)` 在 `consent_grant` 表中只有**唯一一行**授权，并确定地绑定到一个 `scope_version`。
- 每个权限项在 `consent_item_decision` 中只有**唯一一行**决策（主键为 user+client+item），写入使用 `MERGE`。
- 提交必须给出所绑定版本下**全部权限项**的决策（`GRANTED` 或 `DENIED`），缺失任一项会被拒绝，避免出现无法解释的中间状态。
- 重复提交相同内容：不新增行、不增加 `version_seq`、不产生额外审计等副作用。
- 范围变化（增 / 减）会在同一授权行上收敛；当绑定版本变化或授权从撤销中恢复时，`version_seq` 自增。
- `idempotencyKey` 用于客户端重放：相同 key + 相同负载安全重放；相同 key + 不同负载返回 409。

“有效同意视图”（`EffectiveConsent`）对每一项给出**决策 + 来源 + 说明**，是唯一可解释的最终状态。

### 1.3 版本兼容规则（重点：不静默失效、不放大）

把基于旧版本存储的决策解析到授权当前绑定的目标版本时，遵循以下规则：

1. **同键保留（EXPLICIT）**：新旧版本都存在的同名权限项，决策原样保留。
2. **等价替换迁移（MIGRATED_EQUIVALENT）**：新项用 `replaces` 指回唯一前驱，且前驱**没有**被拆分，则旧决策等价迁移到新项。
3. **拆分不放大（SPLIT_AMBIGUOUS）**：旧项被拆成多个后继（源版本 `splitInto` 非空，或多个新项 `replaces` 同一前驱）。即便旧项是 GRANTED，后继项也**绝不自动授予**——自动授予全部后继会放大权限，必须由用户重新逐项同意。
4. **废弃 / 下线不静默失效（DROPPED_RETIRED）**：旧版本有、新版本无后继的权限项会保留在可解释视图中，但在新版本下**永远不产生授权**；历史 DENIED 也不会因为改名而重新变成授予。
5. **新增项不自动授予（NEW_UNDECIDED）**：新版本新增的权限项在用户显式同意之前没有授权。

### 1.4 请求的确定性版本绑定

- 同意提交与凭据签发都必须能确定地绑定到某一个范围定义版本。
- 提交未知版本 → 404（可区分）。
- 授权仍绑定在 `v1`，而签发请求显式要求 `v2` → 返回 **428 Precondition Required**（`VersionConflictException`），不会静默重绑。
- 想使用新版本，需要先针对新版本做一次全量同意提交（授权行随之 `v1 → v2`，`version_seq` 自增）。

---

## 2. 凭据签发与撤销传播

### 2.1 签发（Issue）

`POST /api/tokens` 在事务内先对授权行加 `SELECT ... FOR UPDATE` 行锁，再读同意并签发：

- 授权不存在 → 拒绝（不允许“签发成功却读不到同意”）；
- 授权已撤销 → 拒绝（不允许“撤销之后仍成功签发”）；
- 请求的 scope 超出已授予集合 → 拒绝；
- 凭据记录其绑定版本与收敛后的权限项集合。

### 2.2 校验（Introspect）

`POST /api/tokens/introspect` 同时检查：本地持久化状态是否 `ACTIVE`、是否过期、**以及下游撤销存储是否已记录撤销**。撤销一旦在本地提交，凭据立即不再有效；传播完成后下游也一致失效。

### 2.3 撤销与“可恢复的收敛”

`POST /api/revocations` 在**同一个数据库事务**内：

1. 锁定授权行（与签发互斥）；
2. 插入一条 `revocation_event`（可恢复工作单元，初始 `PENDING`）；
3. 锁定该授权下所有 `ACTIVE` 凭据，置为 `REVOKED` 且传播状态 `PENDING`，关联撤销事件；
4. 授权置 `REVOKED`；
5. 使相关缓存失效并写审计。

因此系统不会停留在“同意已撤销但凭据仍有效”的部分撤销状态。

下游传播（`RevocationPropagationService`）把每个撤销事件作为独立工作单元：

- 已 `DONE` 的凭据幂等跳过，崩溃 / 部分失败后从数据库继续；
- 某次下游调用失败：记录尝试次数与错误，事件保持 `PENDING`，由调度器或管理端 `POST /admin/revocations/converge` 重试；
- 全部凭据 `DONE` 后事件才置 `PROPAGATED`；
- 超过最大尝试次数的凭据标记 `FAILED`（仍可查询、可重新驱动），事件保持 `PENDING` 直到人工介入。

`InMemoryDownstreamPropagator` 是模拟的下游凭据有效性存储（按 token 幂等），并支持 `setFailNextN(n)` 注入故障用于测试。

---

## 3. 并发安全

- 所有“读—改—写”同意 / 撤销 / 签发的路径都在事务内对同一 `(user, client)` 授权行加 `FOR UPDATE` 行锁，因此同一用户的同意、撤销、签发被**串行化**，最终结果唯一。
- 撤销先提交 → 其后任何签发都读到 `REVOKED` 而失败。
- 签发先提交 → 撤销会在同事务内捕获该凭据并一并置 `REVOKED`。
- 决策表按主键 `MERGE`，高并发重复提交不会产生重复行。

对应测试：`ConcurrentIssueRevokeTest`（16 线程并发签发 + 撤销；撤销后无 ACTIVE 凭据、无成功校验；纯并发签发全部成功且都能读到一致同意）。

---

## 4. 读取权限与审计

- 用户端用 `X-User-Id` 标识调用方；用户只能操作 / 读取**自己**的同意。
  - `GET /api/consents/{clientId}` 可带 `X-On-Behalf-User`；若指向他人 → **403**，不泄露任何明细，并写 `CONSENT_READ_FORBIDDEN` 审计。
- 管理端 `/admin/**` 必须带 `X-Admin-Key`（与配置 `consent.admin.key` 一致）：
  - 缺失 / 错误 → **401**，并写 `ADMIN_ACCESS_DENIED`；
  - 管理端可跨主体列举授权、查看审计、查看 / 手动驱动撤销传播。
- 所有成功与拒绝的关键动作都写入 `audit_event`（含 actor、subject、client、action、result、时间），管理端可按主体或全局查询。

---

## 5. 缓存与一致性

`ConsentCache` 缓存“有效同意视图”，配合按 `(user, client)` 分片的读写锁（`StripedLocks`）：

- 读：持读锁期间校验 TTL 新鲜度；
- 失效：持写锁删除该授权的**所有版本**缓存，与读互斥；
- 加载：数据库加载发生在读锁之外以避免阻塞失效，加载完成后重新加读锁并复检，使“失效先于本次读取完成”时不会把陈旧加载结果重新发布；
- 提交 / 撤销 → 失效对应授权缓存；发布新的范围定义版本 → 全局失效。

因此在失效之后开始的读取不会得到过期结论。对应测试：`CacheConsistencyTest`。

---

## 6. HTTP 接口概览

### 用户端（`X-User-Id`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/consents` | 全量提交某版本下的逐项同意 |
| GET  | `/api/consents/{clientId}` | 读取自己的有效同意视图（可 `X-On-Behalf-User`，越权 403） |
| POST | `/api/revocations` | 撤销自己对某客户端的同意 |
| POST | `/api/tokens` | 在已同意范围内签发受约束凭据 |
| POST | `/api/tokens/introspect` | 下游凭据校验 |

### 管理端（`X-Admin-Key`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET  | `/admin/scope-versions` | 列出范围定义版本 |
| GET  | `/admin/scope-versions/{version}` | 查看某版本定义 |
| POST | `/admin/scope-versions` | 发布新的不可变版本（会清空缓存） |
| GET  | `/admin/consents/users/{userId}` | 跨主体列出某用户全部授权 |
| GET  | `/admin/consents/users/{userId}/clients/{clientId}` | 跨主体查看单个授权 |
| GET  | `/admin/revocations` | 查看撤销传播状态 |
| POST | `/admin/revocations/converge` | 手动驱动一批挂起传播收敛 |
| GET  | `/admin/audit` | 全局审计 |
| GET  | `/admin/audit/users/{userId}` | 按主体查询审计 |

### 同意提交示例

```json
POST /api/consents
X-User-Id: user-1
Content-Type: application/json

{
  "clientId": "client-a",
  "scopeVersion": "v2",
  "idempotencyKey": "optional-client-generated-key",
  "decisions": [
    {"key": "profile.read", "decision": "GRANTED"},
    {"key": "email.read", "decision": "GRANTED"},
    {"key": "contacts.read", "decision": "DENIED"},
    {"key": "contacts.phone.read", "decision": "DENIED"},
    {"key": "contacts.address.read", "decision": "DENIED"},
    {"key": "calendar.read", "decision": "DENIED"},
    {"key": "offline_access", "decision": "DENIED"}
  ]
}
```

---

## 7. 配置项（`src/main/resources/application.properties`）

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `spring.datasource.url` | `jdbc:h2:file:./data/consentgrants;...` | 文件库，保证撤销传播等进行中工作在重启后仍可恢复；测试用独立临时库 / 内存库 |
| `spring.sql.init.mode` | `always` | 启动时幂等执行 `schema.sql`（`CREATE TABLE IF NOT EXISTS`） |
| `consent.scope-definition.location` | `classpath:scope-definitions.json` | 范围定义种子文档；已存在版本不会被覆盖 |
| `consent.cache.enabled` | `true` | 是否启用有效同意缓存 |
| `consent.cache.ttl-ms` | `60000` | 缓存 TTL（毫秒） |
| `consent.cache.lock-stripes` | `64` | 缓存读写锁分片数 |
| `consent.revocation.propagation-worker-enabled` | `true` | 是否启用后台传播收敛 worker（测试中关闭以确定性断言） |
| `consent.revocation.propagation-interval-ms` | `200` | 后台收敛间隔 |
| `consent.revocation.propagation-batch-size` | `100` | 每批处理的撤销事件数 |
| `consent.revocation.propagation-max-attempts` | `50` | 单凭据最大尝试次数，超过标记 `FAILED` |
| `consent.admin.key` | `change-me-admin-key` | 管理端共享密钥（生产环境务必通过环境变量 / 参数覆盖） |

---

## 8. 构建与测试

```bash
mvn clean test          # 运行全部自动化测试
mvn spring-boot:run     # 启动（默认文件库 ./data，已被 .gitignore 忽略）
```

测试覆盖（共 26 个）：

- `ConsentSubmissionConvergenceTest` — 重复提交幂等、唯一最终状态、无额外副作用、部分提交拒绝；
- `ScopeVersionEvolutionTest` / `ScopeEvolutionResolutionTest` — 版本确定绑定、未知版本、等价迁移、拆分不放大、废弃保留不授予、新增不自动授予、已发布版本不可变；
- `RevocationPropagationTest` — 撤销立即失效、注入中途失败后从持久化位置重试收敛、无凭据时立即完成、重复撤销安全；
- `RestartRecoveryTest` — 两个 Spring 上下文共用文件库，模拟崩溃后重启继续收敛；
- `ConcurrentIssueRevokeTest` — 并发签发 / 撤销的唯一自洽结果；
- `CacheConsistencyTest` — 提交 / 撤销 / 版本发布使缓存失效，失效后读取不返回过期授予；
- `AccessControlAuditWebTest` — 管理端 401、跨主体 403、用户端 / 管理端可见范围区分、越权可审计。
