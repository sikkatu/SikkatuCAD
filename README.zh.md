# SikkatuCAD

[English](README.md) | [Русский](README.ru.md)

![SikkatuCAD](app/src/main/res/drawable/ic_sikkatu_logo.png)

Android 移动端 CAD 图纸文字编辑器和查看器。支持 **DXF** 和 **DWG** 图纸。
界面语言：English / Русский / 中文。

## 功能

- **打开和查看** DXF / DWG 建筑图纸（立面图、平面图、剖面图）；
- **翻译文字标注**（TEXT / MTEXT / ATTRIB）：俄语 → 英语 / 泰语；
- **保存**为 DXF（可靠）或 DWG（实验性）；
- 测量：距离、角度、面积；自动标注；
- 几何编辑：直线、矩形、圆、弧、多段线、文字；
- 图层控制、导出 PNG。

## 隐私

应用**不发送任何数据到互联网**。所有图纸操作都在设备本地完成。

## 编译

```bash
git clone https://github.com/sikkatu/SikkatuCAD.git
./gradlew assembleDebug
```

## 许可证

代码开放可读、可编译使用，但**禁止 Fork 和修改**。
详见 [LICENSE.md](LICENSE.md)。
