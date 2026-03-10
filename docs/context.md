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

### 異常検知
- `OddsAnomalyDetector` がロジックA（支持率急増）・B（順位乖離）・C（トレンド逸脱）を実行。
- バックエンド起動後の全アラートを累積保持し、`GET /api/odds/alerts` でフロントエンドに提供。

### 永続化
- オッズデータ（A〜H列 8列構成）は `sheetRange` シートへ Append のみ。
- アラートデータは `Alerts!A:G` シートへ Append のみ。

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
<!-- 今後の設計判断・背景・却下した選択肢などをここに追記する -->
