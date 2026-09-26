# crosspath — 災害時すれ違い通信

災害時に携帯電話網が利用できない環境で、Bluetooth によるすれ違い通信を利用して
自身の生存情報を発信し、気にかかる人の安否情報を受信する Android アプリ。

## ビルド手順

### 前提条件

- Android Studio Koala (2024.x) 以降
- Android SDK 37 (compileSdk), minSdk 31
- JDK 11 (Android Studio 同梱の JBR を使用)

### コマンドラインビルド

```bash
# 環境変数 JAVA_HOME と ANDROID_HOME を設定
export JAVA_HOME="$HOME/.local/opt/android-studio/jbr"
export ANDROID_HOME="$HOME/Android/Sdk"

# Debug APK をビルド
./gradlew assembleDebug --console=plain

# APK の出力先
# app/build/outputs/apk/debug/app-debug.apk
```

### Android Studio でのビルド

1. File → Open でプロジェクトルートを開く
2. Run ボタンまたは Build → Build Bundle(s) / APK(s) → Build APK(s)

## インストール手順

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 画面構成

プロジェクトは仕様書 v0.8 第11章に基づき、6つの画面（SC01〜SC06）で構成される。
**現在の実装は UI のみ**（BLE 通信・データ永続化・Service・通知は未実装）。

| 画面ID | 画面名 | Fragment クラス | レイアウトファイル |
|---|---|---|---|
| SC01 | 初回登録 | `Sc01RegistrationFragment` | `fragment_sc01_registration.xml` |
| SC02 | ホーム | `Sc02HomeFragment` | `fragment_sc02_home.xml` |
| SC03 | 市町村選択 | `Sc03MunicipalityFragment` | `fragment_sc03_municipality.xml` |
| SC04 | 緊急時画面 | `Sc04EmergencyFragment` | `fragment_sc04_emergency.xml` |
| SC05 | 通知対象者管理 | `Sc05WatchTargetFragment` | `fragment_sc05_watch_target.xml` |
| SC06 | 通知履歴 | `Sc06NotificationHistoryFragment` | `fragment_sc06_notification_history.xml` |

### 補助ファイル

| 役割 | ファイル |
|---|---|
| 画面ID enum | `ui/Screen.java` |
| テーマ適用ヘルパ | `ui/theme/ScreenThemes.java` |
| 暫定サンプルデータ | `ui/sample/SampleData.java` |
| 共通リソース（色） | `res/values/colors.xml` |
| 共通リソース（テーマ） | `res/values/themes.xml` |
| 共通リソース（寸法） | `res/values/dimens.xml` |
| 共通リソース（文字列） | `res/values/strings.xml` |
| 共通リソース（スタイル） | `res/values/styles.xml` |

## テーマ構成

| 画面 | テーマ | 基準色 |
|---|---|---|
| SC01・SC02・SC03・SC05・SC06 | `Theme.Survival.Light` (Material3 Light) | `#42EB93` |
| SC04 のみ | `Theme.Survival.EmergencyDark` (Material3 Dark) | `#B01735` |

OS のダーク設定にかかわらず画面ごとに固定テーマを適用する。

## 画面遷移図

```
SC01 (初回登録) ──登録成功──→ SC02 (ホーム)
                                    │
                           生存ボタン↓
                                    │
                               SC03 (市町村選択)
                                    │
                              確定→ SC04 (緊急時画面)
                                    │
                     ┌──────────────┼──────────────┐
                     ↓              ↓              ↓
                  SC05 (対象者)  SC06 (履歴)  SC02 (期限終了)
```

## 今回のスコープ（UI のみ）

以下の機能は **実装していない**（UI 上は固定値ダミー表示／TODO コメント）：

- BLE/GATT 通信
- Room/DB/DAO/Entity
- Foreground Service
- 通知の発行
- タイマー・期限管理（72時間／100時間）
- 登録 API
- ViewModel のデータ取得