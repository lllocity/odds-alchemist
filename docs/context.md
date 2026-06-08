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

### 管理用フロントエンド (`/frontend`)
- **環境**: Next.js (App Router), React, TypeScript
- **スタイリング**: Tailwind CSS
- **機能**: スケジュール監視URL登録・削除、データ管理（シートクリア・スプレッドシートリンク）
- **起動**: Docker Compose（自宅ローカルのみ）
- **ビルド・実行**:
  - 開発起動: `npm run dev`
  - ビルド: `npm run build`

### 閲覧用フロントエンド (`/frontend-viewer`)
- **環境**: Next.js (App Router), React, TypeScript
- **スタイリング**: Tailwind CSS
- **機能**: オッズ推移グラフ・検知アラート・買いの掟（読み取り専用）
- **デプロイ先**: Vercel（外部からアクセス可能）
- **認証**: NextAuth v5 + Google OAuth（特定アカウントのみ許可）
- **データアクセス**: Google Sheets API を直接呼び出し（バックエンド不要）
- **追加パッケージ**: `next-auth@beta` `googleapis` `recharts`
- **ビルド・実行**:
  - 開発起動: `npm run dev -- --port 3001`
  - ビルド: `npm run build`

---

## システムアーキテクチャ

### データフロー
- **スケジュール監視**: `OddsScrapingScheduler` が `TargetUrlStore` に登録されたURLごとに独立した `ScheduledFuture` を持ち、自己再スケジュール方式で定期実行する。URLはフロントエンドから `POST /api/odds/targets` で動的登録のみ（起動時は空）。登録直後に即時フェッチが非同期実行される。URL削除時は `cancelUrl()` でスケジュールも即時停止する。
- **動的間隔**: 各URLの発走時刻キャッシュを `OddsSyncService.getCachedStartTime()` で参照し、残り時間に応じて 30分/5分/1分 の3段階で間隔を切り替える。
- **起動時復元**: `OddsScrapingScheduler` が `@EventListener(ApplicationReadyEvent.class)` で `TargetUrlStore.getUrls()` を参照し、各URLの初回スクレイピングを非同期で開始してスケジュールを再開する。

### フロントエンド構成

```
[自宅 Docker Compose]                    [Vercel]
  管理用 FE (frontend/)                   閲覧用 FE (frontend-viewer/)
    - URL登録                               - オッズ推移グラフ
    - データ管理         Google Sheets       - 検知アラート
         ↓              ↗        ↖          - 買いの掟
  BE (Spring Boot) ────┘          └──── Sheets API 直読み
                                          （read-only SA）
GCP サービスアカウント
  [既存] 読み書き → BE が使用
  [新規] 読み取り専用 → 閲覧用 FE が使用
```

閲覧用 FE は BE に依存せず Google Sheets を直接読む。
管理操作（URL登録・データクリア）は自宅ローカルからのみ実行可能。

### 異常検知
- `OddsAnomalyDetector` が以下のロジックを実行（11種のアラートタイプ）:
  - **ロジックA** 支持率急増（4番人気以下、前回比+2%以上）
  - **ロジックB** 順位乖離（単複人気順位差 3以上）+ 拡大中/解消中
  - **ロジックC** トレンド逸脱（5〜12番人気、当日初回比+5%以上）
  - **ロジックD** 支持率加速（4番人気以下、0.5%/分以上）
  - **ロジックE** フェーズ別逸脱（朝/30分前/10分前の基準点から+5%以上）
  - **ロジックF** オッズ断層の凝縮/拡散（隣接オッズ比率1.5倍以上の断層位置変化）
- アラートは Google Sheets `Alerts!A:G` へ Append。閲覧用 FE は Sheets から直接読む（BE 不要）。

### 永続化
- オッズデータ（A〜H列 8列構成）は `sheetRange` シートへ Append のみ。
- アラートデータは `Alerts!A:G` シートへ Append のみ。
- 監視対象URLは `Targets!A:C` シートへ上書き保存（clearAndWriteData）。起動時に `TargetUrlStore.loadFromSheet()` で復元し、再起動後も自動的に監視を再開する。

### レース識別
- 同名レースが同日に複数存在しうるため、`OddsData.url` フィールドおよびキャッシュキーはURLで一意識別する。

---

---

## 決定事項・議論ログ

### 予想戦略の体系化に関する議論（2026-06-08）

**背景**: オッズのみに依存した予想の限界が見えてきたことから、追加すべき情報源（馬体重・近走成績・コース適性等）を議論。スポーツナビの出馬表・前走情報・コース別/距離別成績ページが、低コストで複数の判断軸を一括取得できることを確認した。その後、議論は「どの情報を見るか」から「どう予想するか」に発展し、以下の戦略骨子を整理した。

**戦略骨子（初期仮説、未検証）**:
- **軸馬選定**: 「人気馬でも大穴でもない中穴ゾーン」を狙う。複数の独立した統計分析で「6番人気付近が単勝回収率のピーク」「1番人気の回収率は76〜78%程度」という傾向が収束して観測されており、favorite-longshot bias（本命の過剰支持）として構造的根拠がある。ゾーンの定義は固定の「オッズ◯倍」ではなく、レースごとに異なるオッズ分布に対応するため、人気順位ベース、理想的には既存ロジックF（オッズ断層検知）の出力を使った動的な定義が望ましい。
- **相手選定**: オッズ推移（トレンド）から「3着以内に来そうな馬」を拾う。既存の検知ロジックのうち、個別馬の評価上方修正を捉えるC（トレンド逸脱）・D（支持率加速）を主軸とし、A（支持率急増）・E（フェーズ別逸脱）は確度の補強材料として二段構成にする。B（順位乖離）・F（断層変化）は個別馬の評価ではなく「レース全体が荒れそうか」の判定に役割を分けて使う。

**信頼度ウィンドウ（初期仮説）**:
- 既存ロジックEのフェーズ概念（朝/30分前/10分前）を予想ロジックの信頼度判定にも拡張する。
- 層A（発走1時間以上前）＝トレンド計算の基準点・参照専用、層B（発走1時間前〜締切直前）＝意思決定の主材料、という二層構造とする。
- 出走取消等の異常系は、既存の堅牢性ルール（try-catch + WARN/ERRORログ）に沿って別途検知・除外する設計を組み込む。

**データ保持ポリシー**:
- オッズ・アラート・結果（着順）は、戦略を検証できるだけの期間（数カ月分目安）は手動削除しない。削除する場合は事前にCSV等で退避する。
- 検証に最低限必要な3項目: 最終オッズ、主要アラート発生有無、着順。

**3人格レビューの総合判断**:
- C・D中心の相手選定や軸ゾーンの定義は、現時点では「初期仮説」であり実績による検証を経ていない。設計ドキュメント上もその位置づけを明記し、データが数カ月分貯まった時点で「この選定方法は機能していたか」を再評価するレビューポイントを事前に設けることとした。

**今後の進め方**: 上記の信頼度ウィンドウ・相手選定ロジック・データ保持ポリシーを具体的なステップとして詳細化し、実装に進む。

---

### Steps 37〜39: 新戦略の実装（予定, 2026-06-08）

**Step 37: AI分析プロンプト改訂（信頼度ウィンドウ＋新戦略）**
- `frontend-viewer/app/api/odds/analysis/route.ts` — `buildHorsesData` に層A/B タグ付け（時刻差ヒューリスティック）、プロンプトの verdict 体系を全面改訂、`is_chaotic_race`/`chaotic_note` フィールドを JSON スキーマに追加
- `frontend-viewer/app/components/OddsAnalysis.tsx` — 型定義変更（`軸候補`/`相手候補`/`対象外`）、VERDICT_STYLES 更新、`buildBets` を単勝・馬単・三連単に刷新、荒れ判定バナーを追加
- 詳細: `docs/step37_ai_prompt_revision.md`

**設計決定（Step 37）:**
- getCachedStartTime（Java バックエンド）は frontend-viewer から呼び出せないため、時系列データの時刻差（差 ≥ 20分 → 層A、差 < 20分 → 層B）でポーリング区間を推定して代替する
- B（順位乖離）・F（断層変化）アラートは個別馬の verdict に影響させず `is_chaotic_race` 判定専用とする
- 三連単は `is_chaotic_race === true` のときのみ表示する

**Step 38: 出馬表・距離別成績のオンデマンドスクレイピング**
- `frontend-viewer/lib/scrapeRaceData.ts`（新規）— オッズURLからレースIDを抽出し、Yahoo!スポナビの出馬表ページ・距離別成績ページをスクレイプ
- `frontend-viewer/app/api/odds/analysis/route.ts` — `getOddsData`/`getAlerts` と並列でスクレイピングを実行し、Gemini プロンプトの「## アラート履歴」後に「## 出馬表情報」セクションを追記
- 取得データ: 騎手名・前走レース名・前走着順・馬体重増減・今走距離の着別度数（2ページのみ）
- 詳細: `docs/step38_race_data_scraping.md`

**設計決定（Step 38）:**
- ページ選定: `denma`（前走・馬体重・騎手）と `achievement/distance`（今走距離の実績）の2ページに絞る。grade・course・time ページは費用対効果が低いため対象外
- スクレイピング失敗時は `console.warn` のみで空文字を返し、オッズデータのみで分析を継続（堅牢性原則）
- `node-html-parser` を依存追加。クラス名への依存を最小化し、テーブル列インデックスとテキストパターンマッチで抽出する

**Step 39: 着順記録機能（戦略検証データの保全）**
- Google Sheets `Results!A:G` シートを新設し、レース終了後に 1〜3 着の馬番を記録する
- 閲覧用 FE に ResultRecorder コンポーネントを追加（URL選択 → 1着/2着/3着の馬番入力 → 送信）
- 詳細: `docs/step39_result_recording.md`

**進捗:**
- Step 37: ✅ 実装完了（2026-06-08）
- Step 38: 未着手
- Step 39: 未着手

---

### Step 25〜31: 閲覧用 FE (Vercel) 新設（進行中, 2026-04-16）

**背景**: 自宅 WiFi 外からオッズ推移・アラートを閲覧したいニーズが発生。
データは Google Sheets に集約済みのため、Sheets 直読みの閲覧専用 FE を Vercel にデプロイする構成を採用。

**意思決定**:
- **ストレージ移行はしない**: Sheets の行数は個人用途で許容範囲内。パフォーマンス劣化が顕在化した段階で Neon (PostgreSQL Serverless) への移行を検討する。
- **認証方式**: Google OAuth (NextAuth v5) を採用。パスワード認証も検討したが、意図（特定 Google アカウントのみ許可）が明確なため OAuth を選択。
- **読み取り専用 SA を別途作成**: 漏洩時の被害範囲を「Sheets が読まれる」止まりにするため、書き込み権限を持たない専用サービスアカウントを使用。
- **管理用 FE から閲覧系機能を削除（Step 30）**: グラフ・アラート・買いの掟は閲覧用 FE に移管。管理用 FE はURL登録・データ管理のみに絞る。

**進捗**:
- Step 23: GCP 読み取り専用 SA 作成 ✅
- Step 24: Google OAuth クライアント作成 ✅
- Step 25: frontend-viewer/ プロジェクト新規作成 ✅（コミット済み）
- Step 26: lib/sheets.ts 実装（Sheets クライアント） ✅（コミット済み）
- Step 27: API Route Handlers 実装 ✅（コミット済み）
- Step 28: NextAuth v5 + Google OAuth + proxy（認証ミドルウェア） ✅（コミット済み）
- Step 29: 閲覧用 page.tsx・コンポーネント移植 ✅（コミット済み）
- Step 30: 管理用 FE から閲覧系機能を削除・1カラム化 ✅（コミット済み）
- Step 31: Vercel デプロイ・NextAuth 認証設定 ✅（コミット済み）

**Step 28 実装上の注意点**:
- `middleware.ts` は Next.js 16 で `proxy.ts` に改名
- NextAuth の `authorized` コールバックは proxy context で動作しないため `getToken`（`next-auth/jwt`）で直接 JWT を読む方式に変更
- `auth.config.ts`（edge-compatible）と `auth.ts`（フル設定）に分離
- Google プロバイダーに `prompt: 'select_account'` を設定（Google OAuth セッションが残っても毎回アカウント選択を強制）
- `signIn` コールバックで ALLOWED_EMAIL 以外のセッション作成を阻止
- proxy で不正メールのセッションクッキーを強制削除

**実装ステップ詳細**: `docs/step23_gcp_readonly_sa.md` 〜 `docs/step31_vercel_deploy.md` を参照。

---

### Steps 32〜36 + AI分析拡張: 検知ロジック強化（2026-04）

**Step 32（AI分析機能）**:
- `frontend-viewer/app/api/odds/analysis/route.ts` — Gemini API（Flash Thinking）を使った馬券分析エンドポイント
- `frontend-viewer/app/components/OddsAnalysis.tsx` — 分析結果表示コンポーネント（verdict別カード・買い目推奨）
- 買い目推奨は三連複（本命×対抗×3着紐）・ワイドに特化

**Steps 33〜36（検知ロジック拡張）**:
- **Step 33 ロジックD**: 支持率の加速度検知（単位時間あたりの変化速度）
- **Step 34 ロジックE**: フェーズ別トレンド逸脱（朝/30分前/10分前の基準点ごとに独立評価）
- **Step 35 ロジックF**: オッズ断層（クリフ）検知 ＋ 凝縮/拡散の動的変化
- **Step 36 ロジックB拡張**: 順位乖離の変化方向（拡大中/解消中）

**設計決定事項**:
- `detect(List)` → `detect(List, Optional<LocalTime> startTime)` にオーバーロード追加（後方互換）
- フェーズ判定は `startTime` から相対時間で決定。発走後は `Optional.empty()` でスキップ
- 断層アラートの `horseNumber` は「断層直前の境界馬」を示す（レース全体シグナルの代表値）
- AI分析プロンプトのアラート定義を11種すべてに更新し、タイプ別 verdict 判定指針を追加

---

### Step 22: オッズ推移グラフ 実装完了 (2026-03-19)

**作成ファイル（バックエンド）**:
- `backend/.../dto/HorseDto.java` — 馬番・馬名の DTO
- `backend/.../dto/OddsHistoryItemDto.java` — オッズ時系列 1件の DTO（detectedAt, winOdds, placeOddsMin, placeOddsMax）
- `backend/.../service/OddsHistoryService.java` — OddsData!A:H を読み込み、URL一覧・馬一覧・時系列データを返す
- `backend/.../controller/OddsHistoryController.java` — `/api/odds/history/urls`, `/api/odds/history/horses`, `/api/odds/history`

**作成ファイル（フロントエンド）**:
- `frontend/app/types/oddsHistory.ts` — OddsHistoryItem / HorseOption 型定義
- `frontend/app/components/OddsTrendChart.tsx` — URL・馬名カスケードドロップダウン + Recharts 折れ線グラフ

**変更ファイル**: `frontend/app/page.tsx`（OddsTrendChart をアラート一覧の下に追加）

**設計決定事項**:
- OddsData は当日分のみ・数千行以下のため Sheets 全件読み込みで許容
- グラフライブラリは Recharts を採用（Next.js App Router との相性◎、スタンダード）
- 3エンドポイント方式（URL一覧 → 馬一覧 → 時系列）でカスケードドロップダウンを実現
- Sheets API 失敗時は `try-catch` + `WARN` ログのみ、空リストを返してシステムを止めない
- データ削除後は「該当データがありません」メッセージをフロントエンドで表示

**テスト件数**: 合計88件（BUILD SUCCESSFUL 確認済み）
- OddsHistoryServiceTest: 新規作成 → 8件
- OddsHistoryControllerTest: 新規作成 → 4件（Step 22）

---

### Step 21: Docker化 実装完了 (2026-03-15)

**作成ファイル**: `backend/Dockerfile`、`frontend/Dockerfile`、`docker-compose.yml`、`.env.example`、`docs/setup_guide.md`
**変更ファイル**: `frontend/next.config.ts`（`output: 'standalone'` 追加）、`README.md`（Docker 起動手順を主軸に更新）、`.gitignore`（`.env` / `logs/` 追加）

**起動コマンド**: `docker compose up --build`（初回 / コード変更時）、`docker compose up -d`（2回目以降）、`docker compose down`（停止）

**設計決定事項**（レビュー時と同じ）:

### Step 21: Docker化 設計レビュー (2026-03-15)
- **`application-secret.yaml` の扱い**: Slack Webhook URL は `SPRING_SLACK_WEBHOOK__URL` 環境変数でオーバーライド。Spring Boot の環境変数→プロパティ変換ルール（`.` → `_`、大文字化）を利用する。
- **Spring Boot Admin client URL**: コンテナ内では `localhost` は自分自身を指さない。`docker-compose.yml` の `environment` で `SPRING_BOOT_ADMIN_CLIENT_URL=http://backend:8080/admin`（Compose サービス名）にオーバーライドする。
- **永続化ボリューム**: Step 19 で永続化は Google Sheets に統一済み。ローカルDBのマウントは不要。ログファイル `/tmp/odds-alchemist/app.log` のみ `./logs` にマウントする。
- **`depends_on` に healthcheck 条件を追加**: `condition: service_healthy` + `/actuator/health` ポーリングで、Spring Boot が本当に Ready になってからフロントエンドを起動する。
- **Next.js standalone モード**: `next.config.js` に `output: 'standalone'` を設定し、実行イメージを最小化する。

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
