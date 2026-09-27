# 通常画面の中継サービス（2026-09-27）

詳細設計書v0.8の3、4.2、9.2、9.4、11.5節に沿った通信機能の組み込み。
設計書全体の実装完了を意味しない。登録API・Android安否通知Dispatcherは別工程。

## 動作

- 市町村確定のコミット後、SC04が現在の登録を確認しMainActivityからサービスを起動。
  未登録・市町村選択中・期限切れでは起動しない。登録済み期間へのアプリ復帰時にも確認する。
- 表示中のActivityで付近のデバイス権限とAPI33以上の通知許可を要求。
  通知拒否でもBLE権限があれば中継できる。拒否で登録期限を延長・初期化しない。
- RelayForegroundServiceはconnectedDevice型、非exported。中継用Channelの常駐通知を作る。
  通常画面を離れてもActivityから停止しない。OSによる停止・省電力の影響は実機確認が必要。
- AUTO役割、MTUは実交渉値、HIERARCHICAL優先（相手がFLATのみなら既存の方式合意に従う）。
  初期共有予算65536B、接触45秒。FULLのみの旧APKとは互換ではない。
- DBコミット後ACK、期間・接続の取消ゲート、既存の差分同期エンジンを使用。
- 終了・切断後10秒で再探索。相手ごとの成功60秒／失敗の指数backoffとジッターも適用。
  新規人物受信での抑制短縮を維持する。再探索の10秒待ちと探索窓により実接続までの時間は変わる。
- SC04に探索・接続・同期・待機・通信不能理由と直近結果を表示。
  DB保存後はSC04／SC06の対象者一覧を更新。タイムアウトをEQUALにしない。
- Bluetooth OFF・権限不足・保存確認失敗時は停止し、アプリ復帰または「通信を再開」で再評価。
  任意の期間終了ボタンは追加しない。
- 30秒ごとのRepository確認、期限タイマー、各フラグメントのゲート、DB保存時検証を併用。
  期限・時計異常では閉じたゲートを優先し、満了時は既存Repositoryが通信データを削除。
  履歴の削除期限は期間終了＋100時間のまま。
- START_STICKYの再生成でも保存状態を確認する。再起動・強制停止後はアプリを開く運用。
- BLE検証画面（debug限定・adb起動）を開くとサービスを停止して通信所有を切り替える。通常画面へ戻ると再確認して再開。

## 確認結果

- debug／releaseビルド成功、JVM101件成功、Lintエラー0・警告39。
- Pixelのinstrumentation 61件成功（専用DBを使用する既存Room・時計等の回帰テスト）。
- Pixelに保存データ保持でAPK更新。MainActivity cold startはStatus: ok。
- サービスログでSTARTING→DISCOVERINGを確認。
- dumpsysでisForeground=true、type=0x10（connectedDevice）、relay通知Channelを確認。
- 起動プロセスのクラッシュログなし。

これらは2台での統合後の同期や画面消灯動作の確認を代替しない。

## 次の実機確認

1. Galaxyも同じAPKへ更新。両端末で通常の緊急時画面を開き、権限を許可する。
2. 検証画面は開かず、役割や開始ボタンの操作なしで「相手との不足なし」を確認。
3. 一度ホームへ戻る／画面を消し、中継通知と復帰後の状態を確認する。
4. Bluetooth OFFで停止理由、ON後「通信を再開」で探索復帰を確認する。
5. 不足データのある状態での自動同期・途中切断後再試行、サービス再生成、期限境界を追加確認する。

## 残課題

- HIERARCHICAL・MTU23は要約転送だけで45秒を超える実測条件がある。今回も制限を維持し未完了扱い。
  FLATへの自動切替や未合意の省略形式は追加していない。
- 2台での統合後の実BLE、画面消灯、省電力、Bluetooth復旧、サービス再生成の受入は未実施。
- Android安否通知Dispatcher、登録API、3台実中継、大規模性能は未完了。

OS要件確認：[Android Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)。
