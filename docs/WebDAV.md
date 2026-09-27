# WebDAV 多端同步

「流光褶皱」在原版 OK 影视（FongMi/TV）基础上新增了 **WebDAV 多端同步**能力：只要在各个客户端填好同一套 WebDAV 设置，点播源配置、App 设置、收藏、观看历史与播放进度就会在多台设备之间自动保持一致。

## 一、需要同步的内容

| 内容 | 说明 |
| --- | --- |
| 点播/直播/壁纸配置 | 配置地址、名称、当前使用的线路、Home 站点等 |
| App 设置 | 播放器、解码、字幕、弹幕、壁纸、DoH、无痕等所有偏好设置 |
| 收藏 | 影视收藏、直播收藏 |
| 观看历史 | 观看记录 |
| 播放进度 | 每一集的播放位置、时长、片头片尾、倍速、缩放、倒序/倒播等 |

> 播放进度是「观看历史」条目的一部分（History.position / duration），因此历史与进度是一起同步的。

## 二、配置方式

设置 → **WebDAV 同步** 打开配置面板：

- **服务地址**：WebDAV 根目录地址，例如坚果云 `https://dav.jianguoyun.com/dav/`
- **账号**：登录邮箱 / 用户名
- **密码/密钥**：WebDAV 的应用密钥（坚果云在「账户信息 → 安全选项 → 添加应用」里生成）
- **文件夹**：存放同步文件的目录名，例如 `okmove`（不存在会自动逐级创建）
- **启用同步**：总开关
- **数据变化时自动同步**：播放/收藏发生变化后自动延迟同步（默认开启），冷启动也会自动同步一次

同步文件固定为该目录下的 `sync.json`，普通 JSON 文本，方便自行备份或排查。

### 扫码 / 二维码

手机端提供 **扫码导入**：点击扫一扫，扫描其他设备生成的二维码即可一键填入完整 WebDAV 配置。

任何一端都可以点击 **生成二维码** 把当前配置画成二维码，让另一台设备扫码导入（TV 端没有摄像头，就用这个功能给手机扫）。

二维码内容为如下 JSON：

```json
{"type":"webdav","url":"https://dav.jianguoyun.com/dav/","user":"xxx","pass":"<Base64>","folder":"okmove"}
```

## 三、同步模式

面板底部提供三种手动操作：

- **立即同步**（推荐）：双向合并，本机改动上传、云端改动下载，冲突自动处理
- **上传本机（覆盖云端）**：以本机数据为准，覆盖云端快照
- **下载云端（覆盖本机）**：以云端数据为准，覆盖本机数据（等同于恢复）

## 四、合并策略

「立即同步」采用**三方合并**（本地快照 + 上一次同步的基线 + 云端快照）：

1. 本机有改动、云端没变 → 保留本机，上传
2. 云端有改动、本机没变 → 采用云端，写入本机
3. 两边都有改动 → 冲突，按类型裁决：
   - 观看历史：取最近观看时间较新的那条，时间相同取播放进度更靠后的
   - 收藏：取收藏时间较新的那条
   - 点播/直播/壁纸配置：取更新时间较新的那条
   - App 设置：以本机为准
4. 某条数据在任一端被删除 → 删除会同步到另一端

新设备安装后只要填好 WebDAV 设置并执行一次「立即同步」，即可一次性恢复全部配置、收藏与进度。

> 单次同步数据量超过 6MB 时会丢弃配置里的原始 JSON 缓存（联网会重新拉取），避免同步文件过大。

## 五、实现位置

- `app/src/main/java/com/fongmi/android/tv/webdav/WebDavSetting.java`：配置读写
- `app/src/main/java/com/fongmi/android/tv/webdav/WebDav.java`：WebDAV 客户端（MKCOL / GET / PUT）
- `app/src/main/java/com/fongmi/android/tv/webdav/WebDavData.java`：同步数据结构
- `app/src/main/java/com/fongmi/android/tv/webdav/WebDavCode.java`：二维码载荷编解码
- `app/src/main/java/com/fongmi/android/tv/webdav/SyncManager.java`：三方合并、自动调度、配置重载
- `app/src/main/java/com/fongmi/android/tv/utils/QrHelper.java`：二维码生成
