# タスク: Step 20 Slack Webhookによるアラート通知連携

## 目的

異常検知時にフロントエンドを開いていなくてもアラートを受け取れるよう、Slack Incoming Webhook を使ったプッシュ通知を実装する。1分おきのポーリングで同じ馬のアラートがスパム化しないよう、**初回検知時のみ通知**する設計とする。

> **⚠️ 設計変更 (2026-03-13):** 当初 LINE Notify を予定していたが、同サービスは 2025年3月31日に終了済みのため使用不可。代替として **Slack Incoming Webhook** を採用する。

---

## 決定事項

| 項目 | 決定内容 |
|------|---------|
| 通知サービス | Slack Incoming Webhook |
| 集約ポリシー | 1スクレイピングで複数アラートが発生した場合、**1通にまとめて送信** |
| 重複送信防止キー | `"URL:馬番:alertType:yyyy-MM-dd"` 形式で**日次ユニーク** |
| 通知タイミング | `detect()` が返す全アラートのうち、未送信のものだけ抽出して送信（初回検知のみ） |
| HTTPクライアント | `RestClient`（Spring 6.1+ / Spring Boot 4.x 推奨） |
| 統合ポイント | `OddsSyncService.saveAlertsToSheet()` の直後 |

---

## 設計方針

### スクレイピングとアラート通知の分離

`OddsAnomalyDetector.detect()` は毎回「現在条件を満たす全アラート」を返す（Sheets書き込み・フロントエンド表示には全件必要）。
通知の絞り込みは `SlackNotifyClient` が担い、`detect()` に送信済み管理ロジックを混入させない。

```
fetchAndSaveOdds()
  └─ detect()           ← 毎回全アラートを返す（Sheetsへは全件書く）
  └─ saveAlertsToSheet()
  └─ slackNotifyClient.notify(alerts, url)
       ├─ 送信済みキーセットでフィルタ
       ├─ 未送信のものだけ1通に集約
       └─ Slack POST（失敗してもスクレイピングは止めない）
```

---

## 実装計画

### 前提・現状把握

| 項目 | 現状 |
|------|------|
| `OddsSyncService.fetchAndSaveOdds()` | スクレイピング→パース→検知→Sheets保存の順で実行。Slack通知なし。 |
| `OddsAnomalyDetector.detect()` | `List<AnomalyAlertDto>` を返す。`latestAlerts` に累積保持。 |
| `AnomalyAlertDto` | `raceName, horseNumber, horseName, alertType, value, detectedAt` の record。 |
| 設定管理 | `application.yml` + `application-secret.yml`（gitignore済み）。 |
| HTTPクライアント | 現状未使用。`RestClient` を新規追加する。 |

---

### Step 1: 設定を追加

**`application.yml` に以下を追記:**

```yaml
slack:
  enabled: true     # false にすると通知を無効化（開発・テスト時の誤通知防止）
  webhook-url: ""   # 値なし。実際の値は application-secret.yml で上書き
```

**`application-secret.yml` に以下を追記（gitignoreされているファイル）:**

```yaml
slack:
  webhook-url: "https://hooks.slack.com/services/XXX/YYY/ZZZ"
```

**`ScrapingProperties.java` には追加しない。** Slack設定専用の `@ConfigurationProperties` クラスを新規作成する。

**`config/SlackProperties.java` を新規作成:**

```java
@ConfigurationProperties(prefix = "slack")
public record SlackProperties(String webhookUrl, boolean enabled) {}
```

`BackendApplication.java` に `@EnableConfigurationProperties(SlackProperties.class)` を追加する。

---

### Step 2: `SlackNotifyClient.java` を新規作成

場所: `service/SlackNotifyClient.java`

**責務:** 送信済みキーセットを保持し、未通知のアラートだけを1通にまとめて Slack に POST する。

**フィールド:**

```java
private final SlackProperties properties;
private final RestClient restClient;

/** 送信済みキーセット: 同日・同URL・同馬番・同検知タイプは1回のみ通知 */
private final Set<String> sentKeys = ConcurrentHashMap.newKeySet();

/** 日次リセット用（日付変更でキャッシュをクリア） */
private volatile LocalDate lastResetDate = LocalDate.MIN;

/** テスト用コンストラクタでの差し替えに対応 */
private final Clock clock;
```

**コンストラクタ:**

```java
// Spring が使用するデフォルトコンストラクタ
public SlackNotifyClient(SlackProperties properties) {
    this(properties, RestClient.builder().build(), Clock.systemDefaultZone());
}

// テスト用（Clock・RestClient を差し替え可能）
SlackNotifyClient(SlackProperties properties, RestClient restClient, Clock clock) { ... }
```

**主要メソッド:**

```java
/**
 * アラートリストを受け取り、未通知のものだけを1通にまとめてSlackへ送信します。
 * enabled=false・アラート0件・全件送信済みの場合は何もしません。
 * 通信失敗は try-catch で捕捉し WARN ログのみ出力します（スクレイピングを止めない）。
 */
public void notify(List<AnomalyAlertDto> alerts, String targetUrl)
```

**処理フロー (`notify`):**

1. `enabled` が false → 即リターン
2. `webhookUrl` が blank → `WARN` ログ → リターン（NPE防止）
3. `resetIfNewDay()` で日付変更時に `sentKeys` をクリア
4. `alerts` を `buildKey(alert, targetUrl)` でフィルタ → `sentKeys` に含まれないものだけ `toSend` に収集
5. `toSend` が空 → リターン
6. `buildMessage(toSend, targetUrl)` でメッセージ文字列を組み立て
7. `RestClient` で Webhook URL に `{"text": "..."}` を POST
8. **送信成功時のみ** `toSend` の各キーを `sentKeys` に追加（失敗時は次回再試行対象）
9. 通信例外は `try-catch` → SLF4J `WARN` ログのみ

**送信済みキーの設計:**

```java
private String buildKey(AnomalyAlertDto alert, String targetUrl) {
    String date = alert.detectedAt().substring(0, 10); // "yyyy-MM-dd"
    return targetUrl + ":" + alert.horseNumber() + ":" + alert.alertType() + ":" + date;
}
```

**メッセージ形式 (`buildMessage`):**

```
【オッズアラート】2件検知
URL: https://example.com/race/1

1. [支持率急増] 第1回テストレース
   馬番: 5 / 馬名: テスト馬A
   数値: 0.0234

2. [順位乖離] 第1回テストレース
   馬番: 8 / 馬名: テスト馬B
   数値: 4.0
```

**`RestClient` 使用例:**

```java
restClient.post()
    .uri(properties.webhookUrl())
    .contentType(MediaType.APPLICATION_JSON)
    .body(Map.of("text", message))
    .retrieve()
    .toBodilessEntity();
```

---

### Step 3: `OddsSyncService` に統合

**コンストラクタに `SlackNotifyClient` を DI 追加:**

```java
public OddsSyncService(OddsScrapingService scrapingService, RaceOddsParser parser,
                       GoogleSheetsService sheetsService, OddsAnomalyDetector anomalyDetector,
                       SlackNotifyClient slackNotifyClient) { ... }
```

**`fetchAndSaveOdds()` に以下を追加（`saveAlertsToSheet` 呼び出しの直後）:**

```java
// 4.1. 検知されたアラートをスプレッドシートの "Alerts" シートへ永続化
saveAlertsToSheet(targetUrl, alerts);

// 4.2. 未通知のアラートをSlackへ送信（送信済みキャッシュで初回検知のみ）
slackNotifyClient.notify(alerts, targetUrl);
```

---

### Step 4: テスト追加

**`SlackNotifyClientTest.java` を新規作成:**

| テストケース | 検証内容 |
|-------------|---------|
| `notify_enabledがfalseの場合は送信しないこと` | POST が呼ばれないこと |
| `notify_アラートが0件の場合は送信しないこと` | 空リスト渡しで POST なし |
| `notify_未通知アラートがSlackに送信されること` | POST が1回呼ばれ、`sentKeys` にキーが追加されること |
| `notify_送信済みアラートは重複送信されないこと` | 同一キーを2回渡しても2回目は POST なし |
| `notify_複数アラートが1通にまとめられること` | アラート3件でも POST が1回だけ呼ばれること |
| `notify_日付変更時に送信済みキャッシュがリセットされること` | Clock で翌日に進めると同一キーが再通知されること |
| `notify_通信失敗時でもメソッドが例外を外に投げないこと` | `RestClient` が例外をスローしてもメソッドは正常終了すること |
| `notify_通信失敗時は送信済みキャッシュに追加しないこと` | 失敗キーは `sentKeys` に残らず、次回再試行対象になること |
| `notify_webhookUrlが未設定の場合は送信しないこと` | blank URL で POST なし・WARNログ出力 |

**テストのアプローチ:**
- `RestClient` はモック化（`Mockito.mock(RestClient.class)` でメソッドチェーンをスタブ）
- `Clock` はテスト用に固定値 (`Clock.fixed(...)`) を注入して日次リセットをテスト
- パターン: `OddsAnomalyDetector` の Clock インジェクション方式と同様

---

## エラーハンドリング方針

| 処理 | 失敗時の挙動 |
|------|-------------|
| Slack POST 失敗（ネットワーク・4xx・5xx） | `WARN` ログ出力のみ。`sentKeys` には追加しない（次回スクレイピング時に再試行対象）。 |
| `webhookUrl` が blank | `notify()` 冒頭でチェック → `WARN` ログ → リターン |
| `enabled=false` | 即リターン（ログなし） |

---

## 変更対象ファイル一覧

| ファイル | 変更種別 |
|---------|---------|
| `config/SlackProperties.java` | 新規作成（`@ConfigurationProperties`） |
| `service/SlackNotifyClient.java` | 新規作成 |
| `service/OddsSyncService.java` | `SlackNotifyClient` DI追加、`fetchAndSaveOdds` に `notify()` 呼び出し追加 |
| `BackendApplication.java` | `@EnableConfigurationProperties(SlackProperties.class)` 追加 |
| `src/main/resources/application.yml` | `slack.enabled` / `slack.webhook-url` 追加 |
| `src/main/resources/application-secret.yml` | `slack.webhook-url` に実際のURL記入（gitignore済み） |
| `test/.../service/SlackNotifyClientTest.java` | 新規作成 |
