# x²k点歌
<img width="1672" height="941" alt="6f6b1463f8b660d3c8a969b2fd845acf" src="https://github.com/user-attachments/assets/ede225b8-d834-4347-a6b0-d427fb7a15fd" />


`x²k点歌` 是一个 Fabric 1.20.1 的 Minecraft 在线点歌 mod。它使用房主或服务器本机保存的网易云登录凭证获取播放地址，然后把同一首歌同步给所有安装了 mod 的玩家播放。

> 请只使用你自己合法拥有和有权使用的网易云账号凭证。不要把 `MUSIC_U`、完整 Cookie 或 `config/online_music.json` 上传到公开仓库。

## 功能特性
<img width="2559" height="1599" alt="cee685a61d9eeff99a14b204ea6fac19" src="https://github.com/user-attachments/assets/74a82b30-4abe-4d58-b0f1-4d00016b11ba" />

- 全局一起听：一名玩家点歌后，所有开启个人收听的玩家同步播放同一首歌。
- 房主凭证：网易云登录凭证只保存在房主或服务器本机，不会发给其他玩家。
- 点歌面板：输入 `/gmusic` 打开 GUI，可搜索歌曲、翻页、点歌、查看队列和播放器状态。
- 聊天命令：支持 `/gmusic s 歌名` 搜索，`/gmusic list 1` 选择搜索结果，也支持 `/gmusic play 歌曲ID` 直接点歌。
- 队列播放：播放中再次点歌会加入队列，不会直接切掉当前歌曲。
- 队列管理：支持暂停、继续、上一首、下一首、置顶排队歌曲、删除排队歌曲。
- 播放进度：点歌面板和右侧 HUD 都会显示当前播放进度。
- 歌词显示：物品栏上方显示当前一行歌词，可开启或关闭上飞动效。
- 右侧 HUD：刚换歌和快切歌时显示当前歌曲、专辑封面和队列；平时显示专辑封面、进度条和歌词。
- 个人开关：玩家可以关闭个人收听，只让别人继续听。
- 音量控制：`x²k设置` 面板可调点歌音量，并跟随 Minecraft 主音量和音乐音量。
- 原版音乐处理：播放点歌时会停止 Minecraft 自带背景音乐。

## 运行环境

- Minecraft `1.20.1`
- Fabric Loader `0.15.0` 或更高
- Fabric API，建议使用 `0.92.8+1.20.1`
- Java `17`
- 客户端和服务端都需要安装本 mod

项目当前版本见：

```text
gradle.properties
```

当前构建产物默认位于：

```text
build/libs/online-music-mod-1.0.22.jar
```

## 安装部署

### 单人游戏或房主开局域网

1. 安装 Fabric Loader `1.20.1`。
2. 把 Fabric API 放入 `.minecraft/mods/`。
3. 把 `online-music-mod-版本号.jar` 放入 `.minecraft/mods/`。
4. 启动游戏，进入世界。
5. 房主在游戏内配置网易云登录凭证。

### Fabric 服务端

1. 准备 Fabric 1.20.1 服务端。
2. 把 Fabric API 放入服务端 `mods/`。
3. 把 `online-music-mod-版本号.jar` 放入服务端 `mods/`。
4. 所有需要听歌和看 UI 的玩家，也需要把同一个 jar 放入自己的客户端 `mods/`。
5. 启动服务端。
6. 由房主或 OP 在服务端本机配置网易云登录凭证。

## 配置网易云登录凭证

推荐只提供 `MUSIC_U`，也可以提供完整 Cookie。mod 会自动提取 `MUSIC_U` 并保存。

`/gmusic token set ...` 已禁用，不能再通过聊天框提交 `MUSIC_U` 或完整 Cookie。原因是 Minecraft 输入框有长度限制，完整 Cookie 很容易被截断；同时聊天框也不适合输入敏感登录凭证。

请统一使用本地文件读取方式。

1. 在游戏内输入：

```text
/gmusic token path
```

2. mod 会创建并显示本地文件路径，默认是：

```text
config/x2k_netease_cookie.txt
```

3. 把下面任意一种内容放进这个文件：

```text
MUSIC_U=你的值
```

或：

```text
完整网易云Cookie
```

4. 保存文件后，在游戏内输入：

```text
/gmusic token load
```

5. 再检查登录和 VIP 状态：

```text
/gmusic token check
```

### 凭证保存位置

mod 最终会把凭证保存到服务器或房主本机：

```text
config/online_music.json
```

这个文件包含敏感登录信息，不要发给别人，也不要提交到 GitHub。

## 基础使用
<img width="2559" height="1599" alt="91d20ae9161639e6c90779ae28938aaa" src="https://github.com/user-attachments/assets/2bd4e082-3b5f-4a52-ba10-d3a299ed3f22" />

### 打开点歌面板

```text
/gmusic
```

面板里可以搜索、上一页、下一页、点歌、查看歌曲列表、控制播放和打开 `x²k设置`。

### 搜索歌曲

```text
/gmusic s 歌名
```

示例：

```text
/gmusic s Blank Space
```

搜索结果默认显示前 5 首。

### 选择搜索结果

```text
/gmusic list 1
```

这会选择你自己刚搜索出来的第 1 首。每个玩家的搜索结果互不影响，点歌成功后本次 `/gmusic list 编号` 会失效，需要重新搜索。

### 直接用歌曲 ID 点歌

```text
/gmusic play 歌曲ID
```

搜索结果里也可以点击 `[点歌]`，聊天框会自动填入 `/gmusic play 歌曲ID`。这种方式不依赖本次搜索结果，可以一直点歌。

### 搜索翻页

聊天命令：

```text
/gmusic next
```

点歌面板里有上一页和下一页按钮。

## 播放和队列管理

查看当前歌曲和队列：

```text
/gmusic list
```

查看当前播放：

```text
/gmusic now
```

暂停全局播放：

```text
/gmusic pause
```

继续全局播放：

```text
/gmusic resume
```

下一首：

```text
/gmusic skip
```

上一首：

```text
/gmusic prev
```

把队列里的第 2 首置顶为下一首：

```text
/gmusic top 2
```

删除队列里的第 2 首：

```text
/gmusic remove 2
```

停止播放并清空队列，只有 OP 可以执行：

```text
/gmusic stop
```

## 个人收听和 HUD

关闭个人收听：

```text
/gmusic off
```

恢复个人收听：

```text
/gmusic on
```

打开或关闭物品栏上方歌词：

```text
/gmusic lyrics on
/gmusic lyrics off
```

打开或关闭歌词动效：

```text
/gmusic lyrics anim on
/gmusic lyrics anim off
```

打开或关闭右侧 HUD 面板：

```text
/gmusic panel on
/gmusic panel off
```

也可以在 `/gmusic` 面板右下角打开 `x²k设置`，用按钮和滑块调整：

- 点歌音量
- 歌词显示
- 歌词动效
- 右侧面板
- 个人收听

## 字体和界面资源

当前版本歌词和面板使用 Minecraft 默认方块字体，字体资源定义在：

```text
src/main/resources/assets/online_music/font/lyrics.json
```

项目中如果存在本地测试字体，例如：

```text
src/main/resources/assets/online_music/font/ydbth.ttf
```

它不会被打进 jar，也不建议提交到公开仓库。

## 从源码构建

Windows PowerShell：

```powershell
.\gradlew.bat clean build
```

Linux 或 macOS：

```bash
./gradlew clean build
```

构建完成后，jar 在：

```text
build/libs/
```

只需要把非 `sources` 的 jar 放到客户端和服务端 `mods/`。

## 常见问题

### `/gmusic token check` 显示没有登录

重新复制当前浏览器或网易云客户端的 `MUSIC_U`，放入 `/gmusic token path` 显示的本地文件，然后执行 `/gmusic token load`。不要使用聊天框提交凭证。

### VIP 歌曲只能播放一小段

通常是网易云没有识别到当前 Cookie 的登录或 VIP 状态。先执行：

```text
/gmusic token check
```

如果仍然显示未登录或 VIP 未识别，更新本地凭证文件里的 `MUSIC_U` 或完整 Cookie，然后重新执行 `/gmusic token load`。

### 游戏声音关了还有点歌声音

当前点歌音量会跟随 Minecraft 主音量和音乐音量，同时还有 mod 自己的点歌音量滑块。请检查：

1. Minecraft 设置里的主音量。
2. Minecraft 设置里的音乐音量。
3. `/gmusic` 面板右下角 `x²k设置` 里的点歌音量。

### 退出地图后还在播放

客户端离开世界时会停止本地点歌播放器。如果仍然听到声音，请确认客户端安装的是最新构建的 jar。

### 搜索结果点不了或串了

搜索结果按玩家分别保存，互不串。`/gmusic list 1` 只能选择你自己刚搜索出来的结果，点歌成功后会清空本次结果，需要重新搜索。

## 开发说明

- mod id：`online_music`
- 主入口：`com.xkitme.onlinemusic.OnlineMusicServer`
- 客户端入口：`com.xkitme.onlinemusic.OnlineMusicClient`
- 播放器实现：`OnlineMusicPlayer`
- 网易云接口：`NeteaseMusicService`
- 全局队列：`GlobalMusicManager`
- 点歌面板：`MusicPanelScreen`
- 右侧 HUD 和歌词：`MusicHud`

修改功能后请同步更新版本号：

```text
gradle.properties -> mod_version
```
