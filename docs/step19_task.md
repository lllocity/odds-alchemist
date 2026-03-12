# タスク: Step 19 監視対象URLの永続化

## 目的
現在、監視対象URLは `TargetUrlStore`（`CopyOnWriteArrayList`）でインメモリ管理しているため、アプリ再起動のたびに消失する。Google Sheets の `Targets` シートへ永続化し、再起動後も即座に監視を自動再開できるようにする。

> **スコープ確定 (2026-03-12):** アラート履歴の起動時ロード（要件3）は対象外。アラートの Sheets 書き込みはすでに実装済みのため、URL 永続化のみ実装する。ストレージは Google Sheets に統一する（OddsData / Alerts と同一スプレッドシート）。

---

## 実装計画

### 前提・現状把握

| 項目 | 現状 |
|------|------|
| `TargetUrlStore` | `CopyOnWriteArrayList` のインメモリのみ。Sheets 連携なし。 |
| `GoogleSheetsService` | `appendData(range, values)` のみ実装。読み込み・上書きメソッドなし。 |
| `OddsScrapingScheduler` | `@PostConstruct` でスレッドプールを初期化。URL 復元処理なし。 |
| アラート書き込み | `OddsSyncService.saveAlertsToSheet()` で実装済み。 |

---

### Step 1: Google Sheets に `Targets` シートを追加

スプレッドシートに手動で `Targets` という名前のシートを追加する。

| 列 | 内容 | 例 |
|----|------|----|
| A | URL | `https://example.com/race/1` |
| B | 最終実行時間 | `2026-03-12 15:30:00` |
| C | 次回実行予定時間 | `2026-03-12 15:35:00` |

- ヘッダーなし（データのみ）
- B・C列は初回登録時は空。最初のスクレイピング完了後に書き込まれる。

---

### Step 2: `GoogleSheetsService` にメソッドを追加

```
readData(String range) → List<List<Object>>
```
- `spreadsheets().values().get(spreadsheetId, range).execute()` で取得
- 値が null の場合は空リストを返す

```
clearAndWriteData(String range, List<List<Object>> values)
```
- `spreadsheets().values().clear(...)` でレンジをクリア後
- `spreadsheets().values().update(...).setValueInputOption("USER_ENTERED").execute()` で書き込む
- values が空の場合はクリアのみ行う（書き込みスキップ）

---

### Step 3: `TargetUrlStore` を URL + 実行時刻管理に拡張

**新規レコード `TargetUrlInfo`（内部 record）:**
```java
record TargetUrlInfo(String url, String lastExecutionTime, String nextScheduledTime)
```
- `lastExecutionTime` / `nextScheduledTime` は `null` 許容（初回登録直後は未確定）
- フォーマット: `"yyyy/MM/dd HH:mm:ss"`

**内部データ構造変更:**
- `CopyOnWriteArrayList<String>` → `ConcurrentHashMap<String, TargetUrlInfo>`（URL をキーにした Map）
- `getUrls()` は `Map` のキーセットから生成して返す（既存 API 互換を維持）

**コンストラクタ変更:** `GoogleSheetsService` を DI で受け取る。

**`@PostConstruct loadFromSheet()`（新規追加）:**
- `googleSheetsService.readData("Targets!A:C")` で A〜C列を読み込む
- 各行: A=URL, B=最終実行時間（空可）, C=次回実行予定時間（空可）
- `TargetUrlInfo` として Map に追加（重複チェックあり）
- 失敗時は `try-catch` で捕捉し `WARN` ログ。インメモリは空のまま起動継続。

**`addUrl(String url)` 変更:**
- `TargetUrlInfo(url, null, null)` として Map に追加後、`persistToSheet()` を呼び出す

**`removeUrl(String url)` 変更:**
- Map から削除後、`persistToSheet()` を呼び出す

**`updateExecutionTimes(String url, String lastExecution, String nextScheduled)`（新規追加）:**
- Map 内の `TargetUrlInfo` を新しい時刻で差し替える（URL が存在する場合のみ）
- `persistToSheet()` は **呼ばない**（Sheets への書き込みは `OddsScrapingScheduler` が責務を持つ）

**`persistToSheet()`（private 新規追加）:**
- 現在の Map 全件を `[["url", "lastExec", "nextSched"], ...]` 形式に変換（null は空文字）
- `googleSheetsService.clearAndWriteData("Targets!A:C", rows)` を呼び出す
- 失敗時は `try-catch` で捕捉し `ERROR` ログ。スクレイピングは止めない。

> **注意:** `loadFromSheet()` 内では `persistToSheet()` を呼ばない（既存データを再書き込みしない）。

---

### Step 4: `OddsScrapingScheduler` に起動時 URL 復元処理と実行時刻更新を追加

**`@EventListener(ApplicationReadyEvent.class) restoreFromStore()`（新規追加）:**
- `targetUrlStore.getUrls()` を読み込む（Step 3 の `@PostConstruct` で Sheets から復元済み）
- URLが 0 件の場合はログを出力して終了
- 各 URL に対して `OddsTargetsController.addTarget()` と同じ非同期処理を実行:
  1. `CompletableFuture.runAsync(() -> { oddsSyncService.fetchAndSaveOdds(url, ...) → scheduleUrl(url) })`
  2. 失敗時は `WARN` ログのみ（スケジューラーを止めない）

**`scrapeAndReschedule(String url)` 変更:**
- スクレイピング完了後・次回 `scheduleUrl(url)` 呼び出し後に以下を実行:
  1. `targetUrlStore.updateExecutionTimes(url, 現在時刻, 次回予定時刻)`
  2. `targetUrlStore.persistToSheet()` を呼び出す（`public` に変更して委譲）
- これにより Sheets 上の B・C列がスクレイピングのたびに更新される

> **`@PostConstruct` vs `@EventListener(ApplicationReadyEvent.class)` の使い分け:**
> `@PostConstruct` は Bean 初期化時に実行されるため、他の Bean（`GoogleSheetsService` 等）の準備が完了していない場合がある。`ApplicationReadyEvent` は Spring Context が完全に起動した後に発火するため、URL 復元処理に適している。`taskScheduler.initialize()` は従来通り `@PostConstruct` で実行する。

---

### Step 5: テスト追加

**`TargetUrlStoreTest`（新規ファイル）:**

| テストケース | 検証内容 |
|-------------|---------|
| `loadFromSheet_起動時にSheetsからURL・実行時刻が復元されること` | `readData` が返す A〜C列が `getUrls()` と `TargetUrlInfo` に反映される |
| `loadFromSheet_Sheetsが空の場合はURLリストが空であること` | 空リスト返却時にエラーなし |
| `loadFromSheet_Sheets読み込み失敗時でも起動が継続すること` | `readData` が IOException をスローしても例外が外に出ない |
| `addUrl_登録時にSheetsへ書き込まれること` | `clearAndWriteData` が呼ばれること |
| `addUrl_Sheets書き込み失敗時でもインメモリへの追加は確定すること` | `clearAndWriteData` が失敗しても `getUrls()` に URL が含まれる |
| `removeUrl_削除時にSheetsへ書き込まれること` | `clearAndWriteData` が呼ばれること |
| `updateExecutionTimes_時刻が更新されること` | `getUrls()` の URL に対応する lastExecution / nextScheduled が更新される |

**`OddsScrapingSchedulerTest` への追加:**

| テストケース | 検証内容 |
|-------------|---------|
| `restoreFromStore_保存済みURLのスクレイピングが非同期で開始されること` | `fetchAndSaveOdds` が URL ごとに呼ばれる |
| `restoreFromStore_URLが0件の場合はスクレイピングされないこと` | `fetchAndSaveOdds` が呼ばれない |
| `scrapeAndReschedule_完了後にupdateExecutionTimesが呼ばれること` | `targetUrlStore.updateExecutionTimes` が呼ばれること |

---

## エラーハンドリング方針

| 処理 | 失敗時の挙動 |
|------|------------|
| `loadFromSheet` (起動時読み込み) | `WARN` ログ出力、インメモリ空のまま起動継続 |
| `persistToSheet` (書き込み) | `ERROR` ログ出力、インメモリへの変更は確定済み |
| `restoreFromStore` の URL ごとのスクレイピング | `WARN` ログ出力、他 URL の処理を継続 |

---

## 変更対象ファイル一覧

| ファイル | 変更種別 |
|---------|---------|
| `service/GoogleSheetsService.java` | メソッド追加 (`readData`, `clearAndWriteData`) |
| `service/TargetUrlStore.java` | 内部構造変更（`TargetUrlInfo` 追加）、DI 追加、`@PostConstruct` 追加、`addUrl`/`removeUrl` 変更、`updateExecutionTimes`/`persistToSheet` 追加 |
| `scheduler/OddsScrapingScheduler.java` | `@EventListener` メソッド追加、`scrapeAndReschedule` 変更（実行時刻更新・Sheets 書き込み） |
| `service/TargetUrlStoreTest.java` | 新規作成 |
| `scheduler/OddsScrapingSchedulerTest.java` | テストケース追加 |
