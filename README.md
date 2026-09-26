# cc-airspace

管理无人机飞行申请、航段与空域时隙。

## 开发环境

- JDK 21
- Spring Boot 4.1.1
- Maven Wrapper 3.9.9
- H2

## 本地运行

启动服务：

    ./mvnw spring-boot:run

运行测试：

    ./mvnw clean test

## 核心概念

- **航段（AirSegment）**：空域资源，带高度范围 `[minAltitude, maxAltitude]` 与容量 `capacity`。
- **许可（FlightClearance）**：一次飞行申请，持有有序航段占用（SegmentReservation），
  状态机为 `ACTIVE -> CANCELLED / COMPLETED`。
- **航线版本（RouteVersion）**：许可的完整航线快照。初始提交为版本 1，每次改道追加一个版本，
  历史版本全部保留。
- **占用事件（SegmentOccupancyEvent）**：逐航段的 `ACQUIRE` / `RELEASE` 审计轨迹，
  提交、改道、取消时按发生顺序记录。

## 改道（Reroute）

改道申请 `RerouteRequest` 引用现有许可，包含：

| 字段 | 含义 |
| --- | --- |
| `rerouteNo` | 改道业务号（幂等键，全局唯一） |
| `expectedVersion` | 申请基于的许可版本，与当前版本不一致则拒绝 |
| `fromLegIndex` | 改道起点航段序号，新航线 = 当前航线 `[0, fromLegIndex)` 前缀 + `legs` |
| `effectiveAt` | 新航线生效时间，容量判定区间为 `[effectiveAt, 许可结束时间)` |
| `legs` | 替换尾部的有序新航段（航段代码 + 高度） |

语义与约束：

1. **衔接位置**：`fromLegIndex` 不得小于许可当前已飞过航段数（`flownLegCount`），
   已飞过的航段不可改写；保留前缀与新航段不得重复。
2. **原子换容量**：事务内先按主键升序一次性锁定全部新旧航段，逐段判定高度范围与容量
   （排除本许可自身占用），全部满足后才换线——先取得全部新航段容量，再释放不再使用的旧航段。
   任一航段容量不足或许可版本已变化，整个事务回滚，原许可与原占用保持不变。
3. **并发安全**：改道、取消、飞行开始、位置上报都先取许可行写锁串行化；
   已取消（`CANCELLED`）或已完成（`COMPLETED`）的许可不能改道；
   基于旧版本或旧位置的请求直接拒绝，不会释放当前仍需要的容量。
4. **幂等**：相同 `rerouteNo` + 相同内容返回首次结果（`created=false`）；
   相同 `rerouteNo` + 不同内容抛幂等冲突。数据库唯一约束兜底并发重复。

## 飞行进度

- `start(externalNo)`：标记飞行开始（幂等）。
- `reportProgress(externalNo, flownLegCount)`：推进已飞过航段数；基于旧位置的上报被忽略，
  不会回退；飞完全部航段后许可自动置为 `COMPLETED`。

## 查询能力

- `get(externalNo)`：当前有效航线（含版本号、已飞航段数、逐航段占用数）。
- `routeHistory(externalNo)`：全部航线历史版本（含每次改道前后的完整航线）。
- `occupancyEvents(externalNo)`：逐航段占用事件流（ACQUIRE / RELEASE，含版本与改道业务号）。
- `conflicts(externalNo)`：当前航线中时间窗内其他有效许可占用数已达到容量的冲突航段。

## 测试

`RerouteServiceTests` 覆盖：改道成功换线与容量转移、幂等回放与冲突、容量不足 / 版本过期
时的回滚不变性、已取消与已完成许可的改道拒绝、已飞航段保护与旧位置请求不释放容量、
高度与生效时间校验、历史版本与占用事件记录、冲突航段查询，以及并发改道只成功一次、
改道与取消并发下最终状态一致。
