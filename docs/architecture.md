# 全体設計

[READMEに戻る](../README.md)

UIは軌跡の収集と候補の表示を担当し、エンジンは候補の生成・順位付けを担当します。

## 入力から確定まで

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
         ↓ 漢字かな交じりの文字n-gramで上位20候補を再評価
         ↓ 読み間の表記文脈も比較し、近い読みの代表を最大3件先頭へ
    ↓ List<DisplayCandidate>（変換候補＋最上位の読みそのもの、確からしさ付き）
GlideKeyboardView.showScoredCandidates
    ↓ 候補をタップ → onCandidateSelected
JapaneseInputMethodService → InputConnection.commitText
```

## 主な実装ファイル

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

Kotlinファイルは [app/src/main/java/net/ramdos/keyboard_prototype/](../app/src/main/java/net/ramdos/keyboard_prototype) 以下にあります。

## 軌跡と候補の契約

- `GlideTrace.points`: 1回の操作の順序付き座標列。タップも短い軌跡として渡します。
- `TracePoint.x/y`: 五十音表全体の左上を `(0, 0)`、右下を `(1, 1)` とした座標。途中で表の外へ出た点も丸めずに保持します。
- `TracePoint.elapsedMillis`: 指を置いた時点からの経過ミリ秒。バッチ化された移動履歴も保持します。
- `GlideTrace.keys`: その軌跡で使用した文字と矩形領域。描画と同じ正規化座標を使います。
- `generateScoredCandidates` の戻り値は候補順の `List<DisplayCandidate>`。`text` と相対的な確からしさ `confidence`（0〜1、不明ならnull）を渡します。空なら候補を表示しません。文字列のみの `generateCandidates` も維持します。
- エンジンで確からしさを算出し、UIはその値を薄い青（0）〜薄い緑（1）へ線形補間します。候補の並び順からは色を決めません。算出方法と制約は [変換候補の背景色](conversion.md#候補の背景色) を参照してください。

## 推論とセッション管理

フロントは推論を行わず、`CandidateSession` がワーカーで生成します。入力先の切り替え、カーソル移動、新しいジェスチャー、キャンセル時には処理を中断し、既にUIへ配送された結果も世代番号で破棄します。`StubCandidateEngine` はフロントのテスト用に残しており、IMEでは使いません。モデル・辞書が欠けた場合はエラーを表示します。

## エンジンの詳細

GPT-2の次かな予測・軌跡認識・辞書の読みと単語コストを探索中に混合し、読み候補を辞書ベースのエンジンで漢字に変換します。漢字変換にはLLMを使いません。辞書は読み探索と変換で同じ配列を共有します。

スコアの式や調整値、評価結果は次の文書にまとめています。

- [探索エンジン](engine.md): 軌跡の確率格子、周辺キー、連続文字補完、ビーム探索、文字追加ボーナス、探索上限。
- [ひらがな言語モデル](hiragana-model.md): 次かな確率、トークナイザー、ONNX推論、モデルの取得とライセンス。
- [変換エンジン](conversion.md): 辞書の共有、未知かなの部分変換、文字n-gramによる表記評価、最終候補の順位付け。

Aの軌跡評価は幾何学的なヒューリスティックであり、候補確率は刈り込まれた候補内での近似値です。実入力に合わせた重みの調整が必要です。

## 文脈とデータの扱い

- 文脈はカーソル直前最大256文字を読み取り、モデルへ渡すのは最大96トークンです。パスワード欄と `IME_FLAG_NO_PERSONALIZED_LEARNING` 指定時は前文脈を読み取りません。
- 入力内容や軌跡を永続保存・送信する処理はありません。変換結果の小さなキャッシュはメモリー内のみで保持します。
- APKへのモデル・辞書の同梱後は、すべてオフラインで動作します。学習による個人最適化は未実装です。

## フロントエンドの実装履歴

UIの試作・統合に使用したブランチは次のとおりです。

- `codex/frontend-requirements`: 従来のアプリIDを維持した統合版。
- `codex/ergonomic-gojuon-layout`: 別アプリIDを持つ比較用の試作。
- `codex/kana-grid-frontend`: 設計図に対応したフロントエンド変更。エンジン・辞書・モデル・依存関係は変更対象外でした。

## 参考

[Android公式IMEガイド](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method?hl=ja)、[MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)。
