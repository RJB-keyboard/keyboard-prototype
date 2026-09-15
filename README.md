# 五十音グライドキーボード

五十音表のタップ・グライドから候補を表示し、候補のタップで確定するAndroid IMEのプロトタイプです。Android 7.0（API 24）以降に対応します。

## 使い方

1. 下記「ビルド準備」でモデルを取得し、Android Studioでプロジェクトを開いて実機またはエミュレーターで `app` をRunします。
2. アプリの「1. キーボードの設定を開く」から「五十音グライド（試作）」を有効にします。
3. アプリへ戻り、「2. キーボードを選ぶ」で「五十音グライド（試作）」を選択します。
4. 試し入力欄をタップし、五十音表の文字をタップするか、指を置いたまま複数の文字をなぞります。
5. 指を離すと軌跡と文脈から生成した変換候補が表の上に表示されます。候補をタップすると入力先へ確定します。

GPT-2の次かな予測・軌跡認識・辞書の読みと単語コストを探索中に混合し、読み候補を辞書ベースのエンジンで漢字に変換します。初回はモデルと辞書の読み込みに時間がかかります。入力中の通信はありません。

表は右から左に「あ・か・さ・た・な・は・ま・や・ら・わ」の10列で、各列は上から下に並びます。わ行は「わ・を・ん・空白・ー」を縦一列に配置します。現代仮名46文字と「ー」の47キーを配置し、や行・わ行の空きマスはキーにしません。グライド中は線と現在のキーを強調表示します。

以前の「あいキーボード」と同じアプリ・IMEコンポーネントなので上書き更新できます。元のキーボードには候補欄右上の「ABC」キーから戻せます。エミュレーターで表示されない場合は、物理キーボード接続時にもソフトウェアキーボードを表示する設定を有効にしてください。

## フロントとエンジンの分離

```text
GojuonBoardView: DOWN → MOVE（履歴点も取得）→ UP
    ↓ GlideTrace（座標・経過時刻・キー配置）
GlideKeyboardView.onTraceCompleted
    ↓ JapaneseInputMethodService が橋渡し
CandidateSession（専用ワーカー、古いリクエストの破棄）
    ↓ trace + カーソル直前の文章
GlideCandidateEngine
    ├─ StrokeModel → A（距離・曲がり・滞留・通過点のスキップ）
    ├─ Kuromojiで文脈を読みへ → hiragana-gpt2-xsmall → B（次かな確率）
    ├─ SumireReadingLexicon → 読みのprefix・単語／接続コスト・未知語ペナルティ
    └─ GlideDecoder → ビーム探索で読み候補を生成
         ↓ Sumireの辞書・接続コスト・Viterbi/A*でかな漢字変換
    ↓ List<String>（変換候補＋最上位の読みそのもの）
GlideKeyboardView.showCandidates
    ↓ 候補をタップ → onCandidateSelected
JapaneseInputMethodService → InputConnection.commitText
```

| 担当 | ファイル | 責務 |
| --- | --- | --- |
| フロント | `ui/GojuonLayout.kt` | 五十音の配置とヒット判定 |
| フロント | `ui/GojuonBoardView.kt` | タップ・グライド収集、軌跡とキーの描画 |
| フロント | `ui/GlideKeyboardView.kt` | 候補一覧の表示、軌跡と候補選択をコールバックで通知 |
| エンジン | `engine/CandidateEngine.kt` | 軌跡データと差し替え可能なインターフェース |
| 軌跡認識・探索 | `engine/StrokeModel.kt`, `engine/GlideDecoder.kt` | 意味に依存しないA、A/Bのビーム探索、読みの確率と成分スコア |
| かな予測 | `engine/lm/` | 指定GPT-2のONNX Runtime推論、文字単位のトークナイザー |
| かな漢字変換 | `engine/conversion/` | 既存Sumireランタイム・辞書のアダプター、Kuromojiによる文脈読み取り |
| 非同期処理 | `engine/CandidateSession.kt` | 推論の直列実行、中断、古い結果の破棄、リソース解放 |
| IME接続 | `JapaneseInputMethodService.kt` | フロントとエンジンの接続、入力先への確定、セッションのリセット |

Kotlinファイルは `app/src/main/java/net/ramdos/keyboard_prototype/` 以下にあります。

エンジンの契約:

- `GlideTrace.points`: 1回の操作の順序付き座標列。タップも短い軌跡として渡します。
- `TracePoint.x/y`: 五十音表全体の左上を `(0, 0)`、右下を `(1, 1)` とした座標。途中で表の外へ出た点も丸めずに保持します。
- `TracePoint.elapsedMillis`: 指を置いた時点からの経過ミリ秒。バッチ化された移動履歴も保持します。
- `GlideTrace.keys`: その軌跡で使用した文字と矩形領域。描画と同じ正規化座標を使います。
- 戻り値は候補順の `List<String>`。空なら候補を表示しません。

`GlideDecoder.decode(trace, context)` は `ReadingCandidate` を返し、読み・Aの対数確率・Bの対数確率・辞書コスト・未知文字数・混合スコア・候補内の正規化確率を確認できます。IMEの初期スコアは `log A + 0.35 * log B - dictionaryCost`。`GlideDecoderConfig` でビーム幅、重み、文字追加ボーナス、上限を調整できます。`dictionaryWeight = 0.0` で以前のA+B探索との比較ができます。Aは幾何学的なヒューリスティックで、実ストロークから学習した校正済み確率ではありません。候補確率は刈り込まれたビーム内での近似値です。長い読みへの言語モデルのペナルティは、実データに合わせて重みと文字追加ボーナスを調整する必要があります。

辞書は最終的な漢字変換だけでなく、読みの探索中の枝刈りにも使います。既知語と語の自然な接続を優先しつつ、名前・新語のための自由なかなの経路を残します。辞書で未知文字を含む読みは、無理に漢字断片へ変換せずかなのまま表示します。辞書は同じ配列を共有するため、追加ダウンロードは不要です。

Bはひらがなの次文字に条件付けた確率で、EOSはスコアに含めません。読みの長さは軌跡のイベントとスキップで決まります。漢字変換にはLLMを使いません。詳しくは [探索エンジン](docs/engine.md)、[モデル](docs/hiragana-model.md)、[変換エンジン](docs/conversion.md) を参照してください。

フロントは推論を行わず、`CandidateSession` がワーカーで生成します。入力先の切り替え、カーソル移動、新しいジェスチャー、キャンセル時には処理を中断し、既にUIへ配送された結果も世代番号で破棄します。`StubCandidateEngine` はフロントのテスト用に残しており、IMEでは使いません。モデル・辞書が欠けた場合はエラーを表示します。

## ビルド準備

```powershell
powershell -ExecutionPolicy Bypass -File tools/prepare_hiragana_model.ps1
```

モデルは固定リビジョンとSHA-256で検証し、`app/src/main/assets/models/hiragana-gpt2-xsmall/model.onnx`（約85 MB）へ取得します。この大きなファイルはGit管理対象外です。Sumireの基本辞書はassetsに同梱しています。依存関係・ライセンス・辞書の再取得手順は上記の各ドキュメントにあります。APKへの同梱後はすべてオフラインで動作します。

## 操作の扱い

- 新しい軌跡を開始すると前の候補を消します。候補確定後、IMEを閉じたとき、入力先の切り替え時にも状態をリセットします。
- 空きマスから始めた操作は無視します。
- 表の外で指を離す、操作がキャンセルされる、2本目の指を置く場合は、その軌跡を破棄します。
- 横画面は表の高さを縮め、入力先を表示したままにします。
- 濁音・半濁音・小書き文字は元のキーの確率的な別候補です（例：は→は／ば／ぱ、や→や／ゃ）。同じキーで小さな往復をすると連続文字の手がかりになります。
- 学習による個人最適化は未実装です。軌跡認識の係数と候補品質は実操作データによる調整が必要です。
- 文脈はカーソル直前最大256文字を読み取り、モデルへ渡すのは最大96トークンです。パスワード欄と `IME_FLAG_NO_PERSONALIZED_LEARNING` 指定時は前文脈を読み取りません。
- 入力内容や軌跡を永続保存・送信する処理はありません。変換結果の小さなキャッシュはメモリー内のみで保持します。

## 検証

JDK 25とAndroid SDK（Platform 37.0、Build Tools 36.0.0）を使用します。Gradleは同梱のWrapperで実行します。

Linux／macOSでCIと同じチェックを実行:

```sh
./gradlew --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows:

```powershell
.\gradlew.bat --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# USBデバッグを許可した端末またはエミュレーターで実モデル・辞書・UIのテスト
.\gradlew.bat :app:connectedDebugAndroidTest
```

単体テストは同梱の辞書を使い、ONNXモデルのダウンロードや端末接続なしで実行できます。端末テストの実行には「ビルド準備」のモデル取得が必要です（Linux／macOSでは `pwsh -File tools/prepare_hiragana_model.ps1`）。モデルなしでもAPKのビルドはできますが、IMEの候補生成は利用できません。

### GitHub Actions

[Android CI](.github/workflows/android-ci.yml) は、`main`へのpush・Pull Request・手動実行に対応します。作業ブランチはPull Requestの作成・更新時にチェックし、pushとの二重実行を防ぎます。

- 単体テスト、Android Lint、APKビルドの3ジョブを最大3並列で実行します。APKビルドではデバッグAPKと端末テストAPKをまとめてビルドします。
- 1ジョブが失敗しても残りのチェックは継続します。全ジョブの成功を、従来と同じ名前の `Unit tests, lint and build` チェックで判定します。
- Gradleの依存関係をキャッシュし、同じブランチ／PRの古い実行をキャンセルします。
- 成功・失敗にかかわらず、生成されたレポートを `android-check-reports-unit-tests` と `android-check-reports-lint` に14日間保存します。
- エミュレーターでの端末テスト実行はCIに含みません。上記の `connectedDebugAndroidTest` で実行します。

ローカルのレポートは `app/build/reports/tests/testDebugUnitTest/index.html` と `app/build/reports/lint-results-debug.html`、JUnit XMLは `app/build/test-results/testDebugUnitTest/` に出力します。

APK: `app/build/outputs/apk/debug/app-debug.apk`

テストでは、Aの正規化・通過点・繰り返し・濁音／小書き文字、Bによる候補順位の変化、混合確率、異常な入力、中断、古い結果の破棄、実辞書の漢字変換と読み戻しを確認します。端末テストでは実ONNXモデルの分布・文脈依存・バッチ整合性と、既存のタッチ・候補選択・キャンセル動作も検証します。

実モデルの結合テストに加え、Pixel 8で滞留なし・キー内のずれを含む同じ合成軌跡を辞書なし／ありで比較しています。「にほんご」は2位→1位、「こんにちは」は4位→2位、「ありがとう」は1位を維持。「とうきょう」はどちらも上位8件に入らず、軌跡認識側の課題が残っています。詳細と計測条件は [探索エンジンの検証](docs/engine.md#検証) を参照してください。少数の合成軌跡の結果であり、自由な入力の認識率を保証するものではありません。

参考: [Android公式IMEガイド](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method?hl=ja)、[MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)。

## 新しいキーボード配置

あ行を右端に配置し、左端は上から「わ・を・ん・空き・ー」とします。「っ」の独立キーはありません。

```text
わ ら や ま は な た さ か あ
を り    み ひ に ち し き い
ん る ゆ む ふ ぬ つ す く う
   れ    め へ ね て せ け え
ー ろ よ も ほ の と そ こ お
[ ← ] [ 、。 ] [ スペース ] [ 改行 ] [ → ]
```

- 上部は候補一覧を2行で表示し、右下に削除キーを配置します。
- 下段はカーソル左・句読点・半角スペース・Enter・カーソル右を幅1:1:2:2:1で配置します。通常の改行キーは「改行」と表示します。Enterは入力先に応じて改行・検索・送信などを実行します。
- 削除はUnicodeコードポイントまたは選択範囲を対象とします。押した直後に1回削除し、400ms長押しすると80ms間隔で削除し続けます。指を離す、キーの外へ移動する、キーボードを閉じると停止します。
- 地球儀などのOSボタン用に、最低48dpの専用領域と8dpの間隔を確保します。IME caption bar・navigation bar・切り欠きがそれ以上の領域を要求した場合は拡張します。
- 独自の収納キーは設けず、OS側の収納操作を利用します。
- 試し入力欄はキーボード表示に合わせてスクロールし、入力中のカーソル行を見える位置に保ちます。文字列内のカーソル位置は変えません。
- 左右キーは入力先へ方向キーイベントを送り、カーソルを移動します。
- 句読点キー「、。」を押すと候補欄に「、」「。」を表示し、選んだ記号を入力します。
- 削除・句読点・スペース・Enter・左右キーでは未確定候補を消し、実行中の候補生成結果を無効化します。

統合ブランチは `codex/frontend-requirements` です。比較用の `codex/ergonomic-gojuon-layout` は別アプリIDを持つ試作として残しています。この統合版は従来のアプリIDを維持します。

この設計図対応のフロントエンド変更は `codex/kana-grid-frontend` ブランチです。エンジン・辞書・モデル・依存関係は変更しません。

## 英数字・記号を他のキーボードで入力する

- 候補欄右上の「ABC」をタップすると、直前に使ったキーボードへ切り替えます。戻れない場合は次のキーボードを試し、切り替え先がなければ選択一覧を開きます。
- 「ABC」を長押しすると、Androidのキーボード選択一覧を開きます。Gboardなど、英数字・記号を入力できるキーボードを事前に端末の設定で有効にしてください。
- 外部キーボードに切り替えた後、必要に応じてそのキーボードの英字／数字／記号キーを押してください。他のIMEの内部配列をこのアプリから指定することはできません。日本語入力へ戻るには、外部キーボードまたはOSの切り替え操作から「五十音グライド（試作）」を選択します。
- 切り替え時は未確定候補・軌跡を消し、処理中の候補生成を無効化します。候補を入力したい場合は切り替える前に確定してください。
