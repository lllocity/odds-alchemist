# プロジェクト概要: Odds Alchemist
JRA（日本中央競馬会）のオッズ情報を定期的に取得し、Google Sheetsに保存。その後、投資妙味のある「人気の盲点（支持率の急増、単複オッズの順位乖離など）」を検知してフロントエンドに通知するシステム。

## 基本的な振る舞いと制約
- **言語**: やり取り、コメント、ドキュメントはすべて日本語で行うこと。
- **堅牢性**: エラーでシステムを止めないこと。外部通信（スクレイピング、Google API）の失敗時は、必ず `try-catch` で捕捉し、SLF4J（バックエンド）または標準のロガー（フロントエンド）で詳細を `WARN` または `ERROR` レベルで出力すること。
- **自己完結**: ファイルを修正する際は、既存の動作を壊さないことを最優先とし、必要に応じて関連するテストコードも同時に修正・追加すること。

## 技術スタックとコマンド
### バックエンド (`/backend`)
- **環境**: Java 21, Spring Boot 4.0.x
- **主要ライブラリ**: Jsoup (HTMLパース), Google API Client (Sheets連携)
- **ビルド・実行**:
  - 開発起動: `./gradlew bootRun`
  - テスト実行: `./gradlew test`

### フロントエンド (`/frontend`)
- **環境**: Next.js 14+ (App Router), React, TypeScript
- **スタイリング**: Tailwind CSS
- **ビルド・実行**:
  - 開発起動: `npm run dev`
  - ビルド: `npm run build`

## システムアーキテクチャ概要
- **即時取得**: フロントエンドから `POST /api/odds/fetch` でワンショットのスクレイピング・検知を実行。
- **スケジュール監視**: `OddsScrapingScheduler` が `TargetUrlStore` に登録されたURLごとに独立した `ScheduledFuture` を持ち、自己再スケジュール方式で定期実行する。URLはフロントエンドから `POST /api/odds/targets` で動的登録のみ（起動時は空）。URL削除時は `cancelUrl()` でスケジュールも即時停止する。
- **動的間隔**: 各URLの発走時刻キャッシュを `OddsSyncService.getCachedStartTime()` で参照し、残り時間に応じて 30分/15分/5分/1分 の4段階で間隔を切り替える。
- **異常検知**: `OddsAnomalyDetector` がロジックA（支持率急増）・B（順位乖離）・C（トレンド逸脱）を実行。バックエンド起動後の全アラートを累積保持し、`GET /api/odds/alerts` でフロントエンドに提供。
- **永続化**: オッズデータ（A〜H列 8列構成）は `sheetRange` シートへ、アラートデータは `Alerts!A:G` シートへそれぞれ Append のみ。
- **レース識別**: 同名レースが同日に複数存在しうるため、`OddsData.url` フィールドおよびキャッシュキーはURLで一意識別する。

## 実装時の参照ドキュメント
実装作業を行う際は、**必ず `docs/skills.md` を参照すること**。
スクレイピング・Google Sheets・CORS・TypeScript型定義・ポーリングなど、このプロジェクト固有のパターンと注意事項がバックエンド／フロントエンドに分けて記載されている。
