# crosspath — 災害時すれ違い通信

災害時に携帯電話網が利用できない環境で、Bluetooth によるすれ違い通信を利用して
自身の生存情報を発信し、気にかかる人の安否情報を受信する Android アプリ。

## ビルド手順

### 前提条件

- AGP 8.13.0に対応するAndroid Studio（この環境は2025.1.3）
- Android SDK 36 (compileSdk / targetSdk), Build Tools 36.1.0, minSdk 31
- Gradle 8.13 / Android Studio同梱JBR 21。Javaソース互換性は11

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
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 画面構成

プロジェクトは仕様書 v0.8 第11章に基づき、6つの画面（SC01〜SC06）で構成される。
UI・Room・BLE同期を統合済み。通常画面からconnectedDevice型Foreground Serviceで自動探索・同期を開始します。
対象者へのAndroid安否通知のDispatcherは未実装（中継サービスの常駐通知とは別）です。
通常画面への組み込み内容と確認範囲は [通常画面の中継サービス](docs/RELAY_SERVICE.md) を参照してください。

## mainとBLE実装の統合

- 通常の起動先はmain由来の `MainActivity`（SC01〜SC06）です。
- debug版の画面上部にある「BLE検証画面を開く」から `BleDebugActivity` を開けます。
  Client／Server／AUTO、MTU23、保存後ACK待機に加え、FULL／FLAT／HIERARCHICALを選択できます。
  release版は入口を非表示にし、このActivityをManifestへ登録しません。
- `CrosspathApplication` がRoom・DB用Executor・`SafetyRepository`を1組だけ所有し、
  `UiData` とBLEが共有します。通常画面で確定した市町村・72時間期間をBLE開始時にも使用します。
  BLEで受信した対象者の情報は、通常画面に戻るとSC04／SC06へ反映されます。
- 既存debug版からの更新はDB v1→v2の移行でデータを保持します。旧検証で登録した期間が
  有効な状態でSC01の名前登録を行うと、保存済みの本人IDを引き継ぎます。
  通常画面で個人IDを登録した後は、BLE画面で別のテストIDには変更できません。
- `SessionStopRegistry` が期限切れ・時計異常・DBエラーによる停止を通信所有者へ通知します。
  `SessionTransportGate` が各BLEコールバック・送信フラグメントで残り時間と時計を確認し、
  BLE画面での定期判定も同じRepositoryへ接続します。保存・snapshot取得も同じ期限判定を使います。
- BLE通信は検証画面表示中のみ、手動開始です。通常画面の表示だけでは広告・探索を開始しません。
  通常画面への自動通信接続、画面消灯中の通信、自動再探索、通知Dispatcherは次の実装対象です。

既存のBLE試験手順と端末での検証記録は [BLE_VERIFICATION.md](docs/BLE_VERIFICATION.md) に保持しています。
統合前の実機成功記録を、統合後APKの実機検証済みという意味には扱いません。

### 統合後の検証（2026-09-27）

- `testDebugUnitTest`：79件成功、失敗0件。既存の同期・画面状態・期限管理に加え、
  フラグメント用ゲートの期限境界・時計巻き戻し・古い期間の停止要求を検証。
- `assembleDebug` / `assembleDebugAndroidTest` / `assembleRelease`：成功。
- `lintDebug`：エラー0件、警告37件。main由来のImageViewのtint属性4件を修正。
- Room同期からUIの受信状態・通知履歴への反映、snapshotの時計異常拒否、
  UIとBLEのRepository共有を確認するinstrumentation testを追加し、テストAPKをビルド。
  その後Galaxy SC-51Aへ統合版とテストAPKを上書きインストールし、instrumentation testは
  58件すべて成功（5.85秒）。共有Repository、Room保存、期限管理、DB移行を含みます。
  MainActivityの起動も `Status: ok`。
- Galaxyで通常画面からBLE検証画面へ移動し、登録情報の保持、Server開始・停止、
  通常画面への復帰ができたとの利用者報告あり。統合後の2台BLE再試験は未実施。

実機では更新インストール後、通常画面→BLE検証画面で既存登録を確認し、
2台FULL交換→通常画面へ戻って対象者の受信状態を確認してください。

### 第10章の追加

差分・階層同期、L1／L1'の短い形式の選択、L2による同件数別IDの照合、
保存後ACKごとの交互転送、共有予算、再接続抑制、期間・revision付きキャッシュを追加しました。
検証画面の初期選択はHIERARCHICALです。従来の試験にはFULLを選んでください。
仕様上の暫定値、統計の測定範囲、試験手順は [CHAPTER10_SYNC.md](docs/CHAPTER10_SYNC.md) を参照してください。
追加後のJVMテストは101件、Galaxyのinstrumentation testは61件が成功しています。
第10章の2台実BLE試験は未実施です。

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
（判定は `UiData.isActivePeriod()` = `SessionStatus` の実データ）。

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

## 緊急時モード（Emergency Mode）

緊急時モードは未満了の期間がある状態（ACTIVE または CLOCK_UNCERTAIN）を指します。

- **モードの保持:** `MainActivity` が唯一の保持者（`emergencyMode` フィールド）。フラグメントは `onSessionStatus` で Activity に状態を通知する。
- **モード中の画面:** ホームは常に SC04（緊急時画面）を表示する。SC05（通知対象者）・SC06（通知履歴）も緊急時ダークテーマ（`Theme.Survival.EmergencyDark`）で表示される。
- **起動時の初期画面:** セッション状態（`UiData.checkSession`）が確定してから決定する。未登録→SC01、緊急時モード→SC04、それ以外→SC02。1200ms のフォールバックを設け、状態が返らない場合は未登録／登録済みで SC01/SC02 を出す。
- **モード切替:** 緊急時モードが変わったらテーマを再適用し、必要に応じて現在の画面を作り直す（SC02↔SC04 の遷移、SC05/SC06 のテーマ再 inflate）。

## データ層との統合

UI は DB を直接触らず、`ui/data/UiData.java` を通して `data/SafetyRepository` だけを呼ぶ
（`CompletableFuture` の結果は `UiData.onResult` がメインスレッドへ返す）。

| 画面 | 実データの使い方 |
|---|---|
| SC01 | 名前を検証して `UserProfile.register()`（個人ID 1..0xFFFFFF をローカル生成・保存） |
| SC02 | 自分の個人ID（`UserProfile`）を表示・コピー・共有。生存登録はタイマー作動中なら SC04、停止中は SC03 |
| SC03 | `KyushuMunicipalities.prefectures()`（同梱の九州自治体マスター）で県・市町村を選択し、確定で `startSession()` |
| SC04 | `checkSession()` で期限判定（`checkAndEndExpiredSession()`）を伴う状態取得→`SessionStatus` に応じて`remainingMillis` のスナップショットを1秒ずつ減らして表示（0で再確認→SC02 へ）。`currentWatchStatuses()` で〇／ーを表示 |
| SC05 | `watchTargets()` / `addWatchTarget()` / `deleteWatchTarget()` で通知対象を CRUD（重複は DB の戻り値で判定） |
| SC06 | `currentWatchStatuses()` で現在の通信期間の受信状態を表示 |

- 〇＝`RECEIVED`（今回のACTIVE期間に受信）、ー＝`NOT_RECEIVED`、全件 `NO_ACTIVE_SESSION`＝「現在の通信期間なし」、0件＝「通知対象者が登録されていません」。
- `currentWatchStatuses()` は履歴から導出しない。履歴表示（`validHistories()`）は今回の画面では未使用。
- 期限判定は `checkAndEndExpiredSession()` 一本化。カウントダウンは `remainingMillis` の表示スナップショットであり、期限判定はデータ層が行う。
- `SessionStopHandler` はApplicationのレジストリ経由で該当期間の検証画面・中継Serviceの通信を停止します。

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

- 個人ID のサーバ発行（現状は端末内生成のみ）
- Android 通知の発行（`NotificationHistory` の PENDING/POSTED/BLOCKED を使う Dispatcher）
- 登録 API（サーバ側の本人登録）
- ViewModel 層（現状は Fragment から `UiData` を直接呼ぶ薄い構成）
- MTU23での階層要約の転送時間改善（現在の2台試験では45秒で未完了）
- 統合後の通常画面での2台自動同期・画面消灯・権限取り消し・サービス再生成の実機受入
