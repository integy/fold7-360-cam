# Fold7 360 Cam

Android app 用於 Samsung Galaxy Z Fold 7（及其他摺疊屏手機）同時開啟**外屏自拍鏡頭**與**後置超廣角鏡頭**，透過 OpenGL ES 即時拼接成 2:1 等距柱狀投影（Equirectangular）360 畫面，並以 MediaCodec + MediaMuxer 錄製成 MP4、注入 Google Spherical V1 metadata（YouTube / Meta Quest 可辨識為 360 影片）。

## 功能

- 同時開啟兩顆鏡頭（Camera2 `concurrentCameraIds`）
- OpenGL ES 2.0 shader 即時 360 拼接預覽
- 一鍵錄製 360 影片（H.264 + MediaMuxer）
- 自動注入 Google Spherical V1 360 metadata
- 存檔至相簿 `Movies/Fold7_360`

## 技術

- **相機**：Camera2 API（雙鏡頭 concurrent 串流）
- **渲染**：OpenGL ES 2.0（Equirectangular 投影 shader）
- **編碼**：MediaCodec（H.264）+ MediaMuxer（MP4）
- **360 metadata**：Google Spherical Video V1 uuid box

## 構建

GitHub Actions 會自動編譯 debug APK（`.github/workflows/build_apk.yml`）。本機構建需 Android Studio / SDK：

```bash
gradle assembleDebug
# 輸出：app/build/outputs/apk/debug/app-debug.apk
```

## 限制

- 需在**完全展開**（Unfolded）狀態下使用
- 手機超廣角（~123°）與前置鏡頭（~85°）非 180° 魚眼，360 畫面上下極點與接縫處有黑邊盲區
- Samsung HAL 對同時串流解析度有限制（通常兩路 1080p 或 720p）
- 未經實機測試——雙鏡頭 concurrent 兼容性、拼接質量、metadata 正確性均需在目標裝置驗證

## 檔案結構

```
app/src/main/AndroidManifest.xml
app/src/main/java/com/fold7/cam360/MainActivity.kt
app/build.gradle
build.gradle
settings.gradle
.github/workflows/build_apk.yml
```
