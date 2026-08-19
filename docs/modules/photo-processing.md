# 图片处理模块

> 图片处理模块负责遍历 Immich 图片、读取图片详情和相簿信息、调用视觉模型生成描述与受控标签，并将结果安全写回 Immich。模块不负责视觉模型本身的推理实现，也不负责 Immich 服务端数据存储。

## 1. 职责与边界

### 负责

- 分页扫描 Immich 图片并执行单图或批量处理。
- 根据配置跳过指定相簿，读取图片详情、标签和描述。
- 调用视觉模型、校验模型结果，并增量写回描述和标签。
- 使用 JSONL 保存处理状态、读取缓存和失败审计信息。

### 不负责

- 视觉模型服务的部署和模型推理实现。
- Immich 服务端标签、相簿和资产数据的持久化实现。

## 2. 关键入口

| 类型 | 入口 | 说明 |
| --- | --- | --- |
| 命令行 | `src/main/java/com/xiwang/phototagautogen/PhotoTagCommandLineRunner.java` | 解析运行参数、验证外部连接并启动批处理。 |
| 内部调用 | `PhotoProcessingService.run(...)` | 执行全局扫描、单图处理和处理汇总。 |

## 3. 核心代码结构

| 路径 | 职责 |
| --- | --- |
| `src/main/java/com/xiwang/phototagautogen/service/PhotoProcessingService.java` | 图片扫描、详情/相簿读取、模型调用和写回编排。 |
| `src/main/java/com/xiwang/phototagautogen/client/ImmichClient.java` | Immich 读取和写回能力抽象。 |
| `src/main/java/com/xiwang/phototagautogen/client/ImmichHttpClient.java` | Immich HTTP API 实现。 |
| `src/main/java/com/xiwang/phototagautogen/state/JsonlStateStore.java` | JSONL 状态及读取缓存的加载、追加和查询。 |
| `src/main/java/com/xiwang/phototagautogen/domain/ProcessingState.java` | 单张图片的处理状态、读取缓存和按文件修改时间匹配逻辑。 |
| `src/main/java/com/xiwang/phototagautogen/config/ProcessingProperties.java` | 批处理、状态文件和增量模式配置。 |

## 4. 核心流程

1. 命令行入口解析 `force`、`dry-run`、单图 ID 和增量开关。
2. 服务加载词表和 Immich 标签索引，然后分页读取图片列表。
3. 默认增量模式按图片 `fileModifiedAt` 查找 JSONL 缓存：
   - 已缓存图片详情时复用描述和标签，避免再次调用资产详情接口。
   - 配置了跳过相簿且已缓存相簿时复用相簿名称和 ID，避免再次调用相簿接口。
   - 缓存缺失或文件修改时间变化时重新读取，并更新缓存。
4. `--processing.full=true` 或 `--processing.incremental=false` 时忽略读取缓存和成功历史快捷跳过，重新读取图片标签/相簿。
5. 命中跳过相簿或已有标签时结束当前图片；否则下载预览图、调用视觉模型、校验结果并写回 Immich。
6. 非 dry-run 模式将读取结果、成功结果或失败结果追加到 JSONL；单条失败不终止批处理。

## 5. 数据模型与持久化

- **核心状态**：`ProcessingState` 以图片 ID 为键保存最新状态。
- **状态文件**：由 `processing.state-file` 指定，默认 `.data/processing-state.jsonl`。
- **读取缓存字段**：`fileModifiedAt`、`assetDetailRead`、`albumsRead`、资产类型、描述、标签和相簿名称/ID。
- **一致性规则**：只有当状态中的 `fileModifiedAt` 与当前图片列表中的值相同，缓存才可复用；相簿缓存只保存判断跳过相簿所需的名称和 ID，不保存完整资产 ID 列表。

## 6. 依赖与集成

- **Immich**：通过 `ImmichClient` 分页读取图片、读取详情和相簿，并写回描述、标签。
- **视觉模型**：通过 `VisionModelClient` 下载预览图并生成描述、标签。
- **本地文件系统**：通过 JSONL 状态文件提供跨运行缓存和审计记录。

## 7. 配置与运行约束

| 配置或参数 | 用途 | 注意事项 |
| --- | --- | --- |
| `PROCESSING_INCREMENTAL` | 默认启用增量读取。 | 默认值为 `true`。 |
| `--processing.full=true` | 临时强制全量读取。 | 优先用于一次性重建读取缓存。 |
| `--processing.incremental=false` | 临时关闭增量读取。 | 与 `--processing.full=true` 等价。 |
| `PROCESSING_STATE_FILE` | 指定 JSONL 状态和读取缓存文件。 | 需要保证运行用户具有读写权限。 |
| `PROCESSING_SKIP_ALBUMS` | 指定按名称或 ID 跳过的相簿。 | 只有配置该项时才读取并缓存相簿信息。 |
| `PROCESSING_FORCE` | 增量模式下绕过成功历史。 | 不绕过实时已有标签保护。 |

## 8. 异常、可靠性与可观测性

- 单条 JSONL 损坏不会阻止其他状态加载。
- Immich 和视觉模型请求沿用客户端重试与错误编码；单张图片失败会记录 `FAILED` 状态并继续处理。
- 读取缓存以文件修改时间校验，图片发生变化后自动失效。
- 日志包含图片 ID、读取缓存命中、处理结果和失败原因，不记录 API Key 或图片二进制内容。
- 从 JSONL 文件复用资产详情或相簿信息后命中跳过条件时，仅保留缓存命中日志，不重复输出跳过原因；实时读取的资产仍输出跳过原因。
- “开始处理图片”日志仅在完成历史状态、相簿、资产类型和已有标签判断后输出。

## 9. 测试与验证

| 类型 | 路径或命令 | 覆盖范围 |
| --- | --- | --- |
| 单元测试 | `src/test/java/com/xiwang/phototagautogen/state/JsonlStateStoreTest.java` | 状态追加、成功判断和详情/相簿读取缓存匹配。 |
| 单元测试 | `src/test/java/com/xiwang/phototagautogen/service/PhotoProcessingServiceTest.java` | 图片处理、跳过相簿和增量读取行为。 |
| 构建验证 | `mvn -q -DskipTests compile` | 本次已验证主代码编译通过。 |
| 定向测试 | `mvn -q -Dtest=JsonlStateStoreTest test` | 本次已验证状态存储测试通过。 |
