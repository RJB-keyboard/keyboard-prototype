# オフラインかな漢字変換

`SumireKanaKanjiConverter.open(context)` は、同梱辞書を読み込み、
`convert(reading, limit)` でかな漢字交じりの候補を返す。
この処理にも、文脈を読みへ戻す `readingOf(text)` にも LLM・ネットワークは使わない。
初期化・変換は IME の単一ワーカーで実行する。

## 採用したエンジンとデータ

変換には [KazumaProject/kotlin-kana-kanji-converter](https://github.com/KazumaProject/kotlin-kana-kanji-converter)
の **実行時部分を Android 向けに移植・修正したコード**を使用する。
上流そのままのバイナリでも、完全な Mozc エンジンでもない。
上流の LOUDS 読み／表記トライ、単語コスト・品詞接続コスト、ラティス構築を利用し、
そのラティス上で Viterbi/A* による N-best 変換を行う。

- 固定版: `v1.7.252`
- ソース commit: `a1c44cc6f104e381056c0acc42d8d22a36a5918c`
- 実装: `app/src/main/java/com/kazumaproject/`
- 辞書: [公式リリースの japanese_keyboard_dictionary_assets.zip](https://github.com/KazumaProject/kotlin-kana-kanji-converter/releases/tag/v1.7.252)
  から抽出した `system` 3 ファイル、品詞表、接続行列の計 5 ファイル。
- 辞書の抽出後 ZIP は変更せず `app/src/main/assets/conversion/sumire/` に収録。
  約 13.5 MB、展開した主要配列の元データは約 35.6 MB。
  プロセス全体にはトライ索引、Kuromoji、LM 等の追加メモリが必要。
- 人名・地名追加辞書、Wikipedia、新語、絵文字、英語、任意 n-gram 辞書は組み込まない。

## 移植時の変更

`SuccinctBitVector.kt`, `Node.kt`, `Other.kt` は上記 commit のソースをそのまま収録。
`GraphBuilder.kt` は元実装に未知のかなを保持する高コストノードを追加した。
これは通常の未知語処理であり、辞書のロード失敗を隠す代替変換ではない。
アセットが欠けたり壊れたりしていれば初期化は例外になる。

LOUDS と TokenArray は同じ保存形式・探索手順を使うランタイム部分の移植で、
ビルダー・圧縮書き出し・デスクトップパス依存を除き、boxed collection を primitive array
に置き換えた。読込例外を握りつぶさず、配列・品詞表の整合性を検証する。
トライ探索は接頭辞に一致しない時点で停止するよう修正した。
上流の `Extenstions.kt` から平仮名→片仮名変換を抽出し `ゔ` も処理する。

`FindPath.kt` は上流のラティスとコスト規約を引き継ぐ Viterbi/A* の移植。
接続コストには負値があるため、部分経路のコストだけを優先する上流の列挙方法を、
後向き Viterbi で求める正確な残コストを用いた A* に置き換えた。
探索量は 20,000 展開／キュー 10,000 経路に制限するため、極端に曖昧な読みでは
要求した候補数より少なくなることがある。入力は最大 64 文字、出力は最大 20 候補。
候補を重複排除し、64 エントリの LRU キャッシュを使う。

## 文脈の読み

[Kuromoji IPADIC 0.9.0](https://github.com/atilika/kuromoji/tree/0.9.0)
を Maven Central から依存として取り込む（辞書込み約 13 MB の JAR）。
`Token.reading` を平仮名化し、助詞「は」「へ」などの表記を維持する。
発音フィールドは使用しない。未知語は元表記を残し、LM のトークナイザ側で扱う。
人名などの多義的な漢字の読みは形態素解析による推定なので必ずしも正解にならない。

## 再取得と検証

```powershell
./tools/conversion/prepare_sumire.ps1 -VerifyOnly
./tools/conversion/prepare_sumire.ps1
./gradlew.bat :app:testDebugUnitTest --tests '*conversion.*'
./gradlew.bat :app:connectedDebugAndroidTest
```

通常のビルドでは再取得不要。5 ファイルはすでに同梱済み。
取得スクリプトは固定版の公式配布アーカイブと各抽出ファイルの SHA-256 を検証する。
テストは本物の同梱辞書で「にほんご→日本語」、複数語、未知のかな、文脈読みを検証し、
負の接続コストを持つラティスの候補順も確認する。
Android instrumentation は APK 内の ZIP と Kuromoji 辞書の classloader 経由読込を確認する。

## ライセンス

コードの MIT、Mozc 派生辞書の各条件、Kuromoji の Apache-2.0 と辞書条件を
`app/src/main/assets/conversion/licenses/` に同梱した。
上流配布物の第三者通知には、今回使わない追加辞書についての説明も含まれる。
辞書全体を MIT と扱わず、バイナリ再配布時にもこれらの通知を保持する。
