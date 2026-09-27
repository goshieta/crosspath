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
| 本人プロファイル（名前の保存。個人IDはIDサーバーの採番値を保存） | `ui/data/UserProfile.java` |
| 個人IDの初回登録（サーバー通信・冪等リトライ・secret 保存） | `registration/`（`RegistrationRepository` ほか） |
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

## 緊急時モード（Emergency Mode）

緊急時モードは `SessionStatus.canCommunicate` が true の状態（ACTIVE かつ relayEnabled）を指す。

- **モードの保持:** `MainActivity` が唯一の保持者（`emergencyMode` フィールド）。フラグメントは `onSessionStatus` で Activity に状態を通知する。
- **モード中の画面:** ホームは常に SC04（緊急時画面）を表示する。SC05（通知対象者）・SC06（通知履歴）も緊急時ダークテーマ（`Theme.Survival.EmergencyDark`）で表示される。
- **起動時の初期画面:** セッション状態（`UiData.checkSession`）が確定してから決定する。未登録→SC01、緊急時モード→SC04、それ以外→SC02。1200ms のフォールバックを設け、状態が返らない場合は未登録／登録済みで SC01/SC02 を出す。
- **モード切替:** 緊急時モードが変わったらテーマを再適用し、必要に応じて現在の画面を作り直す（SC02↔SC04 の遷移、SC05/SC06 のテーマ再 inflate）。

## データ層との統合

UI は DB を直接触らず、`ui/data/UiData.java` を通して `data/SafetyRepository` だけを呼ぶ
（`CompletableFuture` の結果は `UiData.onResult` がメインスレッドへ返す）。

| 画面 | 実データの使い方 |
|---|---|
| SC01 | 名前を検証し、IDサーバー `POST /v1/registrations` で採番された個人IDを取得して保存（§「初回登録（IDサーバー接続）」） |
| SC02 | 自分の個人ID（`UserProfile`）を表示・コピー・共有。生存登録はタイマー作動中なら SC04、停止中は SC03 |
| SC03 | `KyushuMunicipalities.prefectures()`（同梱の九州自治体マスター）で県・市町村を選択し、確定で `startSession()` |
| SC04 | `checkSession()` で期限判定（`checkAndEndExpiredSession()`）を伴う状態取得→`SessionStatus` に応じて`remainingMillis` のスナップショットを1秒ずつ減らして表示（0で再確認→SC02 へ）。`currentWatchStatuses()` で〇／ーを表示 |
| SC05 | `watchTargets()` / `addWatchTarget()` / `deleteWatchTarget()` で通知対象を CRUD（重複は DB の戻り値で判定） |
| SC06 | `currentWatchStatuses()` で現在の通信期間の受信状態を表示 |

- 〇＝`RECEIVED`（今回のACTIVE期間に受信）、ー＝`NOT_RECEIVED`、全件 `NO_ACTIVE_SESSION`＝「現在の通信期間なし」、0件＝「通知対象者が登録されていません」。
- `currentWatchStatuses()` は履歴から導出しない。履歴表示（`validHistories()`）は今回の画面では未使用。
- 期限判定は `checkAndEndExpiredSession()` 一本化。カウントダウンは `remainingMillis` の表示スナップショットであり、期限判定はデータ層が行う。
- `SessionStopHandler` は現状ログとID記録のみのプレースホルダで、BLE/Service の実停止処理は未実装。

### 緊急時モードと画面遷移（§11.1 / §11.5 / §11.8）

- **緊急時モード = 「未満了の期間がある」（`SessionStatus.State.ACTIVE` または `CLOCK_UNCERTAIN`）**。
  `SessionStatus.canCommunicate`（ACTIVE かつ relayEnabled）ではない。通信不能・時計不確実は
  「72時間の期間が終了した」ことではないため、画面を SC02 へ戻さない（§11.5）。
- 判断は `ui/ScreenPolicy` に集約した純粋関数（`isActivePeriod` / `emergencyMode` / `commState` /
  `screenFor` / `initialScreen` / `backTarget` / `commStatusRes` / `blockReasonRes`）。
  `SessionStatus.State` と `relayEnabled` だけを入力にするので JVM のユニットテストで検証できる。
- SC04 の表示内容（カウントダウン可否・通信状態・通信不能理由・一覧の有無・代替文言）は
  `ui/Sc04ViewState` が決める。通信不能（`relayEnabled == false`）でも期間が続いていれば
  対象者一覧（〇／ー）とカウントダウンを表示し、理由文だけを併記する（§11.5）。
  `CLOCK_UNCERTAIN` は残り時間が確定できないためカウントダウンを止め、時計確認を明示する。
- 期限終了（`EXPIRED`／`ENDED`／`NO_SESSION`）は `UiData.checkSession()` が
  `checkAndEndExpiredSession()`（終了＋レコード削除を同一トランザクション）を経た状態を返すため、
  それを受けて SC02 へ遷移する（削除処理完了後に画面が戻る。UI 側で期限処理はしない）。
- 画面遷移は `ui/Navigator`（`MainActivity` が `ui/NavHost` を実装）に一元化した。フラグメントは
  `((NavHost) requireActivity()).navigatePush/navigateBack/navigateTab(...)` で依頼するだけで、
  自分で `FragmentTransaction` を実行しない。**`addToBackStack` は使わない**（階層は線形フロー）ため、
  戻る操作で古い SC03・古い SC04（期限終了後）へ戻る経路が存在しない。
  現在画面の唯一の保持者も `Navigator` で、遷移と同時に更新する。
- システムの戻る操作は `ScreenPolicy.backTarget()` に従う（SC03 で確定前に戻る → SC02。
  それ以外の画面は仕様に規定が無いため既定動作＝アプリ終了）。

## 未実装の範囲

以下は **実装していない**（TODO コメントを残している）：

- BLE/GATT 通信（SC04 の通信状態表示は「通信期間中／通信できません／未開始」の段階表示。
  通信不能理由の実検知は BLE・権限・Foreground Service の実装後）
- 個人ID の再発行・復旧 UI（`GET /v1/registrations/me` による復旧は `RegistrationRepository` に実装済みだが画面は無い）
- Foreground Service
- Android 通知の発行（`NotificationHistory` の PENDING/POSTED/BLOCKED を使う Dispatcher）
- 登録失敗時の詳細表示（`X-Trace-Id` を画面に出さずログのみ）
- ViewModel 層（現状は Fragment から `UiData` を直接呼ぶ薄い構成）
- BLE/Service の実停止処理（`SessionStopHandler` の実通信停止）

## 初回登録（IDサーバー接続）

SC01 の「登録する」で、IDサーバーから 24bit の個人IDを取得して保存する。端末内でIDを生成する旧実装（乱数）は廃止した。

| 項目 | 内容 |
|---|---|
| エンドポイント | `POST https://id-server-1084526017972.asia-northeast1.run.app/v1/registrations`（再取得は `GET /v1/registrations/me`） |
| 認証 | `Authorization: Bearer <registration_secret>`。secret は 32 バイト乱数の base64url（パディング無し43文字）を端末で生成 |
| 冪等性 | `request_id`（UUID v4）を**通信前に保存**し、リトライでは同じ値を再利用する（同じ secret＋同じ request_id は 200 で同じ user_id が返る） |
| 競合復旧 | 409 のときは `GET /v1/registrations/me` で保存済みIDの復旧を試みる |
| リトライ | 429 / 408 / 5xx は `Retry-After` を尊重し、無ければ指数バックオフ（1s→2s→4s、±20%ジッター、最大4試行）。400 / 401 / 409 / 410 / `ID_SPACE_EXHAUSTED` はリトライしない |
| 保存 | `personal_id` と `name` は `SharedPreferences`（`user_profile`）。secret は **AndroidKeyStore の AES/GCM 鍵で暗号化**して保存し、平文保存へのフォールバックはしない |
| 通信スレッド | 単一の `ExecutorService`。UI スレッドでは通信しない（結果は Handler で main へ post） |

実装は `app/src/main/java/com/example/crosspath/registration/` に集約する。

| クラス | 役割 |
|---|---|
| `RegistrationRepository` | 登録操作の全体（冪等性・リトライ・競合復旧）。`RegistrationHttp` と `RegistrationStore` を注入して受け取る |
| `HttpRegistrationApi` / `RegistrationHttp` | `HttpURLConnection` 実装（connect 5s / read 10s）と差し替え可能なインターフェース |
| `PrefsRegistrationStore` / `RegistrationStore` | secret・request_id・user_id・created_at の永続化（テスト用に `InMemoryRegistrationStore`） |
| `RegistrationSecret` / `RegistrationJson` / `RegistrationResponse` / `RegistrationRetryPolicy` / `RegistrationError` / `RegistrationException` | 純粋ロジック（JVM ユニットテスト対象） |

ユニットテストは `app/src/test/java/com/example/crosspath/registration/`（`RegistrationRepositoryTest` は偽 HTTP で request_id の再利用・409→me 復旧・秘密値の非漏洩を検証）。