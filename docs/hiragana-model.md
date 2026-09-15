# ひらがな言語モデル B

モデルは [hukuda222/hiragana-gpt2-xsmall](https://huggingface.co/hukuda222/hiragana-gpt2-xsmall/tree/b0ef59dcdfd8eaddc3010cb8a2060c6196bba573) の著者公開 ONNX をそのまま使用する。端末内 CPU 推論であり、入力文脈をサーバーへ送信しない。モデルカードのライセンスは Apache-2.0。

```powershell
powershell -ExecutionPolicy Bypass -File tools/prepare_hiragana_model.ps1
```

固定リビジョンは `b0ef59dcdfd8eaddc3010cb8a2060c6196bba573`。取得ファイルはすべてスクリプト内の SHA-256 と照合する。重みは 84,652,543 バイトで `app/src/main/assets/models/hiragana-gpt2-xsmall/model.onnx` に保存される。大きな ONNX バイナリは Git 管理外なので、別のチェックアウトではビルド前に上記を実行する。アプリ初回ロード時にも重みのハッシュを検証し、アプリ専用・バックアップ対象外の領域にコピーする。

Android 依存は `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`。`OnnxHiraganaLanguageModel.open(context)` はモデルのロードを行うため、ワーカースレッドから呼ぶ。ファイル欠落、ハッシュ不一致、推論エラーを疑似 LLM に置き換えない。

ONNX の入力は `input_ids`, `attention_mask`, `position_ids` の 3 個で、それぞれ int64 の `[batch_size, sequence_length]`。出力 `logits` は float32 の `[batch_size, sequence_length, 94]`。公開ファイルには KV cache の入出力がないため、ビームの接頭辞を最大 16 件ずつまとめ、過去文脈＋接頭辞の末尾 96 トークンを再計算する。右パディングと attention mask を使い、各行の最後の実トークン位置の logits を読む。位置 ID も明示する。上限はコンストラクタ引数で調整でき、モデル自身の上限は 1024。

語彙 ID は `tokenizer_config.json` の **`char_ords` の掲載順 + 7**。後半の「わ・を・ん・ゎ・ゐ・ゑ・ゕ・ゖ・ゔ・ー」は Unicode 順ではない。特殊トークンは CLS=0, SEP=1, BOS=2, MASK=3, PAD=4, EOS=5, UNK=6 というモデルカードの tokenizer 定義に従う。`config.json` の BOS/EOS 値と tokenizer の値には不一致があるため、生成例どおり非空入力には特殊トークンを足さない。空文脈だけ CLS=0 で初期化する。

出力は U+3041〜U+3096 の 86 種のひらがなを対象にした条件付き分布 `P(次の文字 | 文脈, 接頭辞, 次がひらがな)` の自然対数。特殊トークンと長音「ー」は出力候補に含めないが、文脈入力の「ー」は元の語彙 ID で処理する。語の終わりはストローク側で決める。数値は log-sum-exp で正規化する。

このモデルは漢字を読めない。文脈は可能なら確定した候補のかな読みを渡す。tokenizer は全半角・結合濁点を正規化し、カタカナをひらがなに直す。その他の文字は UNK になる。一般的な漢字混じりの文脈理解をこのモデルに期待しない。

`cancelPendingInference()` は実行中の [ONNX RunOptions](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.RunOptions.html) に停止を要求する。新しいストロークなどで `Future.cancel(true)` するときに併用できる。推論中に使う tensor・result・run options はすべて解放し、`close()` は session を解放する。プロセスで共有する OrtEnvironment は閉じない。

検証は通常の tokenizer 単体テストに加え、`OnnxHiraganaLanguageModelTest` が実際の同梱モデルを端末・エミュレータで実行する。確率の正規化、文脈依存性、可変長バッチと単独推論の一致、文脈切り詰め、キャンセル後の再利用を確認する。モデル欠落時はテストをスキップせず失敗させる。
