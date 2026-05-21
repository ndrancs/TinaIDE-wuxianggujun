# TinaIDE GitHub Registry

> 更新日期：2026-05-21

TinaIDE 开源版的插件市场与依赖包市场不再从 TinaServer 读取索引。
客户端默认读取公开仓库：

```text
https://github.com/wuxianggujun/TinaIDE-Registry
```

对应 raw 地址：

```text
https://raw.githubusercontent.com/wuxianggujun/TinaIDE-Registry/main
```

## 目录结构

```text
plugins/index.json
plugins/<plugin-id>/<version>/<plugin-id>.tinaplug
packages/index.json
packages/<package-id>/<version>/<file>.tar.xz
```

`download_url` 和 `download_sources[].url` 支持两种写法：

- 绝对 URL：客户端原样访问。
- 相对路径：客户端会拼到 GitHub raw base 后面。

## 插件索引

`plugins/index.json` 的最小结构：

```json
{
  "plugins": [
    {
      "id": "tinaide.plugin.example",
      "plugin_id": "tinaide.plugin.example",
      "name": "Example Plugin",
      "description": "Example plugin",
      "category": "tool",
      "tags": ["tool"],
      "publisher": {
        "id": "tinaide",
        "display_name": "TinaIDE"
      },
      "versions": [
        {
          "version": "1.0.0",
          "version_code": 1,
          "file_size": 1234,
          "file_hash": "sha256:<sha256>",
          "download_url": "plugins/tinaide.plugin.example/1.0.0/tinaide.plugin.example.tinaplug",
          "created_at": "2026-05-21T00:00:00Z"
        }
      ],
      "download_count": 0,
      "rating_avg": 0.0,
      "rating_count": 0,
      "created_at": "2026-05-21T00:00:00Z",
      "updated_at": "2026-05-21T00:00:00Z"
    }
  ]
}
```

`file_hash` 是推荐字段。填写后客户端会做 SHA-256 校验；未填写时只下载，
不做完整性校验。

## 依赖包索引

`packages/index.json` 支持简单结构。下载信息可以直接写在 `linux` 或
`android` 节点里：

```json
{
  "categories": [
    {
      "id": "runtime",
      "name": "Runtime",
      "sort_order": 0
    }
  ],
  "packages": [
    {
      "id": "sdl3",
      "name": "SDL3",
      "description": "SDL runtime package",
      "category": "runtime",
      "android": {
        "version": "3.2.0",
        "install_type": "download",
        "size": 1234,
        "download_url": "packages/sdl3/3.2.0/sdl3.tar.xz",
        "checksum": "sha256:<sha256>",
        "is_latest": true
      }
    }
  ]
}
```

如果一个包需要多版本，也可以使用 `versions` 映射：

```json
{
  "packages": [
    {
      "id": "sdl3",
      "name": "SDL3",
      "category": "runtime"
    }
  ],
  "versions": {
    "sdl3": {
      "android": [
        {
          "id": 2,
          "package_id": "sdl3",
          "platform": "android",
          "version": "3.2.0",
          "install_type": "download",
          "download_size": 1234,
          "download_url": "packages/sdl3/3.2.0/sdl3.tar.xz",
          "checksum": "sha256:<sha256>",
          "is_latest": true
        }
      ]
    }
  }
}
```

客户端会继续保留本地安装状态、下载历史、缓存与插件系统能力。
评论、评分、举报等需要账号系统的互动能力在开源版不可用。
