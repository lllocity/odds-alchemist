# 実装方針: Odds Alchemist

> **参照タイミング**: 実装・修正作業の前に必ず確認すること。プロジェクト全体の背景は `docs/context.md` を参照。

---

## バックエンド

### スクレイピングのルール (Jsoup)
- **対象サイト**: 現在は「Yahoo!スポナビ競馬」を対象としている。
- **パース方針**: HTMLのクラス名や構造は変更されやすいため、特定のクラス名に過度に依存せず、**「テキストのパターンマッチ（正規表現）」** や **「テーブル列のインデックス」** を組み合わせた柔軟で堅牢な抽出ロジック（`RaceOddsParser.java`）を維持すること。
- **データ抽出要件**: 馬番（数値）、馬名（文字列）、単勝オッズ（数値）、複勝オッズ（下限・上限の数値）を確実に抽出する。

### Google Sheets APIのルール
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
- `TargetUrlStore` はスレッドセーフな `CopyOnWriteArrayList` でURLを保持する `@Service`。起動時は空。
- REST API (`OddsTargetsController`) で動的なURL登録・削除のみ行う: `POST/DELETE /api/odds/targets`。
- URL登録時: 即時fetchを `CompletableFuture.runAsync()` で非同期実行 → 完了後 `scheduler.scheduleUrl(url)` でスケジュール開始。
- URL削除時: `scheduler.cancelUrl(url)` でスケジュール即時停止 + `oddsSyncService.clearCachedStartTime(url)` でキャッシュクリア。
- `OddsScrapingScheduler` は URLごとに独立した `ScheduledFuture<?>` を `ConcurrentHashMap<String, ScheduledFuture<?>> taskMap` で管理し、自己再スケジュール方式で動作する。
- スクレイピング間隔は `OddsSyncService.getCachedStartTime(url)` の発走時刻から動的算出（30分/15分/5分/1分）。

### アーキテクチャと品質
- クラスの責務を単一にし、肥大化を防ぐ（例: 取得処理、パース処理、保存処理は別クラスに分割する）。
- オッズパース処理などの複雑なロジックに対しては、JUnit 5を用いた単体テストを必ず記述し、想定されるHTMLパターン（正常系・異常系・一部データ欠損）を網羅すること。

### CORS設定パターン
- 各 `@RestController` に `@CrossOrigin(origins = "http://localhost:3000")` を付与するシンプルなパターンを採用している。
- 複数のコントローラーが同じ `/api/odds` パスを共有してもSpringは正常に処理できる（HTTPメソッドとパスの組み合わせが一意であればよい）。

### Spring Boot Admin の設定パターン
- `de.codecentric:spring-boot-admin-starter-server:4.0.0` と `spring-boot-admin-starter-client:4.0.0` を使用。
- メインクラスに `@EnableAdminServer` を付与する。
- `spring.boot.admin.context-path=/admin` で Admin UI のパスを `/admin` に設定（`/api/**` との競合を回避）。
- `spring.boot.admin.client.url` には context-path を含む完全なURLを指定すること: `http://localhost:8080/admin`。
- `management.endpoints.web.exposure.include=*` で Actuator エンドポイントを全公開する。
- `logging.file.name=/tmp/odds-alchemist/app.log` でログをファイル出力すると Admin UI 上でログストリームが確認できる（絶対パス指定で起動ディレクトリに依存しない）。
- Spring Boot Admin 4.0.0 は Spring Security を含まないため、追加の Security 設定は不要。
- Admin UI アクセス: `http://localhost:8080/admin`

### Spring Boot バージョン注意事項
- 本プロジェクトは **Spring Boot 4.0.x** を使用している（Jackson 3 / `tools.jackson.*` 名前空間）。
- `application.yaml` の `spring.jackson.serialization` によるJackson設定はSpring Boot 4では正常に機能しない場合がある。Jackson の挙動を変更したい場合は Java の `@Configuration` クラスで行うか、DTOフィールドを `String` 型に変換してサービス層でフォーマットする方針を取ること。

---

## フロントエンド

### TypeScriptの型定義ルール
- バックエンドのDTOが変更された場合は、対応する `frontend/app/types/` 配下の型定義ファイルを**必ず同時に更新**すること。型の不一致はランタイムエラーの原因となる。
- DTOの日時フィールドはバックエンドで `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")` でフォーマットした `String` として渡し、TypeScript側でも `string` 型で受け取る。
- `alertType` など値が有限のフィールドは `type AlertType = '支持率急増' | '順位乖離' | 'トレンド逸脱'` のようにリテラル型 Union で定義し、`any` や `string` で曖昧にしない。

### ディレクトリ構成
- 型定義: `frontend/app/types/` 配下に機能単位で配置（例: `oddsAlert.ts`）。
- UIコンポーネント: `frontend/app/components/` 配下に配置（例: `AlertList.tsx`）。
- `@/app/...` の絶対パスインポートを使用（`tsconfig.json` の `"paths": {"@/*": ["./*"]}` 参照）。

### ポーリング実装パターン
- `useCallback` でフェッチ関数を定義し、`useEffect` で初回即時実行 + `setInterval` でポーリングを設定する。
- クリーンアップ関数 `return () => clearInterval(timer)` を忘れずに書くこと（コンポーネントアンマウント時のメモリリーク防止）。
- `apiBaseUrl` は `process.env.NEXT_PUBLIC_API_BASE_URL` 経由で取得し、`useCallback` の依存配列に含めること。

### 検知タイプ別スタイリングのパターン
- `Record<AlertType, ConfigObject>` 形式の設定オブジェクトで、色・説明・値フォーマッタを一元管理する。
- 未知の検知タイプには `?? fallback` でデフォルトスタイルを適用し、型エラーを防ぐ。
- 値の単位は検知タイプによって異なる（支持率急増・トレンド逸脱: `%` 表示、順位乖離: 整数のランク差）ため、`formatValue: (v: number) => string` をConfig内に定義してロジックを分散させない。

### フロントエンドのURL管理パターン
- `GET /api/odds/targets` で登録済みURL一覧を取得し、初回マウント時に `useEffect` + `fetchTargetUrls` で読み込む。
- `POST /api/odds/targets` でURL登録（body: `{ url: string }`）、`DELETE /api/odds/targets` でURL削除（body: `{ url: string }`）。
- 登録・削除の成功時はレスポンスの `data.urls` を `setTargetUrls` にセットして再取得なしで表示を更新する。
- 通信エラーは `try-catch` + `console.warn` で処理し、UIを壊さない。
