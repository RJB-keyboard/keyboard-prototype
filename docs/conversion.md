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

## グライド探索中の辞書スコア

`SumireKanaKanjiConverter.readingLexicon` は、漢字変換用に読み込んだ読みトライ、
単語コスト、品詞接続行列を共有する。追加の辞書ファイルや二重ロードは不要。
`SumireReadingLexicon` のセッションをグライドのデコードごとに作り、
読み候補をビームから落とす**前**に `evaluate(reading, complete)` を呼ぶ。
A の軌跡評価は意味に依存しないまま、混合スコアから `dictionaryWeight × cost` を引く。
`cost` は小さい方が良い辞書の評価値で、正規化された確率ではない。

探索は直前の読み状態から一文字ずつトライを進め、単語末尾ごとに
単語コスト・前後の品詞接続コスト・語境界ペナルティを加算する。
同じ読み・左右品詞の表記違いは最小コストにまとめる。
単語を一つに限定せず、活用形や助詞を含む複数語の経路も比較する。
完成した入力では EOS への接続も採点し、単に辞書に接頭辞があるだけの読みは
完成語とはみなさない。

途中の読みは、まだ続く辞書語の接頭辞であれば「語境界＋文字数に比例する仮コスト」で
維持する。長い単語が末尾に到達する前に落ちることを防ぐための近似であり、
将来の語コストを保証する A* 下界ではない。
辞書にない一文字を通る高コスト経路も常に用意し、その経路で消費した文字数を返す。
未知の名前や新語を禁止する機能ではなく、既知の語・接続を優先する機能である。

調整値は `SumireLexiconConfig` で指定できる。

| パラメータ | 初期値 | 意図 |
| --- | ---: | --- |
| `costScale` | `1 / 5000` | 辞書コスト 5000 を混合スコアの 1 点にする |
| `wordBoundaryCost` | `1500` | 短い語を無理に多数つなぐ経路に 1 語あたり 0.3 点加算 |
| `unknownCharacterCost` | `20000` | 未知の一文字に 4 点＋境界分を加算 |
| `unfinishedCharacterCost` | `1000` | 未完の辞書語を一文字 0.2 点で暫定評価 |
| `maxCachedPrefixes` | `256` | デコードごとの読み状態 LRU 上限 |
| `maxCachedTokenReadings` | `512` | デコードごとの品詞・最小単語コスト LRU 上限 |
| `maxPosStates` | `64` | 各読み末尾に残す右品詞状態の上限 |

値は初期の調整用であり、実機の雑な軌跡で最適化した値ではない。
接続コストに負値があるため、右品詞状態の上限に達した場合は厳密な最短経路を
取り逃す可能性がある。探索スコア用の読みは最大 128 文字に制限し、デコードのキャンセルを確認する。
後段の漢字変換の上限は引き続き 64 文字で、IME のデコーダは既定で最大 32 文字を使う。
全語彙走査や読みごとの N-best 漢字変換は行わず、セッションを破棄すればキャッシュも消える。

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
読みの辞書スコアも実辞書の既知語・活用・複数語・未知かなで検証する。
小さな合成辞書では、未完接頭辞と完成語の区別、単語・品詞・EOS のコスト、
語の分割、同品詞トークンの集約、キャッシュ上限、割り込みを確認する。
Android instrumentation は APK 内の ZIP と Kuromoji 辞書の classloader 経由読込を確認する。

## ライセンス

コードの MIT、Mozc 派生辞書の各条件、Kuromoji の Apache-2.0 と辞書条件を
`app/src/main/assets/conversion/licenses/` に同梱した。
上流配布物の第三者通知には、今回使わない追加辞書についての説明も含まれる。
辞書全体を MIT と扱わず、バイナリ再配布時にもこれらの通知を保持する。
