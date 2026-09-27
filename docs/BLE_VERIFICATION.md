# crosspath — Android BLE実装

> main統合前の実装説明・実機試験記録を保持した文書です。現在の起動先・期限管理・共有DBの構成は
> [README](../README.md) を参照してください。旧 `MainActivity` は `BleDebugActivity` に移動しました。
> main統合後は通常画面上部の「BLE検証画面を開く」から以下の試験画面を開きます。

Android Studioでこのフォルダーを開き、Gradle Sync後、`app` のdebug構成を実機へ実行します。
Java 11ソース、minSdk 31（Android 12）、compileSdk / targetSdk 36、Build Tools 36.1.0。
Gradle実行にはAndroid Studio付属JBR 21を使用します。AGP 8.13.0 / Gradle 8.13を使用し、Roomは既存バージョンのままです。
Android Studioの対応上限に合わせてAGP 9.4.1 / Gradle 9.6.0から変更しました。
`gradle/gradle-daemon-jvm.properties` もJava 25固定から21に変更しています。
Android Studioでは `File → Sync Project with Gradle Files` を実行してください。
元のSDK 37設定は、このPCで導入済みのSDK 36へ変更しました。

## 今回の範囲

詳細設計書 v0.8 第6章のBLE通信層、第7章のFULL同期＋Room保存後ACKと検証画面です。
初期状態にはRoom保存層・九州233市町村マスター・WireRecordがありましたが、BLE処理はありませんでした。
引き継ぎ文書の `com.example.disasterrelay` / JSON試作はこのリポジトリにはなく、現行パッケージは `com.example.crosspath` です。
既存のRoom APIとマスターを維持し、通信の型・検証には既存WireRecord / MunicipalityMasterを利用しています。

|仕様|実装|
|---|---|
|6.1 両役割|Client / Server固定試験、AUTOでAdvertiser＋Scanner、unsigned 64bit tokenの小さい側だけ接続|
|接続ロック|単一protocolExecutorで1接続。接続中は探索・広告停止、競合接続拒否、45秒上限、停止時close／再構成破棄|
|6.2 広告|Flags＋128bit Service UUID。scan responseに同Service Data UUID＋major 1B＋token 8B。氏名・個人ID・端末名なし|
|GATT|RX Write With Response、TX Indication、CCCD設定。Read／Prepared Write転送は非対応|
|6.3 初期化|接続→発見→属性検査→ローカル通知設定→CCCD完了→MTU結果→HELLO|
|MTU|要求517、成功コールバックの実値を使用。要求受付失敗／結果失敗は23。コールバック未着は操作タイムアウトで切断|
|6.4 分割|Big Endianの10Bヘッダー、本文最大4096B、1方向1件、順序・ヘッダー整合・CRC32検査|
|DATA|19＋4N B、1〜256件、ID昇順、同じ1024-IDブロック、firstId・マスター所属検査|
|実行時権限|SCAN / ADVERTISE / CONNECT、Bluetooth OFF案内・停止、BLE／広告非対応案内|

主な実装ファイル：

- `ble/BleTransport.java`：Android BLEのライフサイクルとコールバック（全APIはprotocolExecutorから呼ぶ）。
- `ble/GattOperationQueue.java`：GATT操作直列化、10秒タイムアウト。
- `protocol/Protocol.java`：暫定UUID、major、上限値。
- `protocol/FrameCodec.java` / `MessageAssembler.java`：分割、再構成、CRC。
- `protocol/DataCodec.java`：DATAの符号化・復号。検証に失敗したDATAは呼出元へ返さない。
- `sync/FullSyncCoordinator.java`：FULL双方向交換、保存後ACK、再送、期限・接続状態の検査。
- `sync/RoomSyncStore.java` / `SyncStore.java`：非同期保存境界。成功結果はRoomコミット後に返す。
- `sync/FullSyncCursor.java`：循環するキー方式のページ取得。未送信位置から次回再開。
- `CrosspathApplication.java`：Activityを再作成しても同じRoom DBとDB用Executorを使用。
- `MainActivity.java`：実行時権限、役割・テストID・市町村選択、状態表示。

Javaファイルは `app/src/main/java/com/example/crosspath/` 以下にあります。

## 通信担当と共有する暫定プロファイル

仕様案のService/RX/TX UUID、major=4、minor=0、CRC-32/ISO-HDLCを採用。
10BフラグメントヘッダーとDATAレイアウトは仕様6.4どおりです。別実装との合意済みという意味ではありません。
HELLO / BEGIN / DATA / ACK / TURN_END / DONEのtype値は仕様7の値を使用します。

以下は仕様に数値の割当がないため、**今回のdebug検証内だけの暫定値**です。

- FULL mode=1、FULL capability bit=1、保存後ACK capability bit=2（今回の追加案）。HELLOは両bitを必須にし、以前のメモリACK版とは同期しません。両端末に新しいAPKが必要です。
- HELLO masterVersion=`0x20260926` → 既存の `kyushu-2026-09-26-v1` のみを指す明示的マッピング。公式版番号やハッシュではありません。
- ACK結果：0=新規保存、1=同一、2=既知IDの別地点、3=容量拒否。DB失敗時は成功ACKなしで切断。
- TURN_END結果：0=送信完了、1=部分終了。DONE結果：1=PARTIAL、2=EXCHANGED。FULLでは集合一致を検査しないためEQUALは返しません。
- snapshotTokenは接続・方向ごとのランダム32bit、batchIdは各方向1から増加。再送時は同じ本文・batchIdを使います。
- 位置を導出しないためSCANは `neverForLocation`。実機でService Dataが取得できることを確認してください。

peerTokenは「検証開始」ごとに生成し、同値発見時に再生成します。本番の72時間セッションとの関連付けは未実装です。
Serverのtokenは広告とHELLOで照合します。AUTOのServerはClientのtokenが自分より小さいことを確認します。
tokenは認証ではありません。固定Clientは広告しないため、Server側でClient広告との照合は行いません。

## 2台の実機試験

1. 両端末に同じdebug APKをインストール。機内モードをONにし、BluetoothのみONに戻します。
2. 端末B：Server、テストID=2、市町村を選択し「ID・市町村を確定して保存」、続いて「検証開始」。付近のデバイス権限を許可します。
3. 端末A：Client、テストID=1、別の市町村を選択して登録確定し、「検証開始」。登録済みなら確定し直す必要はありません。
4. 両側の表示で `GATT準備完了 / MTU=…`、HELLO、DBコミット完了の新規・既知・拒否件数、双方向FULL交換完了を確認します。
5. 両側で再度開始し、Aの「MTU 23で検証」をONにして同じ交換を試します。この設定はMTU要求を省略します。実MTUも記録してください。
6. 両側AUTOで開始し、双方向交換が1接続で完了するか確認します。終了後の再探索は「検証開始」で行います。
7. 権限拒否、Bluetooth OFF、通信途中の停止、画面遷移・回転、相手消失を試し、停止と再開始を確認します。

通常ログ／状態表示には個人ID・地点・相手MAC・本文を出しません。
4B値の正確な一致、最大256件、MTU別の復元は単体試験で検査します。
実機でペイロードまで照合するときは、Android Studio debuggerでテスト値だけを確認してください。

## FULL同期と未実装項目

**現在はRoomへ登録・受信保存するdebug検証版です。画面を離れると通信を止めます。**
登録確定で既存Repositoryの72時間セッションを作成します。期間中はID・地点を変更できません。
検証開始は保存済み期間を再利用します。停止・再起動・再接続で期限は延長しません。
受信はCRC・ID・ブロック検証後、接続の有効性をDBトランザクション内でも確認して保存します。
ACKはコミット後だけ発行します。切断後の完了コールバックからは送信しません。確定済みバッチは保持します。
`BleTransport.send` の完了はGATT到達でありDB保存ではありません。旧メモリACK実装は削除しました。

同期開始時にsessionId・revision・件数を短いトランザクションで取得し、以後はそのrevision以下だけを
userId順に最大256件ずつ読みます。同じブロックのレコードだけでDATAを構成します。
全件Listやデータ複製の一時テーブルは作りません。HELLOのmaxBodyに応じて件数を減らします。
初回は開始ブロックをランダム化し、ACK確定済みの最終IDを保存して、部分同期の次回はそこから再開します。
末尾まで進むと先頭へ一度だけ戻ります。これにより同じブロック内の後半も飢餓状態にならないようにします。
Client送信→TURN_END→Server送信→TURN_END→DONEの順です。ACK待ちは10秒、再送は2回まで。
片方向15秒、全体45秒が暫定上限です。未確定DATAを残して15秒に達した場合は部分同期として切断します。
状態表示のTX/RXは再送・CRC込みの論理本文バイト数で、フラグメントヘッダー・ATT分を含む実通信量ではありません。

以下は未完了です。

- 第7章のSUMMARY / ID_LIST / REQUESTによる差分、第10章の階層同期。
- 接触ごとの自動再探索・backoff、ERRORの詳細コード合意と送信（不正メッセージは切断し、受信ERRORにも対応）。
- Foreground Service、画面消灯中の継続通信、通知。
- 第9章の時計巻き戻し・端末再起動を考慮した完全な期限管理と、期限データの自動削除。現在は既存の壁時計期限検査と通信中の停止タイマーです。
- 実機2台／3台試験、Android 12〜16の機種差・Central/Peripheral同時運用、電池・大規模性能。

## 検証コマンド

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = 'C:\Users\ymmtk\.gradle'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

初回は依存ライブラリのダウンロードが必要です。
テスト結果は `app/build/reports/tests/testDebugUnitTest/index.html`、APKは `app/build/outputs/apk/debug/app-debug.apk`。
2台間の実機試験は以下の記録を参照してください。同一IDの交換と、異なるIDの双方向新規保存を確認済みです。

### この環境での実行結果

- ACK待機機能追加後の `testDebugUnitTest`：44件成功、失敗0件（FULL同期17件）。保存後のACK抑止、停止・再接続時のデータ保持、待機中の再送とタイムアウト、開始後の設定変更拒否を含みます。`assembleDebug` と `lintDebug` も成功しました。

### 保存後ACK待機による切断試験（debug APK限定）

「保存後ACKを一時停止（切断試験用・開始前に選択）」を追加しました。受信データのDBコミット後にACK送信だけを抑止します。
初期値はOFFで、設定は開始時に読み取ります。通信中にチェックを外しても、その接続のACKは再開しません。
通信スレッドをブロックせず、再送・切断・既存の制限時間（方向15秒、全体45秒）は通常どおり動作します。
再送されたDATAもDBで再判定した後にACKを抑止します。releaseビルドでは非表示・無効です。

1. 両端末を停止し、片方をClient（端末A）、もう片方をServer（端末B）に設定します。
2. Server側だけで上記チェックをONにし、Server、Clientの順で開始します。
3. Server側の「保存完了・ACK待機中」を確認したら、制限時間内にServer側の「停止」を押します。
4. 両端末を停止し、Server側のチェックをOFFにして再度開始します。
5. 既に両側に2件ずつある条件では、両側の「新規=0／既知=2／拒否=0」と「双方向FULL交換完了」を確認します。

待機した接続の終了は成功表示ではなく、切断または部分同期となるのが期待動作です。
この操作は保存後・ACK前の切断と再接続の試験です。既知2件だけの条件では、新規データの永続化は別試験になります。
2026-09-27、上記手順の案内後、利用者より両側で「新規=0／既知=2／拒否=0」と
「双方向FULL交換完了」を確認したとの報告あり。再接続後の通常交換成功を記録します。
待機中の表示・停止のタイミングについては個別報告なし。今回の結果は既知2件での復帰確認です。
続いてClientとServerを入れ替え、新しいServer側だけACK待機をONにして同じ停止・再接続手順を
試すよう案内し、利用者より確認済みとの報告あり。両機種をそれぞれServerにした条件で、
既知2件の再交換と双方向FULL交換完了への復帰を確認したものとして記録します。

同日、PixelをClient（端末A）、GalaxyをServer（端末B）、ServerだけACK待機ONとして、
停止操作をせず待つ試験を案内。利用者より端末Aは
「PARTIAL：ACK未確定のまま片方向時間枠終了(開始ボタンで再試行)」、
端末Bは「切断(開始ボタンで再試行)」と報告あり。
ACK未確定時に成功扱いせず、片方向時間枠終了で接続が終了することを画面表示で確認しました。
実測時間・再送時のDB表示は未報告のため、この結果のみで再送回数は確定しません。
続いて両端末を停止し、GalaxyのACK待機をOFF、同じ役割でServer→Clientの順に再開するよう案内。
利用者より両側で「新規=0／既知=2／拒否=0」と「双方向FULL交換完了」が表示されたとの報告あり。
このタイムアウト後の通常交換への復帰も確認しました。

### 新規保存後・ACK前の停止と永続化確認（利用者報告、2026-09-27）

Galaxyのみアプリデータを初期化してID3で登録し、PixelのID1・2は保持する手順を案内。
PixelをClient、GalaxyをServerとし、GalaxyだけACK待機ONで交換後、
Galaxyの「新規=2／既知=0／拒否=0」と「保存完了・ACK待機中」を確認して停止する試験です。
続いてGalaxyを端末設定から強制停止し、データ消去なしで手動起動、ACK待機OFFで再交換するよう案内。
利用者より案内した期待表示が出たとの報告あり：Pixelは「新規=1／既知=2／拒否=0」、
Galaxyは「新規=0／既知=2／拒否=0」、両側で「双方向FULL交換完了」。
新規受信したID1・2がACK前の停止および強制停止後もGalaxyに保持され、再受信時に既知として
処理されることを画面表示で確認しました。交換後は両端末ともID1・2・3を保有する状態です。
続いてACK待機OFFで再交換し、両側の「新規=0／既知=3／拒否=0」と
「双方向FULL交換完了」を確認するよう案内し、利用者より期待結果が出たとの報告あり。
3件を保持した状態で、重複追加せずに再交換できることを確認しました。
利用者の方針により、3台目を用意できないため実機3台の中継試験は開発継続の条件としません。
2台で受信・保存・再送する経路は確認済みですが、3台の独立した端末によるA→B→Cの中継は未検証です。
中継可能と見込んで開発を進め、実機3台で確認済みとは扱いません。

- `assembleDebug`：成功、debug APK生成済み。
- `lintDebug`：成功、エラー0件。更新通知や検証画面の文字列などの警告あり。
- `git diff --check`：問題なし。
- 実機での確認結果は以下のとおり。エミュレーターでのUI操作は未実施。

FULL同期の自動テストは、MTU 23／247／517の双方向交換、256件・ブロック境界、ACK喪失・再送上限、
重複DATA、保存失敗時ACKなし、切断後の遅延処理、容量拒否、3つの仮想ストアによる中継、
revision固定、token不一致、旧メモリACK版拒否、CRC破損、期限切れを対象にしています。
これらはJVM上の仮想通信・メモリストアによる試験で、Android BLEや実Roomの成功証明ではありません。
実Room向けには、スナップショット固定・旧期間拒否・接続取り消し時の保存拒否／ロールバックの
instrumentation testを追加し、`assembleDebugAndroidTest` も成功しました。
初回ビルド時は端末未接続でした。その後、以下の実DB試験と新APKへの更新を実施しました。

### 第7章の実DB試験（2026-09-27）

SC-51A（SC51Aa）、Android 13 / API 33で、新しいdebug APKとテストAPKを更新インストール。
`com.example.crosspath.data.SafetyRepositoryTest` をAndroidJUnitRunnerで実行し、
**OK (17 tests)、実行時間2.301秒**を確認しました。
テストは個別の一時ファイルDBを作成・削除し、アプリの通常DBは消去していません。
重複・地点競合・自己ID保護、容量拒否、トランザクション失敗時のロールバック、並行受信、
DB再オープン後の保存維持、期間・マスター検査、履歴期限に加え、
snapshot revisionの固定、旧期間snapshotの拒否、切断済み接続の書込み拒否と
トランザクション途中の接続無効化によるロールバックを検証しました。
試験後のMainActivity起動も `Status: ok` でした。
この17件はRoom保存層の試験です。実BLE上の2台FULL交換は後述の別試験で確認しました。

### 2台FULL同期の試験記録（利用者報告、2026-09-27）

端末：Galaxy SC-51A（Android 13 / API 33）、Pixel 6a（Android 17 / API 37）。
両端末とも登録ID=1。Galaxyは機内モード＋Bluetooth ON、Pixelは機内モードOFF＋Bluetooth ON。
両端末の付近のデバイス権限は許可済み。以下は利用者の画面表示報告に基づき、パケットキャプチャは未実施です。

|Client（端末A）|Server（端末B）|MTU 23チェック|両側のMTU|結果|
|---|---|---|---|---|
|Pixel|Galaxy|通常設定|517|両側でHELLO確認、DBコミット（新規0／既知1／拒否0）、双方向FULL交換完了。論理TX=85B／RX=85B|
|Galaxy|Pixel|GalaxyのみON|23|両側でDBコミットと双方向FULL交換完了。利用者より最初と同じ内容との報告|
|Pixel|Galaxy|Galaxy（Server）のみON|517|両側MTU=517。Server側チェックがMTU拡大要求を抑止しないことを確認。今回の交換完了表示は個別報告なし|

MTU 23での分割・再構成を含め、同一IDのDB処理後ACKとFULL双方向交換を実機確認済み。
既知1は両側が自己ID=1を保有している条件の結果です。同じ地点の重複か別地点の競合かは、この集計表示だけでは区別しません。
追加試験で、異なるIDによる交換後、両端末に「新規=1／既知=0／拒否=0」と表示されたとの利用者報告あり。
双方が未保有の人物情報を新規保存した経路を確認しました。この追加試験のMTU・端末設定・最終DONE表示は個別報告なし。
データを消さずに再交換し、両端末で「新規=0／既知=2／拒否=0」と表示されたとの利用者報告あり。
新規保存後の再受信で、双方の2件が既知として処理され、新規挿入されないことを画面表示で確認しました。
この再交換の最終DONE表示は個別報告なし。
両端末を「自動役割決定」、MTU 23チェックOFFにして開始し、両側で
「新規=0／既知=2／拒否=0」と「双方向FULL交換完了」が表示されたとの利用者報告あり。
AUTOでの相手発見・役割決定・双方向交換を実機確認しました。実際に選ばれたClient端末とMTU値は個別報告なし。
両アプリを終了して開き直し、再登録・データ消去なしでAUTO再交換したところ、両側で
「新規=0／既知=2／拒否=0」と「双方向FULL交換完了」が表示されたとの利用者報告あり。
アプリを開き直した後も受信済み情報を保持して交換できることを確認しました。
この操作でOSプロセスが終了したかは未確認のため、強制停止・端末再起動試験とは区別します。
両端末を機内モード、Wi-Fi OFF、Bluetooth ONにし、AUTO・MTU 23チェックOFFで交換したところ、
両側で「新規=0／既知=2／拒否=0」と「双方向FULL交換完了」が表示されたとの利用者報告あり。
両端末オフライン条件でのFULL双方向交換を実機確認しました。
両端末で通信を停止した後、端末設定からcrosspathを強制停止し、ストレージ消去なしで手動起動して
AUTO再交換したところ、両側で「新規=0／既知=2／拒否=0」と「双方向FULL交換完了」が
表示されたとの利用者報告あり。強制停止後も保存済み情報を保持し、手動再開できることを確認しました。
強制停止中の継続通信や自動復帰を確認したものではありません。
片方の端末を再起動し、Bluetooth ONを確認してcrosspathを手動起動後、再登録なしでAUTO再交換したところ、
両側で「新規=0／既知=2／拒否=0」と「双方向FULL交換完了」が表示されたとの利用者報告あり。
その後、利用者よりGalaxy・Pixelの両方で確認済みとの補足報告あり。
両機種それぞれについて、端末再起動後の保存情報保持・手動起動後のAUTO再交換を確認しました。
時計変更や期限境界、自動起動を確認したものではありません。
通信途中の異常系、3台中継は別途実機試験が必要です。

### 実機1台の試験記録（2026-09-27、第7章追加前のAPK）

対象：SC-51A（ADB model: SC51Aa）。
利用者が権限ダイアログ、Server開始・停止・再開始、Client開始・待機・停止・再開始を操作し、
案内した①〜⑧すべてで期待した表示・動作だったと報告。

操作後にADBの診断情報でも以下を確認しました。

- SCAN / ADVERTISE / CONNECTの3権限：すべて `granted=true`。
- crosspathのLE広告：開始3回／停止3回。
- crosspathのLE探索：開始9回／停止9回（端末が保持している累積履歴）。
- GATT Serverの登録・登録解除履歴あり。

広告・探索の開始停止はOS側の履歴でも確認できました。
回数は①〜⑧それぞれと一対一に対応する測定ではありません。
この1台試験時点では、他端末による広告受信、接続、MTU交渉、DATA/ACKの双方向交換は未検証でした。

Android API確認元：[Bluetooth権限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、
[BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)、
[BluetoothGattServer](https://developer.android.com/reference/android/bluetooth/BluetoothGattServer)。
