# 肥鱼之声 (Yinxia)

一个极简的本地音乐播放器：扫描手机里的音频、列表播放、通知栏/锁屏控制、后台播放。
没有网络权限，代码里没有任何联网逻辑，所有数据都在你手机上。

> **先说实话：这份代码我没有编译过。**
> 我这边的执行环境 shell 是坏的（`0xC0000142`，DLL 初始化失败），装不了 JDK/Android SDK，也没法跑 Gradle。
> 我已经做了能做的人工核对（版本兼容矩阵、资源引用、跨文件调用、图标路径），但**第一次构建仍可能需要 1–2 轮修错**。
> 报错日志贴给我，我来改。

---

## 一、它有哪些功能

| 功能 | 说明 |
|---|---|
| 音乐库列表 | 封面、标题、歌手 · 专辑、时长；正在播的那一行高亮 |
| **点正在播的那一行** | 暂停 / 继续（位置保留），不会从头重播；点别的行才从头开始播那一首 |
| 搜索 | 按标题 / 歌手 / 专辑过滤 |
| **排序方式** | 名称（A→Z / Z→A）、添加时间（新→旧 / 旧→新）、手动排序（长按拖动）；选择会被记住 |
| **扫描范围** | 只扫描你勾选的文件夹（按文件夹粒度），同样会被记住 |
| **删除歌曲** | 长按进入多选，可批量删除；Android 11+ 走系统确认框 |
| **歌曲详细信息** | 顶栏信息图标切换：歌曲行是否多显示两行技术信息（第一行 格式 · 码率 · 采样率，第二行 文件大小）；默认关闭 |
| **歌单** | 自建歌单：上方 chip 行筛选，长按歌曲多选后加入 / 移出；只存歌曲 id，存在本地 JSON 里，没有数据库 |
| 迷你播放条 | 底部常驻，带进度条，点一下展开全屏播放页 |
| 全屏播放页 | **黑胶唱片封面**（播放时匀速旋转、暂停停在原角度、唱臂搭上/抬起）、可拖动进度条、上一首 / 播放暂停 / 下一首 / 随机 / 循环 |
| 循环三态 | 关 → 列表循环 → 单曲循环 |
| 通知栏 & 锁屏控制 | 走 Media3 的 MediaSession，系统媒体卡片可用 |
| 蓝牙 / 耳机按键 | 上一首下一首、播放暂停由系统接管 |
| 拔耳机自动暂停 | 不会突然外放 |
| 后台播放 | 前台服务 + `mediaPlayback` 类型，划掉最近任务也不会断 |
| 深色 / 浅色模式 | 跟随系统；强调色跟随当前歌曲封面，没有封面时用自选的默认色，也可一键改成壁纸取色 |
| **主题色跟随封面** | 从当前歌曲封面里提主色，播放按钮 / 进度条 / 正在播的那一行 / 顶栏与迷你条图标跟着变；换歌 600ms 渐变 |
| **默认主题色可自定义** | 10 色预设面板（顶栏调色板图标打开）；只在歌曲没有封面时生效，存的是"种子色"，浅色/深色各自调整明暗 |
| **桌面插件（可调大小）** | 封面 + 标题 + 歌手 + 上一首 / 播放暂停 / 下一首，点一下打开 App；可横向 / 纵向自由拉伸；**背板颜色随封面主色变**（透明度不变），封面带圆角 |
| 零外部 UI 依赖 | 图标全是自己画的矢量图，没有引 `material-icons-extended`、没有引图片加载库 |

**三个新功能的具体行为：**

- **排序**：右上角「排序」图标。名称/时间各有升序和降序两个选项，手动排序是第三个。
  选「手动排序」会**进入拖动模式**：顶部变成「拖动调整顺序 + 完成」，此时**长按任意一行上下拖动**即可调整，
  拖到屏幕边缘会自动滚动。点「完成」退出，列表恢复普通形态，**行内不会留下任何箭头或把手**，
  歌曲信息占满可用宽度。手动顺序在松手时就已经存好了，即使直接杀掉 App 也不会丢。
  > 为什么用长按而不是直接拖：直接拖会和列表本身的滚动抢手势，长按是区分两者的标准做法。
- **扫描范围**：右上角「扫描范围」图标。列出所有含音频的文件夹及各自歌曲数，勾选即生效。
  默认全部勾选（等于不限制）；取消任意一个才真正生成白名单。全部勾回来自动恢复成不限制。
- **删除**：长按任意一行进入多选，顶部出现「全选 / 删除」。删除前 App 会确认一次，
  Android 11+ 系统还会再弹一次确认框（`MediaStore.createDeleteRequest`），**这是系统强制的，App 自己删不掉**。
  删除时会同步把这首歌从播放队列里移除。

**点正在播的那一行：**

- 再点一次当前正在播放的那首歌**不会从头重播，而是暂停 / 继续**，播放位置原样保留；
  点别的歌才是把那一首从头开始播。判断在 `PlayerViewModel.onSongSelected(song)` 里：
  点中的就是当前这首就调 `togglePlayPause()`，否则才调 `playSong(song)`；`App.kt` 里接的是
  `onSongClick = viewModel::onSongSelected`。
  > 之前点当前行也会走 `playSong`，等于把队列和播放位置整个重设一遍 —— 想暂停却听到从头开始，很反直觉。
  > 现在"点当前行 = 暂停 / 继续"和迷你条、通知栏上的播放暂停键是同一个入口（`togglePlayPause`），行为一致。

**主题色与桌面插件：**

- **主题色跟随封面**：正在播放的歌曲有封面时，App 从封面里挑一个颜色当强调色
  （`data/ArtworkLoader.kt` 里自己写的直方图取色：缩到 24×24 统计、量化成 4 位/通道的桶、
  按「像素数 × (0.25 + 平均饱和度)」打分，并且丢掉接近黑/白/灰的像素）。
  **没有引 Palette，也没有为它加任何依赖。** 覆盖的只有 `primary` / `onPrimary` /
  `primaryContainer` / `onPrimaryContainer` 四个色位（`ui/theme/AccentTheme.kt`），
  所以播放按钮、进度条、正在播的那一行、顶栏和迷你条的图标跟着封面走，
  而背景和正文仍是原来那套配色。换歌时颜色用 600ms 交叉淡入淡出，不会硬切。
  > 为什么只改四个色位：Material 的色位有几十个，全按封面重算，界面会变成一锅花里胡哨的颜色，
  > 正文和背景的对比度也没法保证。只动"强调"相关的四个，效果明显又不会失控。
- **默认主题色可自定义**：歌曲没有封面（或者根本没在播放）时，退回用户选的默认色。
  顶栏的调色板图标打开底部面板（`ui/ThemeSheet.kt`），里面是 10 个固定预设（`ui/theme/AccentPalette.kt`）。
  面板里存的其实是**种子色**，真正用的时候才由 `accentForTheme()` 按当前明暗主题调整：
  饱和度抬到至少 0.45，亮度在深色主题夹进 0.72–0.92、浅色主题夹进 0.40–0.55 ——
  所以同一个种子色在深浅两种主题下都好看，不用存两份。
  > 为什么是固定预设而不是取色轮：预设能保证每个颜色在两种主题下都好看；
  > 随便取色很容易取到发灰、在深色下看不见的颜色。
  另外，**跟随系统深色模式这件事完全没有变**：用哪种主题仍然由 `isSystemInDarkTheme()` 决定，
  变的只是强调色本身会按当前主题调整明暗。
- **桌面插件**：`widget/PlayerWidgetProvider.kt`（接收器 + 按钮处理）和 `widget/PlayerWidget.kt`
  （拼 `RemoteViews` 并推给桌面）。布局有**两套**：`res/layout/widget_player.xml`（横向，高度不够时用，也就是 3x1 / 4x1）
  和 `res/layout/widget_player_square.xml`（纵向：封面、标题、歌手，然后一行均匀铺开的控制按钮，用于 2x2 这类高度够的尺寸）。
  声明 `res/xml/player_widget_info.xml`。
  显示封面 + 标题 + 歌手 + 上一首 / 播放暂停 / 下一首，**只有封面图片区域**能点开 App（原因见下面那条），
  `resizeMode="horizontal|vertical"` 所以可以自由拉伸。下面的实现细节值得记一下：
  - **插件只能用 RemoteViews + XML 布局，不能用 Compose。** 插件的界面跑在桌面的进程里，
    App 做的只是"把一段构建好的 RemoteViews 推给系统"，Compose 的运行时根本不在那边。
  - **两套布局在运行时选，用户拉伸时会重算。** `PlayerWidget.layoutFor(...)` 读
    `AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH/MIN_HEIGHT`，**按高度**决定用哪一套：
    `OPTION_APPWIDGET_MIN_HEIGHT >= 100`（或者宽度不到 180dp）用竖排，否则用横排；
    `PlayerWidgetProvider.onAppWidgetOptionsChanged` 在尺寸变化时重新渲染一次。
    两套布局**故意共用同一批 view id**，所以推送代码不需要按布局分叉。
    > 为什么从宽高比改成高度：同一个 2x2 在不同启动器上上报的宽高差得很远，算出来的比例一会儿横一会儿竖；
    > 而"高度不够，竖排的封面 + 文字 + 按钮就是叠不下"是确定的、跟启动器无关的问题。
  - **竖排布局的封面是固定的 56dp 正方形，多余高度由两个可伸缩的 `View` 吸收。**
    `res/layout/widget_player_square.xml` 里封面写的是 `56dp` 正方形 + `scaleType="centerCrop"`，
    上下各放一个 `<View android:layout_weight="1" />` 平分剩下的高度，
    所以插件被拉高拉矮，封面形状都不变，2x2 也不会被裁掉内容。
    > 这里改掉过一个真实的 bug：封面以前是 `layout_height="0dp"` + `weight="1"`，
    > 正方形封面被撑成了一条窄窄的竖条，看起来就是"被拉长了"。
    > 注意 `android.widget.Space` 不能出现在 RemoteViews 里，占位只能用普通 `View`。
  - **正方形尺寸被正式支持了。** `res/xml/player_widget_info.xml` 的 `minResizeWidth` 从 140dp 降到 **110dp**，
    所以 2x2 这种接近正方形的尺寸不再算"擦边"，是声明里就允许的尺寸。
  - **背景是半透明的，只留一圈发丝描边。** 由 `widget_background.xml` 配合
    `widget_background` / `widget_outline` 两个颜色实现；这两个颜色都带 alpha，
    并且 `values` 与 `values-night` 的取值不同。
  - **背板颜色跟随封面主色（透明度不变）。** RemoteViews 没有任何"改 shape 颜色"的通道，
    动态上色只能靠位图：两套布局的根都换成了 FrameLayout，最底下垫一个 backdrop
    `ImageView`（fitXY），`PlayerWidget.backdropFor(...)` 按插件实例的尺寸画一张
    圆角矩形位图 —— 填充色取自封面主色（`ArtworkLoader.cachedDominantColor`，同步只查内存缓存），
    **alpha 直接沿用颜色资源里的原值**（浅色 0xD9 / 深色 0xCC，所以不透明度和以前完全一样），
    主色亮度先夹进 0.20–0.60 免得近黑/近白的封面把插件变成黑板/白板。圆角半径和描边宽度
    按"位图边长 / 实际 dp"等比换算，fitXY 拉回去视觉尺寸不走样。深浅模式进了推送签名，
    系统切换深浅色后下一次推送就会换对应那套底色/描边。
  - **封面有圆角了，占位图也有。** 以前 `coverFor` 直接 `createScaledBitmap(192,192)`，
    而系统 `loadThumbnail` 返回的图**不保证正方形**（保持封面原始比例），硬拉伸把封面压变形、
    四角圆弧也被拉成椭圆 —— 这就是"封面看着没圆角、很锐利"的根因。现在统一走
    `roundedSquare(...)`：先按 centerCrop 裁正、再缩到 192px、再切圆角（约 22%）；
    占位图层资源本身是直角的，也预渲染成同一张圆角位图（只做一次），
    两条路的观感一致。
  - **更新由 App 主动推，并且按签名去重。** 签名是「歌曲 id | 标题 | 歌手 | 是否在播 | 封面位图是否真的拿到了」，
    和上次一样就直接返回，所以每秒两次的进度刷新不会反复刷插件。
    > 这里修掉过一个真实的 bug：签名以前不含"封面是否可用"，换歌的瞬间封面还没解码完就先推了一次，
    > 桌面收到的自然是占位图；而之后签名再也没变过，于是**永远不会有第二次推送**，
    > 插件上的封面就一直是那张音符占位图。把封面可用性并进签名，封面解码完成后才会补上那一次推送。
  - **按钮点击**经由 `PlaybackConnection` 连到 `MediaSessionService` 真正控制播放。
    **局部更新已经被删掉了**：以前播放/暂停只用 `partiallyUpdateAppWidget` 换掉图标那一个控件
    （`PlayerWidget.updatePlayState`），现在每个插件命令都走 `updateAll` 整块重画。
    > 为什么删：局部更新会重建那一个 view，而在部分启动器（MIUI）上重建后的 view 会丢掉自己的点击
    > `PendingIntent` —— 这正好对上用户报的"暂停能按，紧接着按播放就没反应"。
    > 整块重画会把每个 `PendingIntent` 重新注册一遍，比换一个图标贵一点，但按钮一定可点。
  - **"按钮没反应"的第三个根因：插件命令以前是借一个临时 `MediaController` 发的。**
    `PlayerWidgetProvider` 收到点击后自己 bind 一个 controller，执行完命令再多等 600ms 才 release
    （`withConnection`）。App 已经在后台时，它自己的 controller 早就没了，
    于是这个临时 controller 就是**最后一个** controller；一 release，MediaSession 的 controller 数归零，
    播放服务和整个 App 进程会被一起拆掉。用户报的现象正是这样：
    **按暂停，音乐确实停了，然后音乐就死了，再按别的键全都没反应，重新打开 App 是冷启动。**
    > 这和以前那两个根因不一样：命令本身是送到了、也生效了，坏的是"发命令的人顺手把服务带走了"。
  - **修法：正在跑的播放服务自己处理插件点击，全程在本进程内。**
    `PlaybackService` 在 `onCreate` 里注册一个动态 `BroadcastReceiver` 监听
    `ACTION_TOGGLE` / `ACTION_NEXT` / `ACTION_PREV`（`onDestroy` 里注销），
    直接操作 `mediaSession.player`，再把真实状态用 `PlayerWidget.pushState(...)` 推回插件。
    `PlayerWidgetProvider` 仍然先乐观地把播放/暂停图标翻一下（点击要立刻有反馈），
    只有当 `PlaybackService.isRunning == false`（根本没有服务在跑，比如刚重启过、或者队列已经空了）
    才退回连 controller 的那条老路。
  - **同一轮修掉的第二个 bug：`PlaybackService.onTaskRemoved` 以前只要"没在播"就 `stopSelf()`。**
    所以在**暂停**状态下把 App 从最近任务里划掉，服务和进程当场就没了，之后再点插件等于对着空气说话。
    现在只有队列本身是空的时候（`player.mediaItemCount == 0`）才停，
    暂停中的播放器会把服务和通知一起留着 —— 这和主流播放器行为一致，
    也是"划掉 App 之后插件还能控制"的原因。
  - **播放队列能在进程被杀之后自己回来。** 以前队列只活在内存里，App 被划掉后插件按按钮等于对**空队列**下命令，
    表现就是"插件上的按钮全都没反应"。现在：`LibraryPreferences` 多了 `queueSongIds` + `queueIndex` 两个字段，
    `PlayerViewModel.playSong` / `persistQueueIndex()` 负责写入；`Song -> MediaItem` 的转换挪到了
    `player/MediaItems.kt`，让 App 和插件共用同一份；`widget/QueueRestore.kt` 在插件执行命令之前，
    一旦发现 `controller.mediaItemCount == 0`，就按存下来的 id 顺序把队列重建出来（已经不在库里的歌直接跳过）。
  - **"点插件打开 App" 的 `PendingIntent` 只挂在封面的 `ImageView` 上**，不再挂在根布局视图上。
    > 这是针对"按钮点了没反应"的一个**推断性修复**（没有在 MIUI 上实测过）：挂在根布局时，
    > MIUI 的桌面容易把根视图的点击当成整个插件的点击，顺手把子按钮的点击一起吃掉；
    > 移到封面之后，上一首 / 播放暂停 / 下一首这三个按钮的点击才有机会正常送达。
  - **为什么这轮值得记**：这是"插件上的按钮没反应"找到的**第三个**根因，
    和前两个不同，它有一个能观察到的症状（App 冷启动）做证据，不是猜的。

**黑胶唱片（播放页）：**

- 播放页的封面做成了黑胶：黑胶底盘（带沟槽纹理）+ 圆形封面 + 中心孔一起随播放匀速旋转
  （一圈 20 秒，`NowPlaying.kt` 的 `VinylRecord`），右上角一根唱臂，播放时搭上盘面、暂停时抬起。
- 角度由 `Animatable` 持有，动画循环写在 `LaunchedEffect(isPlaying)` 里：
  暂停时协程被取消、**角度冻结在当前位置**，继续播放从同一角度接着转，
  和真实唱机"停针再放"的手感一致。每转完一圈把角度收回 0–360，
  避免长时间播放后 Float 精度不够、旋转开始抖动。
- 全部用 Compose Canvas 自绘（底盘、沟槽、唱臂），没有加任何图片资源或依赖；
  封面复用 `Artwork` 组件（裁剪参数从"圆角半径"泛化成了 `Shape`，列表仍是圆角矩形，这里是圆形）。

**歌曲详细信息与歌单：**

- **歌曲详细信息开关**：顶栏多了一个信息图标（`ic_info`），点一下切换歌曲行是否多显示两行技术信息。
  这两行由 `util/SongDetails.kt` 的 `songDetailLines(song)` 生成，返回一个 `List<String>`：
  第一行是 `格式 · 码率 · 采样率`（例如 `FLAC · 320 kbps · 44.1 kHz`），第二行是文件大小（例如 `8.4 MB`）；
  一个字段都拿不到时返回空列表，那两行就都不画。
  开关存在 `LibraryPreferences.showSongDetails`（默认 false），一路暴露成
  `LibraryUiState.showSongDetails` / `PlayerViewModel.setShowSongDetails`。
  > 默认关掉，是因为用户明确说过喜欢现在这种干净的样子。
  > **拆成两行**是因为格式、码率、采样率、文件大小全塞在一行里，在手机上会被截断。
  > 另外**不是所有版本都能拿到全部字段**：`MediaStore` 从 **Android 11（API 30）** 起才提供码率，
  > 采样率更要 **Android 16（API 36）** 才有；老系统上查询直接不投影这两列，
  > 第一行就只显示拿得到的部分。格式和文件大小则一直都有。
- **歌单**：歌单由用户自己建，统一存在 `LibraryPreferences.playlists` 里，是一段 JSON（用 `org.json`，没有数据库）。
  单个歌单是 `data/Playlist.kt` 的 data class，字段就三个：`id`、`name`、`songIds`。
  界面上列表上方有一条 chip 行（`ui/PlaylistChips.kt`）：「全部」+ 每个歌单一个 chip + 一个「新建歌单」chip，
  点 chip 就把音乐库过滤到那个歌单；**长按 chip** 会弹出重命名 / 删除（`ui/PlaylistSheets.kt`）。
  chip 本身**没有用 Material 的 `FilterChip`**，是自己画的 `Row`（背景 + 描边），手势完全交给 `combinedClickable` 一个接管。
  > 为什么要自己画：`FilterChip` 要求 `onClick` 非空，内部会装一个 clickable，和外层包一层的
  > `combinedClickable` 抢手势，长按经常根本送不到 —— 而长按是这个界面里改名 / 删除歌单的唯一入口，不能靠运气。
  建歌单的入口在音乐库里：长按歌曲进入多选，多选态的顶栏有一个「歌单」按钮，点开 `AddToPlaylistSheet` ——
  输入新名字走「新建并加入」，或者直接挑一个已有歌单。当已经处在某个歌单的筛选状态时，同一个按钮变成「移出歌单」。
  从设备上删歌时会同步执行 `dropMissingSongsFromPlaylists`，把它从所有歌单里去掉。
  因为歌单**只存 id**，被删掉或者不在扫描范围内的歌会被直接忽略，不会留下坏条目。
  > 一个歌单被清空后 chip 行**仍然显示**，所以永远能切回「全部」，不会卡在空歌单里出不来。

**冷启动优化（列表几乎立刻可见）：**

- **主因：以前每次冷启动都要等一次完整的 MediaStore 扫描。** 列表一直停在加载页，扫描没结束就什么都不显示，
  因为扫描结果从来没有被持久化过。现在新增 `data/LibraryCache.kt`，把上一次扫出来的整个音乐库以 JSON
  存到 `filesDir/library_cache.json`：用 `org.json` 手写序列化，**没有引新依赖**；`save` / `load` 都在
  `Dispatchers.IO` 上跑；每个字段都用 `opt*` 读，所以**以后往 JSON 里加字段也不会让老缓存解析失败**。
  `PlayerViewModel` 新增 `publishCachedLibrary()`（在 `init` 预热和 `loadLibrary()` 开头各调一次），
  先把缓存列表发出去，再在后台跑正常扫描并回写缓存。
  > **结果**：从第二次启动起，列表**几十毫秒**就出来，扫描退到后台刷新；
  > `loading` 也改成了 `allSongs.isEmpty()`，已经有内容的列表不会再闪一次全屏加载页。
  > **两个刻意保留的细节**：① 缓存**只在扫描结果非空时才写**——空结果和"没权限 / MediaStore 出错"分不出来，
  > 写进去会把一份好缓存抹掉；② 缓存恢复那条路径**故意不跑 `dropMissingSongsFromPlaylists`**——
  > 它会写 SharedPreferences，拿可能过期的缓存数据去跑会删掉歌单条目，所以这一步只等真正的扫描完成后再做。
- **次因（Android 12 以下）：老路径按原尺寸解码封面。** `ArtworkLoader` 的 legacy 路径（API 26–28）
  以前是直接 `BitmapFactory` 解整张专辑封面、不传任何 `BitmapFactory.Options`——
  一张 3000×3000 的封面就是 **~36 MB**。现在改成**两趟解码**（先 `inJustDecodeBounds`）配 `inSampleSize`，
  目标 **384 px**，和 Android 10+ 那条 `loadThumbnail(Size(384, 384))` 路径保持一致。
- **次因（并发）：以前封面解码数量不受限。** 首屏一帧就可能同时发起 10–15 个解码。
  现在 `ArtworkLoader` 把每个解码都包进一个 `kotlinx.coroutines.sync.Semaphore`（`decodeLimited`）。
  上限**从 3 放宽到 6**：Android 10+ 走的 `loadThumbnail(uri, Size(384, 384))` 单次解码本来就很便宜，
  3 个许可会让滚动时封面一张张慢慢"挤"出来；6 只是一道防病态突发的护栏，
  API 26–28 老路径的内存问题已经由上面的 `inSampleSize` 两趟解码兜底，不再靠压低并发解决。
- **已修（排序分配）：** `recompute()` 的三种排序、`syncManualOrder`、`MusicRepository.buildFolders`
  以前都用 `sortedBy` / `compareBy` + `title.lowercase()` —— **每一次比较**都要新建一个 lowercase 字符串
  （还附带 `Long`/`Int` 装箱）。现在统一换成零分配的 `sortedWith { a, b -> … compareTo(…, ignoreCase = true) }`。
- **已修（扫描结束撞上滚动）：** 缓存优先之后列表早就画好了，扫描回来时用户可能正在拖它。
  `loadLibrary()` 现在会先把扫描结果和当前显示按 id 顺序比一遍，**一样就什么都不更新**（只收掉 `loading`），
  不再无条件换掉 `allSongs` / `folders` / `songs` —— 那会让 LazyColumn 在滚动途中重建每一个可见行。
- **已知残留（这次仍然没改）**：上面这些排序和文件夹汇总**依旧跑在主线程上**
  （`loadLibrary` 的 scope 是 `Dispatchers.Main.immediate`）。分配已经去掉了，但数千首歌时排序本身的
  CPU 时间还没有挪到后台，**属于后续可优化项，不要当成已经解决。**

**目前没做**：收藏、文件夹层级浏览（只有扫描范围，没有按文件夹分组的视图）、均衡器、歌词、按专辑/歌手分组、独立播放队列（队列就是当前显示的列表）。这里更正一处：**播放列表已经做了**（用户自建歌单，见上文），没做的只剩**收藏和队列管理**。

---

## 二、怎么变成手机上的 APK

### 路线 A：云端编译（推荐，不需要装 Android Studio）

1. 注册 / 登录 GitHub，新建一个仓库（public 或 private 都行，名字随便，比如 `yinxia`）。
2. 把 `Yinxia/` 目录里的**全部内容**上传上去。
   - 网页操作：仓库页 → `Add file` → `Upload files` → 把 `Yinxia` 里的文件和文件夹全拖进去。
   - ⚠️ 注意 `.github` 这个目录是隐藏的，**必须一起上传**，云端编译的配置就在里面。
3. 上传完会自动触发编译：仓库页 → **Actions** → 点最新那次运行。
4. 等 3–5 分钟，成功后页面底部 **Artifacts** 里下载 `yinxia-debug-apk`（是个 zip，解压出 `app-debug.apk`）。
5. 把 apk 传到手机安装。

免费账号在公开仓库上的构建时长是无限的；这个构建大概用 3–5 分钟。

### 路线 B：本地用 Android Studio

1. 装 Android Studio（最新版），首次启动时让它装 **SDK Platform 37** 和 **Build-Tools 37**
   （compileSdk 是 37，所以平台要 37；targetSdk 仍是 36）。
2. `File` → `Open`，选中 `Yinxia` 目录。
3. 首次打开会同步 Gradle（要下载依赖，视网络几分钟到十几分钟）。
4. 连上手机并打开 USB 调试，点绿色 ▶ 直接装；或者 `Build` → `Build APK(s)` 拿 `app-debug.apk`。

> ⚠️ **仓库里没有 `gradle-wrapper.jar`**（二进制文件我没法生成）。
> - 云端路线不受影响：workflow 用 `gradle/actions/setup-gradle` 直接安装 Gradle 9.6.0，不依赖 wrapper。
> - 本地路线：Android Studio 打开时会提示"创建 Gradle wrapper"，同意即可；或者用装了 Gradle 9.6.0 的机器跑一次 `gradle wrapper`。
>   国内网络慢的话，建议配国内 Maven 镜像加速依赖下载。

### 覆盖安装（按版本更新）

Android 只允许**和已安装版本同签名**的 APK 原地覆盖安装。以前每次 CI 运行都会新生成一个
`~/.android/debug.keystore`，签名每次都不同，所以装一个新构建必须先卸载旧版——歌单和设置一起没了。

现在 `.github/workflows/build-debug-apk.yml` 里多了一步 `actions/cache@v4`：
`path: ~/.android/debug.keystore`，固定 key `android-debug-keystore-v1`，
把同一个 keystore 钉在多次构建之间；`app/build.gradle.kts` 的版本号也随之递增
（当前 `versionCode 3` / `versionName "1.2"`），这样更新才是可识别的版本号。

> ⚠️ **两点如实说明**：① 这次改动**之前**已经装在手机上的那个构建，是用一个**我们已经复现不出来的密钥**签的，
> 所以**启用这套机制需要最后卸载一次**——卸载干净之后，后续更新才能原地覆盖安装（歌单和设置从此得以保留）；
> ② GitHub 的 cache 连续 7 天不用会被清掉，真被清掉时 keystore 会重新生成，那就又要卸载一次。

---

## 三、装到小米 15（HyperOS 3 / Android 16）之后要做的设置

**必须做第 2 条，否则锁屏后播几分钟就被系统杀掉**——这不是代码 bug，所有第三方播放器在小米上都要这么设：

1. **安装时**：会提示"未经安全检测"，选择继续安装 / 允许来自此来源。
2. **后台保活（关键）**：
   `设置` → `应用设置` → `肥鱼之声` → `省电策略` → 改成 **无限制**；
   同一页里把 **自启动** 打开。
3. **权限**：首次启动会请求"音乐和音频"权限；拒绝的话界面会给你一个按钮直接跳系统设置。
4. **通知栏控制**：播放后下拉通知栏就有媒体卡片；锁屏上也能控制。
   如果通知栏没有卡片，检查 `设置` → `通知管理` → `肥鱼之声` 是否被关掉。

---

## 四、代码结构

```
Yinxia/
├─ .github/workflows/build-debug-apk.yml   云端编译 APK 的流水线
├─ gradle/libs.versions.toml               所有依赖版本的唯一来源
├─ settings.gradle.kts / build.gradle.kts  Gradle 顶层配置
├─ gradle.properties                       JVM 内存、AndroidX 开关
└─ app/
   ├─ build.gradle.kts                     模块配置：compileSdk 37 / targetSdk 36 / minSdk 26
   └─ src/main/
      ├─ AndroidManifest.xml               权限、Activity、播放服务声明
      ├─ java/com/yinxia/music/
      │  ├─ MainActivity.kt                唯一 Activity；权限状态放这里，
      │  │                                 靠 onResume 处理"去设置里开权限再回来"；
      │  │                                 删除也要在这里发起（系统确认框只能用 IntentSender）
      │  ├─ data/
      │  │  ├─ Song.kt                     一首歌的数据（含文件夹、添加时间，以及码率/采样率/大小/MIME 等技术字段）
      │  │  ├─ SortMode.kt                 排序方式枚举
      │  │  ├─ FolderEntry.kt              一个含音频的文件夹
      │  │  ├─ Playlist.kt                 自建歌单（只存歌曲 id）
      │  │  ├─ LibraryPreferences.kt       排序/扫描范围/手动顺序/播放队列的持久化
      │  │  ├─ LibraryCache.kt             音乐库本地缓存：冷启动先画上次的结果，扫描随后在后台刷新
      │  │  ├─ MusicRepository.kt          用 MediaStore 扫描本地音频 + 汇总文件夹
      │  │  └─ ArtworkLoader.kt            封面解码 + 内存 LRU 缓存 + 提主题种子色（自己写的直方图取色，没引 Palette）
      │  ├─ player/
      │  │  ├─ PlaybackService.kt          MediaSessionService：后台播放 + 系统媒体控制
      │  │  ├─ PlaybackConnection.kt       界面进程 ↔ 播放服务的桥梁
      │  │  └─ MediaItems.kt               Song → MediaItem 的转换，App 与桌面插件共用
      │  ├─ ui/
      │  │  ├─ PlayerViewModel.kt         界面状态；播放本身在服务里，所以转屏不断播。
      │  │  │                              排序/范围过滤/多选/删除请求都在这里
      │  │  ├─ App.kt                      权限门 / 列表 / 迷你条 / 各种面板 的组装
      │  │  ├─ LibraryScreen.kt            歌曲列表（普通 / 多选 / 手动排序三种形态）
      │  │  ├─ PlaylistChips.kt            列表上方的歌单筛选条（自己画的 Row，不用 Material 的 FilterChip）
      │  │  ├─ SortSheet.kt                排序方式面板
      │  │  ├─ FolderSheet.kt              扫描范围面板
      │  │  ├─ ThemeSheet.kt               默认主题色面板（10 色预设）
      │  │  ├─ PlaylistSheets.kt           加入歌单面板 / 命名与重命名对话框 / 歌单操作菜单
      │  │  ├─ MiniPlayer.kt               底部迷你播放条
      │  │  ├─ NowPlaying.kt               全屏播放页
      │  │  ├─ Artwork.kt                  封面组件（取不到封面就按歌曲 id 用渐变兜底）
      │  │  └─ theme/                      配色、字体、Material 3 主题
      │  │     ├─ AccentTheme.kt           按主题调整强调色；只覆盖 4 个色位
      │  │     └─ AccentPalette.kt         默认主题色的 10 个固定预设
      │  ├─ util/Format.kt                 毫秒 → 3:07 / 1:02:45
      │  ├─ util/SongDetails.kt            把格式/码率/采样率/大小拼成最多两行文字
      │  └─ widget/
      │     ├─ PlayerWidget.kt            拼 RemoteViews 并推给桌面（按签名去重、按高度选布局）
      │     ├─ PlayerWidgetProvider.kt    AppWidgetProvider：接收器 + 按钮点击
      │     └─ QueueRestore.kt            进程被杀后按存下来的顺序重建播放队列
      └─ res/
         ├─ drawable/ic_*.xml              自绘矢量图标（播放、暂停、排序、文件夹、删除…；填充图形也带同色圆角描边）
         ├─ layout/widget_player.xml       桌面插件布局（RemoteViews 只能用传统 View，不能用 Compose）
         ├─ layout/widget_player_square.xml 竖排插件布局（2×2 这类高度够的尺寸用；布局按高度在运行时选）
         ├─ mipmap-anydpi-v26/             自适应启动图标
         ├─ values/                        文案、颜色、主题
         └─ xml/player_widget_info.xml     桌面插件声明（resizeMode 可自由拉伸；minResizeWidth 110dp）
```

### 几个设计上的取舍

- **不用文件系统遍历，走 MediaStore。** Android 10 以后分区存储不允许随便读目录；MediaStore 有索引，几万首歌也快。
- **播放放在 `MediaSessionService` 而不是 Activity 里。** 这是锁屏/通知栏控制、蓝牙按键、后台不被回收的前提。
- **进度用 500ms 轮询，不是插值。** 切歌、拖动、暂停都会改变位置，轮询的代价很小，但状态永远和播放器一致，不会出现进度条和实际播放对不上。
- **拖动时用本地状态。** 如果每帧都把拖动位置写回播放器，会和播放进度互相打架，表现为"拖不动"。
- **封面走了个弯路。** Android 10+ 直接对歌曲的 content URI 调 `loadThumbnail`（系统返回内嵌封面）；Android 9 及以下退回老的 `albumart` 表——那张表按**专辑 id** 索引，不是歌曲 id，所以 `Song` 里专门留了 `albumId` 字段。

---

## 五、想改哪里

| 想改什么 | 改哪 |
|---|---|
| 主色调 | `ui/theme/Color.kt` 是基础配色；**默认强调色**（没有封面时用的那个）改 `ui/theme/AccentPalette.kt`，或者直接在 App 里点顶栏的调色板图标选 |
| 用壁纸取色（Android 12+） | `ui/theme/Theme.kt` 里把 `dynamicColor` 默认值改成 `true` |
| 桌面插件的外观 | `res/layout/widget_player.xml` + `res/values/colors.xml`（含 `values-night` 的深色值） |
| 过滤掉更短的音频（默认 15 秒） | `MusicRepository.MIN_DURATION_MS` |
| 进度刷新频率（默认 500ms） | `PlayerViewModel.PROGRESS_INTERVAL_MS` |
| 封面缓存数量（默认 80 张） | `ArtworkLoader.CACHE_SIZE` |
| 冷启动先显示什么 | `data/LibraryCache.kt` + `PlayerViewModel.publishCachedLibrary()`（缓存优先：先画上次的列表，扫描在后台刷新）；以及 `ArtworkLoader` 里的并发数（`Semaphore(3)`）与解码目标尺寸常量（384px） |
| 歌曲信息显示哪些字段 | `util/SongDetails.kt` 的 `songDetailLines`（两行各放什么、拼接顺序与单位都在这里） |
| 应用名 / 包名 | `res/values/strings.xml` 的 `app_name`；包名要同步改 `build.gradle.kts` 的 `namespace`、`applicationId` 和 Kotlin 的 package |
| 启动图标 | 图标插画是 `res/mipmap-xxxhdpi/ic_launcher_foreground.png`，外面由 `res/drawable/ic_launcher_image.xml` 包一层 17% inset（自适应图标会裁掉四周约 1/6，这个 inset 就是把人脸收进蒙版安全区的关键）；背景色是 `res/values/colors.xml` 的 `ic_launcher_background` |
| 支持更老的手机 | 改 `minSdk`。但要注意启动图标只做了 API 26+ 的自适应图标（纯矢量），降到 26 以下得补位图图标 |

---

## 六、版本矩阵（以及为什么这么锁）

| 组件 | 版本 | 说明 |
|---|---|---|
| AGP | 9.4.1 | AGP 9 是大版本变更（见下方"两个坑"），写法与 AGP 8 不同 |
| Gradle | 9.6.0 | AGP 9.4 要求 ≥ 9.6.0；CI 里由 action 安装，不依赖 wrapper 文件 |
| Kotlin | **2.2.10（不要改）** | AGP 9 内置 Kotlin，版本由 AGP 自带；AGP 9.4.1 的 POM 依赖 KGP 2.2.10。Compose 编译器插件必须与之**完全一致**，所以写 2.2.10 而不是更新的 2.4.x |
| JDK | 17 | AGP 9.x 的最低和默认 JDK 都是 17；CI 里也钉的 17，不是 21 |
| Compose BOM | 2026.09.00 | 统一决定 compose-ui(1.12.1) / material3 / foundation 版本，各库不写版本号 |
| compose-animation | 跟随 BOM | 这次**唯一新加的依赖**：`androidx.compose.animation:animation`，版本由 Compose BOM 决定，不单独钉版本号。`animateColorAsState` 在 `animation` 里，**不在 `animation-core`** —— 主题色过渡要用它 |
| Media3 | 1.11.1 | ExoPlayer + MediaSession |
| lifecycle-viewmodel | 2.11.0 | 注意不要用 `lifecycle-viewmodel-ktx`——2.8 起它已经是空壳 |
| kotlinx-coroutines | 不声明 | 由 compose-runtime 与 lifecycle-viewmodel 传递引入（1.9.0）。少一个版本号少一处坑 |
| compileSdk | **37** | Compose 1.12 起**强制要求** compileSdk 37 + AGP 9，写 36 会直接构建失败 |
| targetSdk | 36 | Android 16，对应你这台 HyperOS 3。compileSdk 和 targetSdk 不是一回事 |
| minSdk | 26 | Android 8.0 |

> 主题色跟随封面这一版只新增了**一个**依赖：`androidx.compose.animation:animation`
> （在 `gradle/libs.versions.toml` 里不写版本号，跟 Compose BOM 走）。
> `animateColorAsState` 住在 `animation` 里，**不在 `animation-core`** —— 引错模块会直接找不到符号。
> 取色本身没有引 Palette，是 `data/ArtworkLoader.kt` 里自己算的。

### AGP 9 的两个坑（AGP 8 时代的知识在这里是错的）

1. **Kotlin 支持是内置的。** AGP 9 起不能再应用 `org.jetbrains.kotlin.android`，否则报
   *"The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0"*。
   所以 `build.gradle.kts` 的 plugins 里只有 `com.android.application` 和 `org.jetbrains.kotlin.plugin.compose`。
2. **`kotlinOptions {}` 没了。** 内置 Kotlin 下 Kotlin 的 `jvmTarget` 默认跟随
   `android.compileOptions.targetCompatibility`，所以只要设一次 `compileOptions` 为 Java 17 就够了，
   不需要（也不该）再写 `kotlin { compilerOptions { … } }`。

另外 AGP 9 还有一些会影响老写法的变更：`applicationVariants`/`variantFilter` 被移除（改用 `androidComponents.onVariants`）、
`getDefaultProguardFile()` 只接受 `proguard-android-optimize.txt`、kapt 与内置 Kotlin 不兼容。
本工程都没用到这些，所以不受影响。

改版本时请连着看：**AGP ↔ 内置 Kotlin ↔ Compose 编译器插件** 是一组，单独动一个最容易炸。

