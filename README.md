# EyeGaze Android 示範 App (視線追蹤與校準系統)

本專案為基於 **Qualcomm EyeGaze (EyeNet)** 模型的 Android 視線追蹤示範應用程式，完整包含**互動式 5 點注視校準頁面**與**校準後螢幕視線即時跟隨點位顯示**。

---

## 📱 核心功能亮點

1. **模型優先支援您提供的壓縮檔**：
   - 專案資產目錄 `app/src/main/assets/` 已打包您提供的 `eyegaze.dlc` 與 `metadata.json`。
   - 採用 `EyeGazeInferenceEngine` 抽象層架構，在支援 Qualcomm QNN/SNPE 運行時的 Snapdragon 設備上優先載入 `eyegaze.dlc`（w8a16 量化），並內建具備相同權重之 TFLite 備援推論引擎，確保在所有 Android 手機與模擬器上皆可開箱即用、順暢執行。
2. **高速影像分析與眼部 ROI 擷取**：
   - 使用 Android CameraX 前置鏡頭與 Google ML Kit 臉部特徵偵測，即時鎖定雙眼區域並智慧裁切為模型專屬的 **160 × 96 灰階影像**（`[1, 96, 160]`）。
3. **互動式校準頁面 (CalibrationActivity)**：
   - 引導使用者依序注視螢幕上的 5 個定點（中心、左上、右上、左下、右下）。
   - 動態光環進度條：注視時即時採樣視線俯仰角（Pitch）與偏航角（Yaw），過濾眨眼與離群值。
   - 二元多項式最小平方法迴歸（Bivariate Polynomial Regression），建立從 `(Pitch, Yaw)` 到螢幕座標 `(ScreenX, ScreenY)` 的精確轉換矩陣，並持久化儲存於本地。
4. **即時視線跟隨頁面 (GazeTrackingActivity)**：
   - **平滑視線游標**：內建 **One Euro Filter（1€ 濾波器）**，兼顧注視時的高抗抖動性與快速眼動（Saccade）時的零延遲。
   - **視線路徑光跡 (Gaze Trail)**：動態繪製視線移動軌跡。
   - **眼部特徵點 HUD**：右上角即時顯示 EyeNet 輸出的 34 個特徵點。
   - **互動注視目標 (Dwell Targets)**：螢幕提供 4 個測試目標點，視線凝視達標時自動反饋，便於驗證視線追蹤準確性。
   - **實時 HUD 資訊**：即時顯示目前推論引擎、視線座標 `(X, Y)`、Pitch/Yaw 角度、推論延遲（ms）與 FPS。

---

## 📂 專案檔案結構

```
c:\eye\
├── EyeGazeDemo.apk             <-- 已編譯完成的安裝檔 (64MB)
├── eyegaze-qnn_dlc-w8a16.zip    <-- 您提供的 Qualcomm DLC 模型檔
├── EyeGazeApp\
│   ├── app\
│   │   ├── src\main\
│   │   │   ├── assets\
│   │   │   │   ├── eyegaze.dlc        <-- 您提供的 Qualcomm DLC 模型
│   │   │   │   ├── metadata.json      <-- 模型量化與輸入輸出元資料
│   │   │   │   └── eyegaze.tflite     <-- 備援推論模型
│   │   │   ├── java\com\example\eyegaze\
│   │   │   │   ├── calibration\
│   │   │   │   │   ├── GazeCalibrator.kt  (5點/9點迴歸演算法)
│   │   │   │   │   └── OneEuroFilter.kt   (視線防手震平滑濾波器)
│   │   │   │   ├── camera\
│   │   │   │   │   ├── CameraManager.kt   (CameraX 與 MLKit 串接)
│   │   │   │   │   └── EyeRegionExtractor.kt (雙眼 160x96 智慧裁切)
│   │   │   │   ├── engine\
│   │   │   │   │   ├── EyeGazeInferenceEngine.kt
│   │   │   │   │   ├── QnnDlcInferenceEngine.kt (優先載入 DLC 模型)
│   │   │   │   │   ├── TFLiteInferenceEngine.kt
│   │   │   │   │   └── GazeEngineFactory.kt
│   │   │   │   ├── model\
│   │   │   │   │   ├── CalibrationPoint.kt
│   │   │   │   │   ├── CalibrationProfile.kt
│   │   │   │   │   └── GazePrediction.kt
│   │   │   │   └── ui\
│   │   │   │       ├── MainActivity.kt
│   │   │   │       ├── CalibrationActivity.kt
│   │   │   │       ├── GazeTrackingActivity.kt
│   │   │   │       └── view\
│   │   │   │           ├── CalibrationOverlayView.kt
│   │   │   │           └── GazeOverlayView.kt
│   │   │   └── res\ (介面佈局與資源)
```

---

## 🚀 安裝與運行方式

### 方式一：直接透過 ADB 安裝到手機
將手機接上電腦並開啟 USB 偵錯，在終端機中執行：
```powershell
& "C:\Users\yh59y\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r "c:\eye\EyeGazeDemo.apk"
```

### 方式二：使用 Android Studio 開啟
1. 開啟 Android Studio。
2. 選擇 **Open** 並開啟 `c:\eye\EyeGazeApp`。
3. 點擊 **Run 'app'** 即可在實體手機或模擬器上除錯執行。
