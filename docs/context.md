# プロジェクトコンテキスト: Odds Alchemist

## プロジェクト概要
JRA（日本中央競馬会）のオッズ情報を定期的に取得し、Google Sheetsに保存。その後、投資妙味のある「人気の盲点（支持率の急増、単複オッズの順位乖離など）」を検知してフロントエンドに通知するシステム。

---

## 技術スタック

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

---

## システムアーキテクチャ

### データフロー
- **即時取得**: フロントエンドから `POST /api/odds/fetch` でワンショットのスクレイピング・検知を実行。
- **スケジュール監視**: `OddsScrapingScheduler` が `TargetUrlStore` に登録されたURLごとに独立した `ScheduledFuture` を持ち、自己再スケジュール方式で定期実行する。URLはフロントエンドから `POST /api/odds/targets` で動的登録のみ（起動時は空）。URL削除時は `cancelUrl()` でスケジュールも即時停止する。
- **動的間隔**: 各URLの発走時刻キャッシュを `OddsSyncService.getCachedStartTime()` で参照し、残り時間に応じて 30分/15分/5分/1分 の4段階で間隔を切り替える。
- **起動時復元**: `OddsScrapingScheduler` が `@EventListener(ApplicationReadyEvent.class)` で `TargetUrlStore.getUrls()` を参照し、各URLの初回スクレイピングを非同期で開始してスケジュールを再開する。

### 異常検知
- `OddsAnomalyDetector` がロジックA（支持率急増）・B（順位乖離）・C（トレンド逸脱）を実行。
- バックエンド起動後の全アラートを累積保持し、`GET /api/odds/alerts` でフロントエンドに提供。

### 永続化
- オッズデータ（A〜H列 8列構成）は `sheetRange` シートへ Append のみ。
- アラートデータは `Alerts!A:G` シートへ Append のみ。
- 監視対象URLは `Targets!A:C` シートへ上書き保存（clearAndWriteData）。起動時に `TargetUrlStore.loadFromSheet()` で復元し、再起動後も自動的に監視を再開する。

### レース識別
- 同名レースが同日に複数存在しうるため、`OddsData.url` フィールドおよびキャッシュキーはURLで一意識別する。

---

## 監視ダッシュボード（Spring Boot Admin）
- **URL**: `http://localhost:8080/admin`
- ログストリーム・ヘルス・メモリ・スレッド等をブラウザで確認可能。
- `/tmp/odds-alchemist/app.log` にログファイル出力（Admin UI 上でリアルタイム閲覧可能）。
- `spring.boot.admin.client.url` には `http://localhost:8080/admin`（context-path 含む）を設定すること。

---

## 決定事項・議論ログ

### Step 20: Slack Webhook通知連携 設計レビュー (2026-03-13)
- **LINE Notify → Slack Incoming Webhook に変更**: LINE Notify は 2025年3月31日にサービス終了済みのため使用不可。Slack Incoming Webhook を採用（Webhook URL 1本で POST するだけで完結）。
- **スクレイピングとアラート通知の分離**: `OddsAnomalyDetector.detect()` は毎回全アラートを返す（Sheets・フロントエンド用）。通知の絞り込みは `SlackNotifyClient` が担い、検知ロジックに送信済み管理を混入させない。
- **初回検知のみ通知（日次ユニーク）**: 重複送信防止キー = `"URL:馬番:alertType:yyyy-MM-dd"`。同日に同条件が何度検知されても Slack 通知は1回のみ。通信失敗時はキャッシュに追加せず次回再試行対象とする。
- **集約ポリシー**: 1スクレイピングで複数アラートが出た場合は1通にまとめて送信（スパム防止）。

### Step 19: 監視対象URLの永続化 (2026-03-12)
- **ストレージ**: Google Sheets の `Targets!A:C` シートへ統一（OddsData / Alerts と同一スプレッドシート）。
- **`updateExecutionTimes` は `persistToSheet` を呼ばない**: スクレイピング完了ごとに時刻更新＋Sheets書き込みを行うが、その責務は `OddsScrapingScheduler.scrapeAndReschedule()` が担う設計。頻繁な書き込みを一箇所に集約してインメモリの変更とシートへの永続化タイミングを分離した。
- **`scrapeAndReschedule` を package-private に変更**: `private` のままではテストから直接呼べないため、テスト可能性を優先して変更。Spring の AOP プロキシを通らない内部呼び出しであるため副作用なし。
- **`@PostConstruct` vs `@EventListener`**: URL読み込みは `TargetUrlStore.@PostConstruct`（DI完了後に即時実行）、スクレイピング再開は `OddsScrapingScheduler.@EventListener(ApplicationReadyEvent)`（Spring Context 完全起動後）で責務を分離した。
