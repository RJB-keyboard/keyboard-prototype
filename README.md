# 五十音グライドキーボード

五十音表のタップ・グライドから候補を表示し、候補のタップで確定するAndroid IMEのプロトタイプです。Android 7.0（API 24）以降に対応します。

## 使い方

1. Android Studioでプロジェクトを開き、実機またはエミュレーターで `app` をRunします。
2. アプリの「1. キーボードの設定を開く」から「五十音グライド（試作）」を有効にします。
3. アプリへ戻り、「2. キーボードを選ぶ」で「五十音グライド（試作）」を選択します。
4. 試し入力欄をタップし、五十音表の文字をタップするか、指を置いたまま複数の文字をなぞります。
5. 指を離すと表の上に「あ」「い」が表示されます。候補をタップすると入力先へ確定します。

現在のエンジンは仮実装なので、どの文字をなぞっても候補は「あ」「い」です。表の文字を直接入力する動作ではありません。

表は右から左に「あ・か・さ・た・な・は・ま・や・ら・わ」の10列で、各列は上から下に並びます。わ行は「わ・空白・を・空白・ん」を縦一列に配置します。現代仮名46文字を配置し、や行・わ行の空きマスはキーにしません。グライド中は線と現在のキーを強調表示します。

以前の「あいキーボード」と同じアプリ・IMEコンポーネントなので上書き更新できます。元のキーボードには端末の切り替え機能、または起動画面の選択ボタンから戻せます。エミュレーターで表示されない場合は、物理キーボード接続時にもソフトウェアキーボードを表示する設定を有効にしてください。

## フロントとエンジンの分離

```text
GojuonBoardView: DOWN → MOVE（履歴点も取得）→ UP
    ↓ GlideTrace（座標・経過時刻・キー配置）
GlideKeyboardView.onTraceCompleted
    ↓ JapaneseInputMethodService が橋渡し
CandidateEngine.generateCandidates(trace)
    ↓ List<String>（現在は「あ」「い」）
GlideKeyboardView.showCandidates
    ↓ 候補をタップ → onCandidateSelected
JapaneseInputMethodService → InputConnection.commitText
```

| 担当 | ファイル | 責務 |
| --- | --- | --- |
| フロント | `ui/GojuonLayout.kt` | 五十音の配置とヒット判定 |
| フロント | `ui/GojuonBoardView.kt` | タップ・グライド収集、軌跡とキーの描画 |
| フロント | `ui/GlideKeyboardView.kt` | 候補一覧の表示、軌跡と候補選択をコールバックで通知 |
| エンジン | `engine/CandidateEngine.kt` | Androidに依存しないデータ型・インターフェースと仮実装 |
| IME接続 | `JapaneseInputMethodService.kt` | フロントとエンジンの接続、入力先への確定、セッションのリセット |

Kotlinファイルは `app/src/main/java/net/ramdos/keyboard_prototype/` 以下にあります。

エンジンの契約:

- `GlideTrace.points`: 1回の操作の順序付き座標列。タップも短い軌跡として渡します。
- `TracePoint.x/y`: 五十音表全体の左上を `(0, 0)`、右下を `(1, 1)` とした座標。途中で表の外へ出た点も丸めずに保持します。
- `TracePoint.elapsedMillis`: 指を置いた時点からの経過ミリ秒。バッチ化された移動履歴も保持します。
- `GlideTrace.keys`: その軌跡で使用した文字と矩形領域。描画と同じ正規化座標を使います。
- 戻り値は候補順の `List<String>`。空なら候補を表示しません。

本物のエンジンを組み込むときは `CandidateEngine` を実装し、サービスの `candidateEngine` を差し替えます。フロントには候補を生成するロジックも文字を確定する処理もありません。現時点の呼び出しは指を離したときの同期処理です。重い辞書探索や推論を導入する際はバックグラウンド実行と古いセッションの結果破棄を追加してください。

## 操作の扱い

- 新しい軌跡を開始すると前の候補を消します。候補確定後、IMEを閉じたとき、入力先の切り替え時にも状態をリセットします。
- 空きマスから始めた操作は無視します。
- 表の外で指を離す、操作がキャンセルされる、2本目の指を置く場合は、その軌跡を破棄します。
- 横画面は表の高さを縮め、入力先を表示したままにします。
- 文字認識・辞書変換・濁点・半濁点・小書き文字・削除・改行は未実装です。
- 入力内容や軌跡を保存・送信する処理はありません。

## 検証

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
# USBデバッグを許可した端末でフロントの動作テスト
.\gradlew.bat :app:connectedDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

テストでは、46文字と空きマスのヒット判定、タップ／グライドでのエンジン差し替え、候補選択、移動履歴と時刻の保持、キャンセル・表外・マルチタッチ・リセット、アクセシビリティ経由のキークリックを確認します。

参考: [Android公式IMEガイド](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method?hl=ja)、[MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)。
