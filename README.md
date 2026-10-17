# OHealth Fluid Unlock

针对 **OPPO 健康（`com.heytap.health`）** 的 libxposed API 102 模块。

它做两件事，且**只**做这两件事：

1. **解锁闸门** —— 把流体云的环境判定方法强制返回“支持”，让 App 内部的流体云管线继续往下走；
2. **输出探针日志** —— 在数据分发的关键节点打印日志，用来判断 App 自带白名单应用的数据究竟能不能流到「推送手表」这一步。

> 本版本**不伪造任何卡片数据**。日志里出现的数据全部来自 App 自身逻辑，可信度最高。
> 目的是先验证通道，再谈自定义配置。

---

## 一、背景：流体云的判定链

分析 APK `OPPO 健康_6.9.40_f91e7bd_260930.apk`（versionCode 6094000，19 个 dex）得到的关键结论：

### 判定闸门（全部依赖 ColorOS 环境）

`NotificationBaseAbility$Info.isSupportFluid()` 是一道多重合取：

```
isAboveOS14()
  && !isFamilyDevice()
  && (非副机 || isSupportSecondFluid())
  && getApplicationInfo("com.oplus.pantanal.ums").metaData.supportWatch == 1
  && PantaCardTools.isSupportFluidCloud(ctx)
  && isDeviceSupportFluid() > 0
```

其中 `isSupportFluidCloud` 最终落到一次纯系统调用：

```java
// SeedlingIntentUtil.isSupportFeatureFromUms
Uri uri = Uri.parse("content://intelligent_data_expositor/switch");   // Constants.INTENT_PROVIDER_URI
ContentProviderClient c = cr.acquireUnstableContentProviderClient(uri);
Bundle r = c.call("isFluidCloudSupport", null, null);                 // 由 com.oplus.pantanal.ums 提供
return r.getBoolean("is_fluid_cloud_support");
```

解析不到 Provider 就直接 `false`。这就是非 OPPO 手机上流体云不工作的根因之一。

### 数据出口（与 ColorOS 卡片无关）

`FluidManager.sendCardData(...)` 的尾部依次做了这些事，**没有任何 ColorOS 卡片服务调用**：

```
applyNotifyLevel(...)
  → NotificationCompat.Builder.setLargeIcon/setContentTitle/setContentText → build()
  → new StatusBarNotification(...)
  → HealthNotificationBean.convert(sbn).setAppName(pkg)
  → ProcessorBase.buildWebpFollowUpBundle(bundle)
  → HealthNotificationRegisterCenter.onNotificationFluid(hnb, bundle)   ← 推送手表就在这一行
```

这个方法的**开头**才有白名单检查：

```java
FluidConfigCenter.getSupportApp(dataSourcePkgName);   // null 就 return false
```

### App 内置白名单（`FluidConfigCenter.defaultApps()`）

固定 23 项，另加 2 项按系统版本条件注入：

| 类别 | 条目 |
|---|---|
| OHealth 自有服务 | `com.heytap.health:pickup` 取单码提醒、`:carlock` 离车安心、`:safeguard` 出行守护、`:touchwf` 一碰传表盘、`:express` 快递 |
| 地图出行 | `com.baidu.BaiduMap` 百度地图、`com.autonavi.minimap` 高德地图、`com.sdu.didi.psnger` 滴滴出行 |
| 交通票务 | `com.umetrip.android.msky.app` 航旅纵横、`com.crgt.ilife` 国铁吉讯、`com.MobileTicket` 12306 |
| 外卖零售 | `me.ele` 饿了么、`com.sankuai.meituan` 美团、`com.sankuai.meituan.takeoutnew` 美团外卖、`com.pupumall.customer` 朴朴超市、`com.dianping.v1` 大众点评 |
| 电商票务 | `com.jingdong.app.mall` 京东、`com.taobao.taobao` 淘宝、`com.taobao.movie.android` 淘票票、`cn.damai` 大麦 |
| 支付/出行 | `com.eg.android.AlipayGphone` 支付宝、`com.jingyao.easybike` 哈啰 |
| 其他 | `com.teamtalk.im` TeamTalk |
| 条件项 | `com.coloros.sceneservice.quickapp.checkin` 考勤打卡（需 `com.oplus.pantanal.ums`≥`0x1039cf1` 且 `com.oplus.metis`≥`0xf48339` 且 `com.coloros.sceneservice`≥`0x1036e10`）<br>`com.coloros.sceneservice.quickapp.game` 体育赛事（需 `com.oplus.pantanal.ums` 版本达标） |

机型子集：`IWATCH_FLUID_LISTS`（Watch 系列 12 项）、`BAND2_FLUID_LISTS`（手环 2 项）。

---

## 二、模块做了什么

### 闸门 Hook（`[GATE]` 日志）

| 类 | 方法 |
|---|---|
| `com.oplus.pantanal.seedling.util.BaseTool$Companion` | `isSupport$seedling_support_manualRelease(Context,String,String,long)` → `true` |
| `com.oplus.seedling.sdk.utils.SeedlingIntentUtil` | `isSupportFeatureFromUms(Context,String,String)` → `true` |
| `com.oplus.seedling.sdk.SeedlingSdk` | `isSupportFluidCloud(Context)` → `true` |
| `com.oplus.pantanal.seedling.util.SeedlingTool` | `isSupportFluidCloud(Context)` → `true` |
| `com.heytap.health.watch.notification.NotificationBaseAbility$Info` | `isSupportFluid()` / `isSupportSecondFluid()` → `true`，`isDeviceSupportFluid()` → `1` |
| `NotificationAbility$Info` 等 deviceability 实现类 | 同样三项，按方法名容错匹配 |
| `FluidManager` | **兜底**：`init()` 返回后把 `supportFluid` 字段强制置 `true` |

### 容错设计

不同 ROM / 版本覆写能力判定的类并不一致，因此：

- 类不存在 → `SKIP (absent)`，INFO 级，不是错误；
- 方法不存在 → 先按**精确签名**找，失败再按**方法名**匹配同名重载，仍失败则 `SKIP (not declared)`；
- 最终由 `FluidManager.supportFluid` 字段兜底，不依赖任何单个判定方法被命中。

> 早期版本曾对 `NotificationAbility$Info#isSupportSecondFluid` 报 `NoSuchMethodException`——
> 该方法只在接口 `NotificationBaseAbility$Info` 中声明，实现类并未覆写。
> 现已改为容错查找，此类情况不会再报错。

### 探针 Hook（`[OBS]` 日志）

| 类 | 方法 | 作用 |
|---|---|---|
| `HealthNotificationRegisterCenter` | `onNotificationFluid(HealthNotificationBean, Bundle)` | **推手表必经之路**，所有卡片都经过 |
| `FluidManager` | `sendCardData(CardViewInfo, SeedlingUIData, boolean, Integer, Function1)` | 卡片数据出口 |
| `FluidConfigCenter` | `getSupportApp(String)` | 白名单查询，命中即内置支持 |
| `ExternalFluidCloudInjector` | `doInject(Bundle)` / `doRemove(Bundle)` | 外部注入通道 |

`Bundle` 参数会被完整展开打印（字节数组只显示长度）。

---

## 三、构建

### GitHub Actions（推荐）

推送代码或手动触发 `Build APK` 工作流即可。产物在 Actions 的 **Artifacts** 里；打 `v*` 标签会自动创建 Release。

工作流会：

1. 准备 JDK 17 与 Gradle 8.9；
2. 现场生成签名密钥（`ohealth` / 口令 `android`）；
3. 编译 release APK；
4. **校验 APK 内确实包含 `META-INF/xposed/` 清单**，缺失则直接失败。

### 本地构建

```bash
# 使用系统 Gradle 8.9+，JDK 17
gradle assembleRelease

# 或安装 Android Studio 后直接 Build
```

本地若没有 `keystore.jks`，release 构建会自动沿用 debug 签名，装到手机上同样可用。

产物路径：`app/build/outputs/apk/release/app-release.apk`

---

## 四、使用

1. 在 LSPosed 中安装 APK，**勾选作用域 `OPPO 健康`**（`scope.list` 已内置，通常会自动选中）；
2. 强制停止并重新打开 OPPO 健康；
3. 查看 LSPosed 日志，过滤标签 `OHealthFluidUnlock`。

### 日志含义

```
[GATE] com.oplus.seedling.sdk.SeedlingSdk#isSupportFluidCloud -> true
```
闸门已生效，App 认为环境支持流体云。

```
[OBS] FluidConfigCenter#getSupportApp (com.sdu.didi.psnger) => FluidAppInfo
```
白名单命中，说明该应用被 App 内置支持。

```
[OBS] HealthNotificationRegisterCenter#onNotificationFluid (HealthNotificationBean, Bundle{...}) => null
```
**关键行**：数据成功抵达「推送手表」节点。若这一行出现，说明通道打通。

### 判读建议

| 现象 | 结论 |
|---|---|
| 只有 `[GATE]`，没有 `[OBS]` | 上游仍被卡住：PantanalClient 拿不到 ColorOS 决策回调 |
| 有 `getSupportApp` 但总是 `=> null` | 包名不在白名单，需扩展支持列表 |
| 有 `sendCardData` / `onNotificationFluid` | 通道打通，可进入下一步（自定义配置） |

---

## 五、已知限制

- **不保证手表以「流体云形态」渲染**。`onNotificationFluid` 确实把数据交给了 `NotificationEventManager` 推手表，但固件是否识别为流体云卡片、而非普通通知，取决于 `webp_md5`、`level` 层级、`KEY_TYPES` 等字段是否符合预期。这是唯一的黑盒，必须实机验证。
- **不能凭空造出手机状态栏的流体云胶囊**。渲染与决策都在 ColorOS 的 `com.oplus.pantanal.ums` 平台侧，App 只是「服务方」。
- **`ExternalFluidCloudReceiver` 是动态注册的**（`AndroidManifest.xml` 中搜不到），广播注入路线是否可用需实机确认。
- 兼容 Android 14+ 的 LSPosed，需正确声明作用域。

---

## 六、免责声明

仅供在**你本人拥有的设备**上做技术验证与学习交流。请勿用于绕过系统安全机制或商业用途。作者不对任何滥用行为负责。

## 七、许可

[MIT](LICENSE)
