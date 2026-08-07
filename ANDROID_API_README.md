# Gallery 本地 API 服务器（魔改说明）

本文档说明如何在 Google AI Edge Gallery App 上启用「对外 OpenAI 兼容 API」功能，
让电脑 / 局域网内的程序通过 HTTP 调用手机 / 平板上加载的本地离线模型。

## 这个魔改做了什么

项目原本把模型推理完全跑在设备本地，App 不对外提供任何 HTTP 接口。
本魔改新增了一个 **OpenAI 兼容的本地 HTTP 服务器**，让外部客户端可以这样调用本地模型：

```
POST http://<手机局域网IP>:8080/v1/chat/completions
```

支持 OpenAI 的两种调用方式：
- 普通（一次性返回）：`stream: false`
- 流式 SSE：`stream: true`（边推理边吐字）

任何兼容 OpenAI 的工具都能直接连：`curl`、Python 的 `openai` 库、各种聊天客户端等。

## 改动清单

| 文件 | 作用 |
| --- | --- |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/api/OpenAiApiServer.kt` | 新增。核心 HTTP 服务器（基于 NanoHTTPD），实现 `/v1/chat/completions`、`/v1/models`、`/health` |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/api/ApiServerViewModel.kt` | 新增。Compose 用的 ViewModel，封装 server 的启停和状态 |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt` | 在聊天页顶栏接入 API 开关 + snackbar 反馈 |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/ModelPageAppBar.kt` | 在顶栏 actions 区加「链接」图标按钮（点亮=运行中） |
| `Android/src/app/src/main/res/values/strings.xml` | 新增几条 UI 文案 |
| `Android/src/gradle/libs.versions.toml` | 新增 NanoHTTPD 依赖 |
| `Android/src/app/build.gradle.kts` | 引入 NanoHTTPD |

## 工作原理（重要概念）

关键设计：**服务器不自己初始化模型，而是复用 App 里已经跑起来的模型会话。**

- 用户先在聊天界面下载并初始化一个模型（比如 Gemma）。
- 点顶栏的「链接」图标，服务器启动，并把当前这个已初始化的模型设为对外服务对象。
- 外部请求进来时，服务器调用同一个 `AgentRuntimeExecutor`（与 App 内聊天共用），
  把 `AgentEvent.StreamToken` 流式转发成 SSE 返回。

这意味着：
1. **必须先**在 App 里把模型跑起来（下载 + 初始化），点开关才有意义。
2. 同一时刻只能有一个推理在跑（本地 runtime 不支持并发多轮），并发请求会返回 409。
3. 服务器只服务「当前选中的那个模型」。

## 使用步骤

### 编译
按项目官方 `DEVELOPMENT.md` 用 Android Studio 打开 `Android/` 目录，配置好 HuggingFace
OAuth 凭据后正常构建即可（新增的 NanoHTTPD 依赖会自动从 Maven Central 拉取，无需额外配置）。

### 运行
1. 手机和电脑连同一个 Wi-Fi（局域网）。
2. 打开 App → 进入 AI Chat → 下载并初始化一个模型。
3. 点顶栏的「链接」图标（图选址在历史图标旁边）。
4. 启动成功会弹出 snackbar，显示服务器地址，例如：
   `Local API server started at http://192.168.1.5:8080`
5. 在电脑上测试：

```bash
# 列出可用模型
curl http://192.168.1.5:8080/v1/models

# 普通调用（一次性返回）
curl http://192.168.1.5:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma","messages":[{"role":"user","content":"你好，介绍一下你自己"}],"stream":false}'

# 流式调用（SSE，边推理边吐字）
curl -N http://192.168.1.5:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gemma","messages":[{"role":"user","content":"讲个笑话"}],"stream":true}'
```

用 Python `openai` 库（把 base_url 指向手机）：

```python
from openai import OpenAI

client = OpenAI(base_url="http://192.168.1.5:8080/v1", api_key="not-needed")
resp = client.chat.completions.create(
    model="any",  # 服务器只认当前激活的模型，这个字段被忽略
    messages=[{"role": "user", "content": "你好"}],
)
print(resp.choices[0].message.content)
```

6. 再点一下「链接」图标即可关闭服务器。

## 常见问题

- **点了开关提示 "Failed to start local API server"**：通常是端口 8080 被占用或没网络权限。
  代码里默认端口是 8080，可改 `OpenAiApiServer.kt` 的 `startServer(port = 8080)`。
- **"No active model"**：模型还没初始化。先进聊天界面确保模型下载并跑起来。
- **"Another request is already in progress"**：本地 runtime 一次只能跑一个推理，等上一个完成。
- **手机能访问但电脑不行**：确认同一局域网、路由没开 AP 隔离；服务器监听在 0.0.0.0（默认）。
- **`multimodal`（图片/音频）暂不支持**：当前只处理纯文本 `user` 消息，取最后一条作为 prompt。

## 已知限制 / 后续可扩展

- 目前只取最后一条 user 消息作为 prompt，**没有把多轮对话上下文回传给模型**。
  要做多轮保留，需要把 `messages` 数组转成 LiteRT 的 `Message` 列表传给 executor。
- `temperature` / `max_tokens` 参数当前被读取但未真正应用（本地模型用自带配置）。
- 可通过 `model` 字段 + 多模型管理扩展成「按模型名路由」。