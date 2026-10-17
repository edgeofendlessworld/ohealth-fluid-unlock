# 模块入口类与 META-INF/xposed 清单必须保留
-keep class io.github.endlessworld.ohealthfluid.** { *; }

# libxposed API 由框架在运行时提供，不做混淆
-keep class io.github.libxposed.api.** { *; }
