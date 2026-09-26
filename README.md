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
UI とデータ層（Room）は接続済み。BLE 通信・Foreground Service・Android 通知の発行は未実装。

| 画面ID | 画面名 | Fragment クラス | レイアウトファイル |
|---|---|---|---|
| SC01 | 初回登録 | `Sc01RegistrationFragment` | `fragment_sc01_registration.xml` |
| SC02 | ホーム | `Sc02HomeFragment` | `fragment_sc02_home.xml` |
| SC03 | 市町村選択 | `Sc03MunicipalityFragment` | `fragment_sc03_municipality.xml` |
| SC04 | 緊急時画面 | `Sc04EmergencyFragment` | `fragment_sc04_emergency.xml` |
| SC05 | 通知対象者管理 | `Sc05WatchTargetFragment` | `fragment_sc05_watch_target.xml` |
| SC06 | 通知履歴 | `Sc06NotificationHistoryFragment` | `fragment_sc06_notification_history.xml` |

下部タブバー（ホーム／通知対象者／通知画面）は **SC02・SC04・SC05・SC06 で共通**。
登録フローの SC01（初回登録）・SC03（市町村選択）には表示しない。
タブの実装は `res/layout/include_bottom_tabs.xml` ＋ `res/menu/menu_bottom_tabs.xml` ＋ `ui/BottomTabs.java`。

タブバーは **Activity 直下**（`activity_main.xml` の `bottom_tabs_container`）に置き、`MainActivity` が
唯一の保持者として表示・テーマ・選択状態を管理する（ページ＝Fragment の中には置かない）。
そのためページ遷移アニメーションでは動かず、背景は画面全幅（端から端・下端まで）に広がる。
SC04 ではタブバーもダークテーマで作り直される。

タブバーでホームを選ぶと、タイマー作動中は SC04、それ以外は SC02 を表示する
（判定は `UiData.isTimerActive()` = `ActiveSession` の実データ）。

### 補助ファイル

| 役割 | ファイル |
|---|---|
| 画面ID enum | `ui/Screen.java` |
| テーマ適用ヘルパ | `ui/theme/ScreenThemes.java` |
| 遷移ヘルパ | `ui/theme/NavTransitions.java` |
| 出現アニメーションヘルパ | `ui/theme/ViewAnims.java` |
| 下部タブバー | `ui/BottomTabs.java`・`res/layout/include_bottom_tabs.xml`・`res/menu/menu_bottom_tabs.xml` |
| データアクセス口（UI から DB への唯一の経路） | `ui/data/UiData.java` |
| 本人プロファイル（名前・個人IDのローカル保存） | `ui/data/UserProfile.java` |
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
SC01 (初回登録) ──登録成功──→ SC02
                                    │
                           生存ボタン↓
                                    │
                               SC03 (市町村選択)
                                    │
                              確定→ SC04 (緊急時画面)
                                    │
                        72時間経過↓
                                    │
                                SC02 (期限終了)

 タブバー（SC02・SC04・SC05・SC06 で共通）
   ┌──────────────┬──────────────┐
   │   ホーム      │  通知対象者   │  通知画面     │
   └──────────────┴──────────────┴─────────────┘
      SC02/SC04         SC05            SC06
      （3画面をタブで行き来）
```

※ ホームタブはタイマー作動中は SC04、それ以外は SC02 を表示する。タブ切り替えは
`replace`（バックスタックへ積まない）。SC01→SC02・SC02→SC03・SC03→SC04 は既存の階層遷移。

## アイコンの運用

アイコンは **Material Symbols**（Google Fonts 配信の公式アイコン）を Android の
vector drawable XML に変換して取り込む。`material-icons-extended` は Jetpack Compose 専用のため
Java＋XML の本プロジェクトでは使わない。

取得は任意のアイコン名を変数に書けるツールで行う（既定アイコン一覧・--fill の詳細はツール冒頭を参照）：

```bash
# 既定アイコンの outline 版を取得
python3 tools/fetch_material_symbols.py

# タブ選択用の fill（塗り）版も併せて取得
python3 tools/fetch_material_symbols.py --fill
```

出力は `app/src/main/res/drawable/ic_<name>_24.xml`（outline）／`ic_<name>_fill_24.xml`（fill）。
アイコンの追加は【既定アイコン一覧】(下記) に名前を足すだけで再取得できる。

### 既定アイコン一覧

| 用途 | アイコン名（drawable） |
|---|---|
| タブ: ホーム | `ic_home_24` / `ic_home_fill_24` |
| タブ: 通知対象者 | `ic_group_24` / `ic_group_fill_24` |
| タブ: 通知画面 | `ic_notifications_24` / `ic_notifications_fill_24` |
| 個人ID | `ic_badge_24` |
| コピー / 共有 / 削除 | `ic_content_copy_24` / `ic_share_24` / `ic_delete_24` |
| 生存登録 / 緊急 | `ic_sos_24` |
| カウントダウン | `ic_timer_24` |
| 通知履歴の見出し | `ic_history_24` |
| 現在地 / 確定 | `ic_location_on_24` / `ic_check_24` |
| 成功 / 失敗 | `ic_check_circle_24` / `ic_error_24` |
| 通信状態 / Bluetooth無効 | `ic_sync_24` / `ic_bluetooth_disabled_24` |
| 対象者 / 追加 | `ic_person_24` / `ic_person_add_24` |
| 遷移（矢印） | `ic_arrow_forward_24`（`android:autoMirrored="true"`） |

### 技術メモ

Material Symbols の配信 SVG は `viewBox="0 -960 960 960"`（960 グリッド・y 軸が負）。
Android vector には viewBox の最小座標が無いため、`viewportWidth/Height=960`（`width=24dp / height=24dp`）とし、
全 `<path>` を `<group android:translateY="960">` で囲んで y を 0..960 へ移して vector drawable 化している
（`viewportWidth=24` にすると y が -960..0 のまま残り、何も描画されない）。
色は必ずテーマから取る（`app:iconTint` / `app:startIconTint` / `android:tint` に `?attr/...`）。

## アニメーション方針

画面遷移は Material の **SharedAxis**（タブ切り替え = X 軸、階層遷移 = Z 軸）を使う。
画面内の出現は alpha＋translationY の**1回だけ**のアニメーション。
**点滅・ループ・常時の警告アニメーションは使わない**（動きは「遷移」「出現」「操作」に紐づく1回のみ）。
実装は `ui/theme/NavTransitions.java`（遷移）と `ui/theme/ViewAnims.java`（出現）に集約している。

## データ層との統合

UI は DB を直接触らず、`ui/data/UiData.java` を通して `data/SafetyRepository` だけを呼ぶ
（`CompletableFuture` の結果は `UiData.onResult` がメインスレッドへ返す）。

| 画面 | 実データの使い方 |
|---|---|
| SC01 | 名前を検証して `UserProfile.register()`（個人ID 1..0xFFFFFF をローカル生成・保存） |
| SC02 | 自分の個人ID（`UserProfile`）を表示・コピー・共有。生存登録はタイマー作動中なら SC04、停止中は SC03 |
| SC03 | `KyushuMunicipalities.prefectures()`（同梱の九州自治体マスター）で県・市町村を選択し、確定で `startSession()` |
| SC04 | `currentSession()` から残り時間を1秒刻みで表示（0で SC02 へ）。`currentWatchStatuses()` で〇／ーを表示 |
| SC05 | `watchTargets()` / `addWatchTarget()` / `deleteWatchTarget()` で通知対象を CRUD（重複は DB の戻り値で判定） |
| SC06 | `currentWatchStatuses()` で現在の通信期間の受信状態を表示 |

- 〇＝`RECEIVED`（今回のACTIVE期間に受信）、ー＝`NOT_RECEIVED`、全件 `NO_ACTIVE_SESSION`＝「現在の通信期間なし」、0件＝「通知対象者が登録されていません」。
- `currentWatchStatuses()` は履歴から導出しない。履歴表示（`validHistories()`）は今回の画面では未使用。

## 未実装の範囲

以下は **実装していない**（TODO コメントを残している）：

- BLE/GATT 通信（SC04 の通信状態表示は「通信期間中／未開始」の段階表示）
- Foreground Service
- Android 通知の発行（`NotificationHistory` の PENDING/POSTED/BLOCKED を使う Dispatcher）
- 期限管理のうち履歴の100時間保持・明示クリーンアップの実行
- 登録 API（サーバ側の本人登録）
- ViewModel 層（現状は Fragment から `UiData` を直接呼ぶ薄い構成）