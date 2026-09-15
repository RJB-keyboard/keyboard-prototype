# ひらがな言語モデル B

[READMEに戻る](../README.md) · [全体設計](architecture.md) · [開発手順](development.md)

モデルは [hukuda222/hiragana-gpt2-xsmall](https://huggingface.co/hukuda222/hiragana-gpt2-xsmall/tree/b0ef59dcdfd8eaddc3010cb8a2060c6196bba573) の著者公開 ONNX をそのまま使用する。端末内 CPU 推論であり、入力文脈をサーバーへ送信しない。モデルカードのライセンスは Apache-2.0。

```powershell
powershell -ExecutionPolicy Bypass -File tools/prepare_hiragana_model.ps1
```

固定リビジョンは `b0ef59dcdfd8eaddc3010cb8a2060c6196bba573`。取得ファイルはすべてスクリプト内の SHA-256 と照合する。重みは 84,652,543 バイトで `app/src/main/assets/models/hiragana-gpt2-xsmall/model.onnx` に保存される。大きな ONNX バイナリは Git 管理外なので、別のチェックアウトではビルド前に上記を実行する。アプリ初回ロード時にも重みのハッシュを検証し、アプリ専用・バックアップ対象外の領域にコピーする。

Android 依存は `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`。`OnnxHiraganaLanguageModel.open(context)` はモデルのロードを行うため、ワーカースレッドから呼ぶ。ファイル欠落、ハッシュ不一致、推論エラーを疑似 LLM に置き換えない。

ONNX の入力は `input_ids`, `attention_mask`, `position_ids` の 3 個で、それぞれ int64 の `[batch_size, sequence_length]`。出力 `logits` は float32 の `[batch_size, sequence_length, 94]`。公開ファイルには KV cache の入出力がないため、ビームの接頭辞を最大 16 件ずつまとめ、過去文脈＋接頭辞の末尾 96 トークンを計算する。右パディングと attention mask を使い、通常は各行の最後の実トークン位置の logits を読む。連続文字補完では、下記の条件で途中位置の logits も再利用する。位置 ID も明示する。上限はコンストラクタ引数で調整でき、モデル自身の上限は 1024。

## 連続文字補完の確率をまとめて取得

`nextRepeatedLogProbabilities(context, prefixes, maxPredictions)` は、各prefixの末尾のかなを
繰り返したときの次かな分布をまとめて返す。例えば `prefix = ごま` なら、
`P(次かな | 文脈 + ごま)` と `P(次かな | 文脈 + ごまま)` を1回で取得できる。
後者の入力を計算するときの途中位置に、前者のlogitsも含まれるため。
GPT-2の因果マスクにより、途中位置は後続の文字を参照しない。
モデルの重み・ONNXファイル・探索候補・スコア・補完回数は変更しない。

`RepeatedKanaInput` はそれぞれを従来と同じtokenizerで符号化し、
短い入力が長い入力の先頭と一致し、長さが増える場合だけ途中のlogitsを再利用する。
96トークンの切り詰めで入力の先頭が動く場合はそこでまとめるのを止める。
GPT-2は絶対位置埋め込みを使い、現実装は切り詰め後に位置を0から振り直すため、
古い文脈の計算をそのまま使うと同じ結果にならない。
[GPT-2の位置埋め込み](https://huggingface.co/docs/transformers/v4.57.0/en/model_doc/gpt2)と
[因果Attentionとキャッシュ](https://huggingface.co/docs/transformers/v5.3.0/en/cache_explanation)も参照。

返す分布数は各prefixで1〜`maxPredictions`個。ONNX以外の実装は既定で最初の1個だけを返し、
デコーダーが残りを従来どおり要求する。先読みした結果は1回のdecode内だけに保持し、
既にスコア計算に使った分布を上書きしない。ビーム枝刈りは従来の順序で実行する。
これはKV cacheの追加ではなく、公開ONNXが既に出力している確率の利用範囲を広げる変更。
長文脈で96トークンに達しているケースの再計算は引き続き残る。

バッチ形状の違いによる浮動小数点の微小差はあり得る。
実モデルの独立推論との対数確率差、読み順位・表示候補の一致を検証する。
実測結果と精度確認の範囲は [変換時間の調査](performance.md) を参照。

## トークナイザーと中断

語彙 ID は `tokenizer_config.json` の **`char_ords` の掲載順 + 7**。後半の「わ・を・ん・ゎ・ゐ・ゑ・ゕ・ゖ・ゔ・ー」は Unicode 順ではない。特殊トークンは CLS=0, SEP=1, BOS=2, MASK=3, PAD=4, EOS=5, UNK=6 というモデルカードの tokenizer 定義に従う。`config.json` の BOS/EOS 値と tokenizer の値には不一致があるため、生成例どおり非空入力には特殊トークンを足さない。空文脈だけ CLS=0 で初期化する。

出力は U+3041〜U+3096 の 86 種のひらがなを対象にした条件付き分布 `P(次の文字 | 文脈, 接頭辞, 次がひらがな)` の自然対数。特殊トークンと長音「ー」は出力候補に含めないが、文脈入力の「ー」は元の語彙 ID で処理する。語の終わりはストローク側で決める。数値は log-sum-exp で正規化する。

このモデルは漢字を読めない。文脈は可能なら確定した候補のかな読みを渡す。tokenizer は全半角・結合濁点を正規化し、カタカナをひらがなに直す。その他の文字は UNK になる。一般的な漢字混じりの文脈理解をこのモデルに期待しない。

`cancelPendingInference()` は実行中の [ONNX RunOptions](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.RunOptions.html) に停止を要求する。新しいストロークなどで `Future.cancel(true)` するときに併用できる。推論中に使う tensor・result・run options はすべて解放し、`close()` は session を解放する。プロセスで共有する OrtEnvironment は閉じない。

検証は通常の tokenizer 単体テストに加え、`OnnxHiraganaLanguageModelTest` が実際の同梱モデルを端末・エミュレータで実行する。確率の正規化、文脈依存性、可変長バッチと単独推論の一致、文脈切り詰め、キャンセル後の再利用を確認する。モデル欠落時はテストをスキップせず失敗させる。
