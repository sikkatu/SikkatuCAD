# DWG 原生接入说明

这个工程里，DWG 目前只做了：

- 文件后缀和头部版本码识别
- 版本信息展示
- UI 和解析层预留

如果你要把 DWG 做成真正可看的几何视图，建议走 JNI：

1. 接入 `libdxfrw`
   来源：
   https://github.com/codelibs/libdxfrw
   仓库说明写明它支持 ASCII/Binary DXF，并支持读取 R14 到 2015 的 DWG。

2. 或接入 `LibreDWG`
   来源：
   https://github.com/LibreDWG/libredwg
   仓库自带 `dwg2dxf`、`dwg2SVG` 等工具，适合先把 DWG 转成更容易在 Android 端渲染的中间格式。

推荐落地方式：

- JNI 返回统一的几何对象列表
- Kotlin 侧继续复用 `DxfCanvasView`
- 这样 DXF 和 DWG 共享一套移动端缩放、拖拽、显示逻辑

建议下一步：

- 新增 `native-lib` 模块
- 用 CMake 编译 `libdxfrw` 或 `LibreDWG`
- 把 `LINE/ARC/CIRCLE/POLYLINE/TEXT` 映射到当前 Kotlin 模型
- 先做 2D 图元，块参照和复杂填充后补
