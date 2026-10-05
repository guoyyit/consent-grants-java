# consent-grants-java

访问同意（Consent）与授权授予（Grant）的生命周期管理：用户向第三方客户端逐项授权后，
系统签发受约束的访问凭据；授权范围变化或同意撤销时，已发放的权限安全收敛。

## 模块结构

```
consent/
├── domain/     不可变领域类型：ScopeVersion / PermissionItem / ConsentRecord / Grant / RevocationJob / AuditEvent
├── store/      持久化抽象（内存实现）：ConsentStore（含按用户对锁）、GrantStore、RevocationJobStore、ScopeVersionStore、AuditLogStore
├── service/    核心业务：ConsentService、GrantService、ScopeRegistry、RevocationPropagationService、ConsentQueryService
├── cache/      ConsentCache：带纪元（epoch）校验的读穿缓存
├── audit/      AuditService：每次状态迁移与每次读取（允许/拒绝）均落审计
├── api/        REST：ConsentController、GrantController、AdminController
└── config/     ConsentProperties、ScopeBootstrap（首次启动发布初始范围版本）
```

## 同意语义

- **唯一收敛状态**：同一 `(userId, clientId)` 只有一条 `ConsentRecord`，携带单调递增的
  `revision`。重复提交相同内容属于**重放（replay）**：不修改记录、不推进 revision、不产生
  额外审计副作用；不同内容的提交整体替换授权集合并推进 revision。授权范围可增可减，
  最终状态总是唯一且可解释（由最近一次有效提交决定）。
- **逐项同意**：每次提交显式给出 `grant` 与 `deny` 两个集合，二者必须不相交，
  且每个权限项在绑定的范围版本中必须处于 `ACTIVE` 状态，否则整批拒绝。
- **撤销**：`DELETE /api/consents/{userId}/{clientId}` 将状态置为 `REVOKED` 并推进
  revision。撤销是幂等的（重复撤销无副作用）。撤销后：
  1. 下游校验立即失败（校验路径实时核对同意状态，不依赖传播完成）；
  2. 系统 enqueue 一个持久化 `RevocationJob`，把该用户对下所有活跃凭据逐条置为
     `REVOKED`，进度逐条落库。
- **签发约束**：`GrantService.issue` 在与提交/撤销相同的按 `(userId, clientId)` 锁内
  读取同意并落库凭据，因此不会出现"撤销之后仍签发成功"，也不会出现"签发成功却读不到
  同意记录"。凭据记录签发时的 `consentRevision` 与范围版本。

## 范围定义版本兼容规则

- 范围定义是**不可变、单调递增**的版本序列（`ScopeVersion`）。进行中的授权请求通过
  提交里的 `scopeVersion` 确定性地绑定到某一个版本。
- 权限项生命周期：`ACTIVE` → （`SPLIT` 拆分为更细粒度项，记录 `replacedBy`）或
  （`DEPRECATED` 废弃）。
- 历史同意在新版本下的解释（`ScopeRegistry.migrate`）：
  - `ACTIVE` 项原样保留；
  - `SPLIT` 项**展开**为其全部替代项（递归、带环保护）；
  - `DEPRECATED` 项与目标版本中不存在的项**丢弃**——历史同意既不静默失效（拆分项
    继续有效），也绝不放大（废弃/未知项不产生任何权限）。
- 新旧版本的处理结果可区分：对某版本提交该版本中不可同意的项（未知/已拆分/已废弃），
  返回 `422 INVALID_SCOPE_ITEM` 并列出 `rejectedItems`；显式绑定旧版本提交旧项仍然合法。
- 凭据校验时，将凭据的权限项从签发版本**向前迁移**到当前同意版本逐项核对：
  任一项迁移后为空或未被当前同意完全覆盖，凭据即失效（收敛而非放大）。
- 发布新版本会使全部同意缓存失效（解释可能已变化）。

## 撤销传播与恢复

- 每次撤销生成持久化 `RevocationJob`（`pending/completed` 两个权限项集合 + 状态机
  `PENDING/IN_PROGRESS/FAILED/DONE`）。每收敛一条凭据就落一次进度。
- 下游失败时任务标记 `FAILED` 且剩余项保持 pending——系统不会停留在不可见的部分撤销
  状态；`recoverUnfinished()`（由 `RevocationRecoveryRunner` 在启动时调用）从最后完成的
  位置继续收敛。对同一凭据重复收敛是幂等的。

## 读取与审计可见性

- 用户主体（`X-Subject` + 默认 USER 角色）只能读取自己的同意明细；跨用户读取返回
  `403` 且**拒绝事件本身被审计**（可追溯越权尝试）。
- 管理端（`X-Role: ADMIN`）可读取任意单条记录；聚合视图
  `GET /api/admin/consents/summary/{clientId}` 只返回各状态计数，不泄露任何用户级明细。
- 所有写操作（提交/撤销/签发/任务推进）与所有读取（允许/拒绝/未找到）都写入追加式
  审计日志，管理端可通过 `GET /api/admin/audit/{userId}/{clientId}` 查询。

## 缓存一致性

`ConsentCache` 采用 seqlock 式纪元协议：写路径先改库再 bump 纪元（`invalidate` /
`invalidateAll`）；读路径在加载前后各读一次纪元，若加载期间发生失效则丢弃结果重新
读取，缓存条目携带加载时的纪元、过期即拒绝。因此并发失效与读取交错时不会返回过期结论。

## 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `consent.scope.initial-version` | `1` | 首次启动时发布的初始范围版本号（已有版本时不会重发） |
| `consent.scope.initial-items` | `profile.read,profile.write,email.read` | 初始范围包含的 ACTIVE 权限项 |

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/consents/{userId}/{clientId}` | 提交同意 `{scopeVersion, grant[], deny[]}`；返回 `changed/replay` 与收敛后状态 |
| DELETE | `/api/consents/{userId}/{clientId}` | 撤销同意（幂等），触发撤销传播 |
| GET | `/api/consents/{userId}/{clientId}` | 读取同意（需 `X-Subject`，跨用户 403） |
| POST | `/api/grants/{userId}/{clientId}` | 签发凭据 `{items[]}`；无有效同意时 409 |
| POST | `/api/grants/validate` | 下游校验 `{token}` → `{valid, reason?}` |
| POST | `/api/admin/scopes` | 发布新范围版本 `{version, items{code:{code,status,replacedBy}}}` |
| GET | `/api/admin/consents/summary/{clientId}` | 管理端聚合计数（需 ADMIN） |
| GET | `/api/admin/audit/{userId}/{clientId}` | 审计轨迹（需 ADMIN） |

## 构建与测试

```bash
mvn test
```

测试覆盖：重复提交收敛（`ConsentServiceTest`）、版本演进（`ScopeVersionEvolutionTest`）、
撤销传播与中途恢复（`RevocationPropagationTest`）、并发签发/撤销（`ConcurrencyTest`，
竞态用例重复 10 轮）、访问控制与缓存一致性（`AccessControlAndCacheTest`）、
HTTP 端到端（`ConsentApiIntegrationTest`）。
