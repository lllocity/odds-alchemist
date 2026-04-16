# 実装方針: Odds Alchemist

> **参照タイミング**: 実装・修正作業の前に必ず確認すること。プロジェクト全体の背景は `docs/context.md` を参照。

---

## バックエンド

### スクレイピングのルール (Jsoup)
- **対象サイト**: 現在は「Yahoo!スポナビ競馬」を対象としている。
- **パース方針**: HTMLのクラス名や構造は変更されやすいため、特定のクラス名に過度に依存せず、**「テキストのパターンマッチ（正規表現）」** や **「テーブル列のインデックス」** を組み合わせた柔軟で堅牢な抽出ロジック（`RaceOddsParser.java`）を維持すること。
- **データ抽出要件**: 馬番（数値）、馬名（文字列）、単勝オッズ（数値）、複勝オッズ（下限・上限の数値）を確実に抽出する。

### Google Sheets APIのルール
- **メソッド一覧**:
  - `appendData(range, values)`: 末尾追記のみ（OddsData / Alerts 用）
  - `readData(range)`: データ読み込み（値なしは空リストを返す）
  - `clearAndWriteData(range, values)`: クリア後に全件上書き（Targets 用）。values が空の場合はクリアのみ。
- **データマッピング**: `OddsSyncService.java` でスプレッドシートに書き込む際、以下の列順序を厳守すること。
  - **オッズシート**（`sheetRange` で指定、範囲: `OddsData!A:H`）:
    - A列: 取得日時 (形式: `yyyy/MM/dd HH:mm:ss`)
    - B列: 対象URL（レース一意識別のためURLを使用）
    - C列: レース名
    - D列: 馬番
    - E列: 馬名
    - F列: 単勝オッズ
    - G列: 複勝オッズ（下限）
    - H列: 複勝オッズ（上限）
  - **アラートシート** (`Alerts!A:G`):
    - A列: 検知日時 (ISO-8601形式)
    - B列: 対象URL
    - C列: レース名
    - D列: 馬番
    - E列: 馬名
    - F列: 検知タイプ
    - G列: 該当数値
- **アラート永続化**: 異常検知後に `saveAlertsToSheet()` で `Alerts!A:G` へ Append する。書き込み失敗は `try-catch` で捕捉してERRORログのみ出力し、スクレイピング処理を止めない。
- **レース一意識別**: 同名レースが同日に複数存在しうるため、`OddsData.url` フィールドとキャッシュキー（`"URL:馬番"`）はURLで一意識別する。パーサーは `url=null` で返し、`OddsSyncService` が `targetUrl` を付与する。

### 監視対象URLの管理パターン (TargetUrlStore / OddsScrapingScheduler)
- `TargetUrlStore` は `ConcurrentHashMap<String, TargetUrlInfo>` でURLを管理する `@Service`。起動時に `@PostConstruct loadFromSheet()` で `Targets!A:C` シートから復元する。
- `TargetUrlInfo` は URL・最終実行時間・次回予定時間を持つ内部 record（`null` 許容）。
- REST API (`OddsTargetsController`) で動的なURL登録・削除を行う: `POST/DELETE /api/odds/targets`。
- URL登録時: `addUrl()` → `persistToSheet()` で即時 Sheets 反映 → 即時fetchを `CompletableFuture.runAsync()` で非同期実行 → 完了後 `scheduler.scheduleUrl(url)` でスケジュール開始。
- URL削除時: `removeUrl()` → `persistToSheet()` → `scheduler.cancelUrl(url)` → `oddsSyncService.clearCachedStartTime(url)`。
- **`persistToSheet()`**: `clearAndWriteData("Targets!A:C", rows)` で全件上書き。失敗時は ERROR ログのみ（インメモリの変更は確定済み）。
- **`updateExecutionTimes(url, lastExec, nextSched)`**: インメモリのみ更新。`persistToSheet()` は呼ばない（`OddsScrapingScheduler.scrapeAndReschedule()` が責務を担う）。
- `OddsScrapingScheduler` は URLごとに独立した `ScheduledFuture<?>` を `ConcurrentHashMap<String, ScheduledFuture<?>> taskMap` で管理し、自己再スケジュール方式で動作する。
- 起動時復元: `@EventListener(ApplicationReadyEvent.class) restoreFromStore()` で `targetUrlStore.getUrls()` を参照し、各URLの初回スクレイピングを非同期で開始してスケジュールを再開する。
- スクレイピング完了後: `scrapeAndReschedule()` が `updateExecutionTimes()` → `persistToSheet()` を呼んで Sheets の B・C列を更新する。
- スクレイピング間隔は `OddsSyncService.getCachedStartTime(url)` の発走時刻から動的算出（30分/5分/1分）。

### アーキテクチャと品質
- クラスの責務を単一にし、肥大化を防ぐ（例: 取得処理、パース処理、保存処理は別クラスに分割する）。
- オッズパース処理などの複雑なロジックに対しては、JUnit 5を用いた単体テストを必ず記述し、想定されるHTMLパターン（正常系・異常系・一部データ欠損）を網羅すること。

### CORS設定パターン
- 各 `@RestController` に `@CrossOrigin(origins = "http://localhost:3000")` を付与するシンプルなパターンを採用している（管理用 FE との通信用）。
- 閲覧用 FE（`frontend-viewer/`）はバックエンドに直接アクセスしないため、CORS 設定は不要。
- 複数のコントローラーが同じ `/api/odds` パスを共有してもSpringは正常に処理できる（HTTPメソッドとパスの組み合わせが一意であればよい）。

### Spring Boot バージョン注意事項
- 本プロジェクトは **Spring Boot 4.0.x** を使用している（Jackson 3 / `tools.jackson.*` 名前空間）。
- `application.yaml` の `spring.jackson.serialization` によるJackson設定はSpring Boot 4では正常に機能しない場合がある。Jackson の挙動を変更したい場合は Java の `@Configuration` クラスで行うか、DTOフィールドを `String` 型に変換してサービス層でフォーマットする方針を取ること。

---

## フロントエンド

### TypeScriptの型定義ルール
- バックエンドのDTOが変更された場合は、対応する型定義ファイルを**必ず同時に更新**すること。型の不一致はランタイムエラーの原因となる。
  - 管理用 FE: `frontend/app/types/`
  - 閲覧用 FE: `frontend-viewer/app/types/`（管理用 FE と共有しない独立した定義）
- DTOの日時フィールドはバックエンドで `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")` でフォーマットした `String` として渡し、TypeScript側でも `string` 型で受け取る。
- `alertType` など値が有限のフィールドは `type AlertType = '支持率急増' | '順位乖離' | 'トレンド逸脱'` のようにリテラル型 Union で定義し、`any` や `string` で曖昧にしない。

### ディレクトリ構成

FE は機能別に2プロジェクトに分かれている。ファイルの配置・インポートパスは編集対象のプロジェクトに合わせること。

**管理用 FE (`frontend/`)**
- 型定義: `frontend/app/types/` 配下（例: `targetUrl.ts`）
- UIコンポーネント: `frontend/app/components/` 配下
- グラフ・アラート関連コンポーネント（`AlertList.tsx`, `OddsTrendChart.tsx`）と型定義（`oddsAlert.ts`）は**閲覧用 FE に移管済み**。管理用 FE には存在しない。

**閲覧用 FE (`frontend-viewer/`)**
- 型定義: `frontend-viewer/app/types/` 配下（例: `oddsAlert.ts`, `oddsHistory.ts`）
- UIコンポーネント: `frontend-viewer/app/components/` 配下（例: `AlertList.tsx`, `OddsTrendChart.tsx`）
- API Route Handlers: `frontend-viewer/app/api/` 配下（Sheets 直読み）
- 認証設定: `frontend-viewer/auth.ts`（フル設定）, `frontend-viewer/auth.config.ts`（edge-compatible）
- プロキシ（認証ミドルウェア）: `frontend-viewer/proxy.ts`（Next.js 16 では `middleware.ts` でなく `proxy.ts`）

両プロジェクト共通: `@/app/...` の絶対パスインポートを使用（`tsconfig.json` の `"paths": {"@/*": ["./*"]}` 参照）。

### ポーリング実装パターン
- `useCallback` でフェッチ関数を定義し、`useEffect` で初回即時実行 + `setInterval` でポーリングを設定する。
- クリーンアップ関数 `return () => clearInterval(timer)` を忘れずに書くこと（コンポーネントアンマウント時のメモリリーク防止）。
- **管理用 FE**: バックエンド URL は `process.env.NEXT_PUBLIC_API_BASE_URL` 経由で取得し、`useCallback` の依存配列に含めること。
- **閲覧用 FE**: API Route Handlers（`/api/...`）を相対パスで呼ぶ。`apiBaseUrl` は不要。`useCallback` の依存配列に `apiBaseUrl` を含めないこと。

### 検知タイプ別スタイリングのパターン
- `Record<AlertType, ConfigObject>` 形式の設定オブジェクトで、色・説明・値フォーマッタを一元管理する。
- 未知の検知タイプには `?? fallback` でデフォルトスタイルを適用し、型エラーを防ぐ。
- 値の単位は検知タイプによって異なる（支持率急増・トレンド逸脱: `%` 表示、順位乖離: 整数のランク差）ため、`formatValue: (v: number) => string` をConfig内に定義してロジックを分散させない。

### Recharts グラフのパターン（Next.js App Router）
- コンポーネントに `'use client'` を付与すること（Recharts は SSR 非対応）
- `ResponsiveContainer width="100%" height={300}` でレスポンシブ対応
- `Tooltip` の `formatter` / `labelFormatter` の型は Recharts 内部型が strict なため `any` を使用すること（`number | string` では不足する場合あり）
- `Line` の `connectNulls` を設定すると null 値があっても線が途切れない
- X軸に文字列の日時を渡す場合は `tickFormatter` で `"HH:mm"` 形式に変換する
- `dataKey` は TypeScript 型のフィールド名と一致させること

### NextAuth v5 + Google OAuth の実装パターン（閲覧用 FE）

- **Next.js 16 では `middleware.ts` を `proxy.ts` に改名すること**（Next.js 16 の仕様変更）。
- 認証設定は `auth.ts` 1ファイルに統合すること。Next.js 15 時代に必要だった `auth.config.ts`（edge-compatible）と `auth.ts`（フル設定）の2ファイル分離は、Next.js 16 + proxy.ts 環境では不要（`proxy.ts` は Node.js Runtime で動作するため）。
- `proxy.ts` での認証判定は `auth()` ラッパーや `authorized` コールバックを使わず、`getToken`（`next-auth/jwt`）で直接 JWT を読む方式を採用すること。`authorized` コールバックは Next.js 16 の proxy context で正常動作しない。
- Google プロバイダーに `authorization: { params: { prompt: 'select_account' } }` を設定すること（Google OAuth セッションが残っていても毎回アカウント選択を強制させるため）。
- `signIn` コールバックで `profile?.email !== ALLOWED_EMAIL` の場合に `return false` を返し、セッション作成を阻止すること。
- `proxy.ts` で不正メールのリダイレクト時は `response.cookies.delete('authjs.session-token')` と `response.cookies.delete('__Secure-authjs.session-token')` で両方のセッションクッキーを削除すること。
- NextAuth v5 の built-in サインインページ（`/api/auth/signin`）はリダイレクトループを引き起こすため、`pages: { signIn: '/login', error: '/login' }` でカスタムページに向けること。
- 環境変数: `NEXTAUTH_SECRET`（または `AUTH_SECRET`）、`NEXTAUTH_URL`、`AUTH_GOOGLE_ID`、`AUTH_GOOGLE_SECRET`、`ALLOWED_EMAIL`。

### カスケードドロップダウンのパターン（URL → 馬）
- URLドロップダウン変更時に `setSelectedHorse('')` / `setHorses([])` / `setChartData(null)` をリセットすること
- 馬ドロップダウンは URL が選択済みの場合のみ有効化（`disabled={!selectedUrl || horses.length === 0}`）
- API 呼び出しは `encodeURIComponent(url)` でクエリパラメータをエスケープすること

### フロントエンドのURL管理パターン
- `GET /api/odds/targets` で登録済みURL一覧を取得し、初回マウント時に `useEffect` + `fetchTargetUrls` で読み込む。
- `POST /api/odds/targets` でURL登録（body: `{ url: string }`）、`DELETE /api/odds/targets` でURL削除（body: `{ url: string }`）。
- 登録・削除の成功時はレスポンスの `data.urls` を `setTargetUrls` にセットして再取得なしで表示を更新する。
- 通信エラーは `try-catch` + `console.warn` で処理し、UIを壊さない。
