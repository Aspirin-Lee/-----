# Notion 只读接入与 Render 部署

## 当前边界
服务端只读接口已实现；尚未部署，没有真实凭据或真实 API 端到端测试。
安卓已提供只读连接页面，填写 HTTPS 根地址和 APP_ACCESS_TOKEN 后读取任务；服务仍需先部署并获得授权。尚未真机验证。
ChatGPT 的 Notion 授权不能自动转移给此服务。
所有自动测试只使用虚构数据。不得把工作区 ID、实验条目或密钥写进此公开仓库。

## 授权
1. 在 Notion 的 https://www.notion.so/profile/integrations 创建个人内部 connection。
2. 仅勾选读取内容能力。在目标页面的连接菜单中添加此 connection；确认其中的实验数据库可访问。
3. 在 Render 的 Environment 中保存 NOTION_TOKEN，而非 GitHub 文件、聊天或 APK。
4. NOTION_DATA_SOURCE_ID 填目标页面内嵌数据库的数据源 ID（不是外层页面 ID，也不是数据库 ID）。
   可使用 Retrieve database API 查询其 data_sources。数据源 ID 由用户在私有部署配置中填写。
5. APP_ACCESS_TOKEN 是手机访问此网关的独立密钥，不能使用 NOTION_TOKEN 代替。

## Render 部署
1. 注册并登录 https://dashboard.render.com/ ，连接自己的 GitHub。
2. 新建 Blueprint，选择当前仓库与 main 分支；Render 读取根目录 render.yaml。
3. 检查选择 Free 计划，填写 NOTION_TOKEN、NOTION_DATA_SOURCE_ID。APP_ACCESS_TOKEN 自动生成。
4. 部署成功后得到 HTTPS 服务地址，访问 /health 仅验证进程存活。
5. 携带 Authorization: Bearer <APP_ACCESS_TOKEN> 访问 GET /experiments 才会验证 Notion 授权。
   首次响应有 nextCursor 时，应继续携带 cursor 参数获取后续页。空列表不等于部署失败。
6. /health 不校验 Notion 凭据。401 是网关密钥不正确；notion_source_not_shared 表示源 ID 或共享权限需检查。
7. Free 实例可能休眠，首次请求需等待唤醒；此服务不使用本地持久化，也不托管原始图片。

## 字段规则
- 自动识别 title 类型字段，名称无需固定为 Name。
- 默认读取“计划日期”的 date.start 和“状态”的 status/select.name，可用环境变量覆盖。
- 没有日期或状态时返回 null；客户端应显示“待安排”，不能使用创建时间推断日期。
- 不写入或更改源数据库；尚未实现实验设计详情、图片下载和双向同步。
- 正式版需增加日程导入、限流/配额、设备凭据管理及真机端到端验证。

## API 与测试
GET /experiments?cursor=...：需认证，返回 items、nextCursor、readOnly。
所有写入方法返回 405。仅配置的数据源可查询，没有任意页面代理。
Node 22，命令 node --test server/notion.test.mjs；GitHub Actions 同时构建 Docker 镜像。
凭据保存在运行环境中；错误响应不透传 Notion 原始响应，服务不记录实验正文。
