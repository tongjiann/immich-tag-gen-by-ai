# immich-tag-gen-by-ai

通过本地 Ollama 或 OpenAI 兼容的视觉模型接口，遍历 Immich API Key 所属用户的图片，为图片生成简体中文描述和受控多级标签。

## 功能

- 每次运行分页遍历 Immich 中全部非删除图片，包含归档图片并排除视频。
- 支持 Ollama `/api/chat` 和 OpenAI 兼容 `/v1/chat/completions` 多模态接口，不把图片写入本地文件。
- 仅为空白描述写入模型描述，不覆盖人工描述。
- 仅添加 `taxonomy.yml` 中允许的多级标签，不删除或覆盖已有标签。
- 使用 JSONL 保存最小化处理结果，用于成功/失败审计和问题排查；已成功设置过标签的图片默认跳过，`force` 可重新处理。
- 支持全量执行、dry-run 和单图片调试，并保留 `force` 兼容参数。
- 支持按相簿名称或相簿 ID 配置跳过指定相簿中的图片。

## 环境要求

- Java 21
- Maven 3.9+
- Ollama，或支持图片输入、Chat Completions 和 JSON Schema 响应格式的 OpenAI 兼容服务
- 可访问的 Immich 服务和用户 API Key

当前工程默认使用 Spring Boot 3.5.16，并默认选择 Ollama `qwen2.5vl:7b`。

## 1. 安装并启动 Ollama

```bash
brew install ollama
brew services start ollama
ollama pull qwen2.5vl:7b
ollama show qwen2.5vl:7b
```

也可以直接在前台运行：

```bash
ollama serve
```

## 2. 配置 Immich

在 Immich 中为需要处理图片的用户创建 API Key。复制示例配置：

```bash
cp .env.example .env
```

编辑 `.env`：

```dotenv
IMMICH_BASE_URL=http://127.0.0.1:2283
IMMICH_API_KEY=替换为真实APIKey
VISION_PROVIDER=ollama
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_MODEL=qwen2.5vl:7b
OLLAMA_CONTEXT_WINDOW=8192
OLLAMA_KEEP_ALIVE=5m
```

`.env` 已被 `.gitignore` 排除，禁止提交真实 API Key。

使用 OpenAI 或兼容服务时，将提供方切换为 `openai`：

```dotenv
VISION_PROVIDER=openai
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_API_KEY=替换为真实APIKey
OPENAI_MODEL=替换为支持图片输入的模型名称
```

本地兼容服务不要求鉴权时，`OPENAI_API_KEY` 可以留空。`OPENAI_BASE_URL` 应包含服务需要的版本前缀，例如 `/v1`。启动连接验证会请求 `{OPENAI_BASE_URL}/models`。

## 3. 构建

```bash
mvn clean test
mvn clean package
```

本机已安装并启动 Ollama 时，可额外执行真实模型契约测试。测试会依次推理程序生成的风光、人像、无人物和低信息图片：

```bash
RUN_OLLAMA_CONTRACT_TEST=true mvn -q -Dtest=OllamaModelContractTest test
```

## 4. 运行

加载环境变量：

```bash
set -a
source .env
set +a
```

正常全量运行：

```bash
java -jar target/immich-tag-gen-by-ai-0.1.0-SNAPSHOT.jar
```

首次建议先对单张图片执行 dry-run：

```bash
java -jar target/immich-tag-gen-by-ai-0.1.0-SNAPSHOT.jar \
  --processing.asset-id=替换为Immich图片UUID \
  --processing.dry-run=true
```

兼容旧脚本的 `force` 参数：

```bash
java -jar target/immich-tag-gen-by-ai-0.1.0-SNAPSHOT.jar \
  --processing.force=true
```

默认以 JSONL 成功历史为准：已成功设置过标签的图片直接跳过，`force` 可绕过历史重新处理。无论是否 `force`，已有任意标签时都跳过模型调用；`force` 不会绕过已有标签保护，也不会覆盖已有描述。

## 配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `IMMICH_BASE_URL` | 无 | Immich 服务地址，必填 |
| `IMMICH_API_KEY` | 无 | Immich 用户 API Key，必填 |
| `VISION_PROVIDER` | `ollama` | 视觉模型提供方：`ollama` 或 `openai` |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` | Ollama 地址，仅 Ollama 模式使用 |
| `OLLAMA_MODEL` | `qwen2.5vl:7b` | 模型名称 |
| `OLLAMA_CONTEXT_WINDOW` | `8192` | 单次推理上下文窗口；大图或扩展词表可提高到 `16384` |
| `OLLAMA_KEEP_ALIVE` | `5m` | 单次推理后模型在 Ollama 中保持加载的时长 |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI 兼容接口地址，仅 OpenAI 模式使用 |
| `OPENAI_API_KEY` | 空 | Bearer API Key；本地免鉴权兼容服务可留空 |
| `OPENAI_MODEL` | 无 | 支持图片输入的模型名称，OpenAI 模式必填 |
| `PROCESSING_PAGE_SIZE` | `100` | Immich 分页大小 |
| `PROCESSING_MAX_RETRIES` | `3` | 连接、429 和 5xx 最大重试次数 |
| `PROCESSING_CONCURRENCY` | `2` | 图片处理并发数，允许 1–8；32GB Apple Silicon 建议从 2 开始 |
| `PROCESSING_HTTP_TIMEOUT_SECONDS` | `30` | Immich HTTP 请求超时 |
| `PROCESSING_MODEL_TIMEOUT_SECONDS` | `300` | 视觉模型调用超时 |
| `PROCESSING_MODEL_RELEASE_INTERVAL` | `50` | 每处理多少张候选图片后主动卸载模型，仅 Ollama 生效 |
| `PROCESSING_CONNECT_TIMEOUT_SECONDS` | `5` | 连接超时 |
| `PROCESSING_CONFIDENCE_THRESHOLD` | `0.65` | 标签置信度阈值 |
| `PROCESSING_PROMPT_VERSION` | `v4` | 提示词协议版本 |
| `PROCESSING_STATE_FILE` | `.data/processing-state.jsonl` | 本地状态文件 |
| `PROCESSING_FORCE` | `false` | 绕过 JSONL 成功历史，重新处理已成功设置过标签的图片；不绕过实时已有标签保护 |
| `PROCESSING_DRY_RUN` | `false` | 是否禁止写回和状态更新 |
| `PROCESSING_ASSET_ID` | 空 | 指定单张图片 UUID |
| `PROCESSING_SKIP_ALBUMS` | 空 | 逗号分隔的相簿名称或相簿 ID，处理每个资产前查询其所在相簿，命中指定相簿的图片直接跳过 |

## 处理规则

1. 启动时验证 Immich、API Key 和所选视觉模型接口。
2. 全量扫描图片并读取实时资产详情；JSONL 中存在成功处理历史的图片直接跳过，`force` 可绕过。已有任意标签时同样跳过模型调用，不覆盖已有标签。
3. 下载 Immich 预览图并调用所选视觉模型；Ollama 模式每处理一批图片后主动卸载模型，避免长期运行时模型内存持续占用。
4. 模型 JSON 必须返回布尔字段 `portraitSubject`；每个标签还必须返回与完整路径一致的父级路径字段 `parentTag`。当主体是清晰可见的人时为 `true`；多人照片只描述最主要或最清晰的主体人物。
5. Java 按置信度、词表路径、去重和 15 标签上限依次过滤，再校验人像规则。`portraitSubject=true` 时，`人脸角度`、`姿态`、`景别`、`服饰类型`、`主体颜色`、`配饰`、`场景`、`拍摄风格` 8 类必须各保留至少一个 `人像/<分类>/<标签>`；否则整张图片处理失败，不写入描述或标签。
6. 必选项无法可靠判断时使用该分类的 `其它`，不得猜测；确认没有明显配饰时使用 `人像/配饰/无配饰`，`其它` 表示存在未收录配饰或无法判断。`portraitSubject=false` 时不得返回任何 `人像/...` 标签。
7. 对每条校验通过的标签路径，先复用完整路径；若不存在，则从最近的已存在父级开始逐层创建。父级节点只维护树结构，资产仅关联每条计划标签的叶子标签 ID。
8. 写回前再次读取实时资产详情；若分析期间已新增任意标签，则放弃本次描述和标签写回。描述只填空白值，不迁移或删除旧 `人物/...` 标签。
9. 单张失败不会终止全局任务，最后输出处理汇总。
10. 配置 `PROCESSING_SKIP_ALBUMS` 后，处理每个资产前通过 Immich 相簿接口查询该资产所在的相簿；命中配置的相簿（按名称或 ID 匹配）则跳过，不调用模型，也不写入本地状态。

JSONL 状态记录图片 `fileModifiedAt`、模型名称、提示词版本、词表版本和处理结果，用于审计与排查；记录 `SUCCESS` 的图片在后续运行中默认跳过，`--processing.force=true` 可绕过。当前默认提示词版本为 `v4`，词表版本为 `3`。修改 `taxonomy.yml` 时应同步递增其中的 `version`。

## 退出码

- `0`：全部成功或成功跳过。
- `1`：配置、连接或启动失败。
- `2`：部分图片处理失败。

## Ollama 上下文窗口

应用会在每次 `/api/chat` 请求中传递 `options.num_ctx`，默认值为 `8192`。如果 Ollama 返回“request exceeds the available context size”，请将 `.env` 中的 `OLLAMA_CONTEXT_WINDOW` 提高到 `16384` 后重试。

若服务端仍固定使用较小的上下文窗口，请将 Ollama 服务的 `OLLAMA_CONTEXT_LENGTH` 调整为不小于应用配置值，并重启 Ollama 服务。

## Immich API 兼容性

客户端使用 Immich 元数据搜索接口分页遍历图片，标签关联优先使用：

```text
PUT /api/tags/{tagId}/assets
```

若服务返回 `404`，会回退到批量接口：

```text
PUT /api/tags/assets
```

单标签关联请求体会同时携带 `ids` 与 `assetIds` 两个字段：Immich v3 起该接口要求 `ids`，旧版本使用 `assetIds`，未知字段会被服务端忽略，因此两个世代均可正常工作。

如果目标 Immich 版本修改了资产分页、缩略图或标签 API，请以该服务版本导出的 OpenAPI 文档为准调整 `ImmichHttpClient`。
