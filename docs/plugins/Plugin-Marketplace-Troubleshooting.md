# 插件市场排障

> 当前状态：公开仓库只保留 Android 客户端侧插件能力；插件市场索引从 GitHub Registry 读取。

后端容器、数据库、管理后台、部署脚本和生产运维排障资料已经迁入私有仓库，
不再随开源 Android 项目分发。

客户端侧排查时优先确认：

1. 网络请求是否能访问 `wuxianggujun/TinaIDE-Registry` 的 `plugins/index.json`。
2. 插件包的 `download_url` 是否能通过 GitHub raw 或绝对下载地址访问。
3. 本地插件缓存、下载历史和安装目录是否可读写。
4. 开源版账号登录、第三方登录、激活码、会员和官方 AI 额度入口均为移除状态，
   不应再按旧商业版链路排查。

依赖包索引同样读取 GitHub Registry 的 `packages/index.json`。Registry 结构见
[`docs/registry/GitHub-Registry.md`](../registry/GitHub-Registry.md)。

如果问题需要查看服务端日志、数据库、管理后台或部署配置，请在私有后端仓库
中处理。
