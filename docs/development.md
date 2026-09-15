# 開発手順

[READMEに戻る](../README.md) · [作業指針](../AGENTS.md)

## 必要な環境

JDK 25とAndroid SDK（Platform 37.0、Build Tools 36.0.0）を使用します。Gradleは同梱のWrapperで実行します。以下のコマンドはリポジトリのルートで実行してください。

モデル取得スクリプトにはPowerShellを使用します。Linux／macOSでは `pwsh` が必要です。

## ビルド準備

Linux／macOS:

```sh
pwsh -File tools/prepare_hiragana_model.ps1
```

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File tools/prepare_hiragana_model.ps1
```

モデルは固定リビジョンとSHA-256で検証し、`app/src/main/assets/models/hiragana-gpt2-xsmall/model.onnx`（約85 MB）へ取得します。この大きなファイルはGit管理対象外です。Sumireの基本辞書はassetsに同梱しています。APKへの同梱後はすべてオフラインで動作します。

依存関係・ライセンス・再取得手順は [ひらがな言語モデル](hiragana-model.md) と [変換エンジン](conversion.md) を参照してください。

Android Studioでプロジェクトを開き、実機またはエミュレーターで `app` をRunします。端末側の有効化は [操作ガイド](usage.md#キーボードを有効にする) を参照してください。

## 検証

Linux／macOSでCIと同じチェックを実行:

```sh
./gradlew --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Windows:

```powershell
.\gradlew.bat --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

単体テストは同梱の辞書を使い、ONNXモデルのダウンロードや端末接続なしで実行できます。モデルなしでもAPKのビルドはできますが、IMEの候補生成は利用できません。

### 端末テスト

[ビルド準備](#ビルド準備)でモデルを取得し、USBデバッグを許可した端末またはエミュレーターで実モデル・辞書・UIのテストを実行します。

Linux／macOS:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Windows:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

### GitHub Actions

[Android CI](../.github/workflows/android-ci.yml) は、`main`へのpush・Pull Request・手動実行に対応します。作業ブランチはPull Requestの作成・更新時にチェックし、pushとの二重実行を防ぎます。

- 単体テスト、Android Lint、APKビルドの3ジョブを最大3並列で実行します。APKビルドではデバッグAPKと端末テストAPKをまとめてビルドします。
- 1ジョブが失敗しても残りのチェックは継続します。全ジョブの成功を、従来と同じ名前の `Unit tests, lint and build` チェックで判定します。
- Gradleの依存関係をキャッシュし、同じブランチ／PRの古い実行をキャンセルします。
- 成功・失敗にかかわらず、生成されたレポートを `android-check-reports-unit-tests` と `android-check-reports-lint` に14日間保存します。
- エミュレーターでの端末テスト実行はCIに含みません。上記の `connectedDebugAndroidTest` で実行します。

### レポートと成果物

ローカルのレポートは `app/build/reports/tests/testDebugUnitTest/index.html` と `app/build/reports/lint-results-debug.html`、JUnit XMLは `app/build/test-results/testDebugUnitTest/` に出力します。

APK: `app/build/outputs/apk/debug/app-debug.apk`

## 検証範囲と結果の読み方

テストでは、Aの正規化・通過点・繰り返し・濁音／小書き文字、Bによる候補順位の変化、混合確率、異常な入力、中断、古い結果の破棄、実辞書の漢字変換と読み戻しを確認します。端末テストでは実ONNXモデルの分布・文脈依存・バッチ整合性と、既存のタッチ・候補選択・キャンセル動作も検証します。

認識・順位付けを変更したときは、文字抜けの改善と不要な文字追加の両方を確認してください。実行できない検証は理由を報告し、合成軌跡での結果と実際の入力での精度を区別します。

条件ごとの比較結果や残る制約は [探索エンジンの検証](engine.md#検証) と [変換候補の比較結果](conversion.md#比較結果と再現) を参照してください。少数の合成軌跡や固定読みの評価であり、自由な入力の認識率を保証するものではありません。
