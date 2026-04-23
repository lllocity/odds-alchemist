# Step 34: 多段階基準点（フェーズ別トレンド検知）

## 概要

現在のロジックC（トレンド逸脱）が持つ「その日の初回」という単一基準点を、発走までの残り時間に応じた3段階（Morning / Pre-30 / Pre-10）に拡張する。これにより「朝から売れている馬」と「直前に急騰した馬」を明確に区別し、アラートに文脈（フェーズラベル）を付与する。

---

## このステップで実施するリファクタリング

### 1. `detect()` シグネチャへの `startTime` 追加

フェーズ判定のために発走時刻を `detect()` に渡す形に変更する。`OddsSyncService` から `OddsAnomalyDetector` への循環依存を回避するためシグネチャ引数として渡す。

**変更前:**
```java
public List<AnomalyAlertDto> detect(List<OddsData> oddsList)
```

**変更後:**
```java
public List<AnomalyAlertDto> detect(List<OddsData> oddsList, Optional<LocalTime> startTime)
```

- `OddsSyncService.fetchAndSaveOdds()` 内の呼び出し箇所を `anomalyDetector.detect(oddsListWithUrl, startTime)` に変更（`startTime` はすでにその時点で取得済み）
- 既存の `OddsAnomalyDetectorTest` は `detect(list)` を直接呼んでいるため、テスト用オーバーロード `detect(List<OddsData> oddsList)` を追加するか、呼び出し側に `Optional.empty()` を渡す形で対応する

### 2. `Phase` enum と `phaseBaselines` Map の追加

```java
enum Phase { MORNING, PRE_30, PRE_10 }
private final ConcurrentHashMap<String, Map<Phase, Double>> phaseBaselines = new ConcurrentHashMap<>();
```

### 3. `clearStateForUrl()` への `phaseBaselines` クリアの追加（Step 33 で作成済みのメソッドを拡張）

```java
public void clearStateForUrl(String url) {
    String prefix = url + ":";
    previousSnapshots.keySet().removeIf(k -> k.startsWith(prefix));
    baselineWinOdds.keySet().removeIf(k -> k.startsWith(prefix));
    phaseBaselines.keySet().removeIf(k -> k.startsWith(prefix));  // 追加
}
```

### 4. `resetBaselineIfNewDay()` への `phaseBaselines` クリアの追加

```java
baselineWinOdds.clear();
previousSnapshots.clear();
phaseBaselines.clear();  // 追加
```

---

## なぜ有用か（予想への活用観点）

### この検知が捉える市場現象

競馬の投票行動には時間的なパターンがある。朝〜昼は出馬表・調教タイムを見た一般ファンの投票が中心で、発走30分前〜10分前には馬場状態の確定・パドック情報・陣営コメントが反映される。単一の基準点では「いつからの変化か」がわからないが、3フェーズ基準点を持てば変化の「文脈」が読める。

### 予想ファクターとしての使い方

| アラートパターン | 市場の解釈 | 予想への活用 |
|---|---|---|
| 朝比較のみアラート | 開門から継続的に売れている | 実績・調教評価に基づく「正当な人気」。本命圏入りを検討 |
| 30分前比較でアラート（朝比較はなし） | 馬場確定・装備変更など直前情報での急騰 | 馬場適性・装備変更馬を重点チェック。高信頼度の直前シグナル |
| 10分前比較でアラート（30分前比較はなし） | パドック・返し馬での好印象による最終流入 | パドック評価が高い馬。即時対応が必要 |
| 3フェーズすべてアラート | 全時間帯にわたって継続的に買われ続けている | 最強の買いシグナル。単勝・三連単の軸に最適 |

### 他の検知パターンとの組み合わせ例

| 組み合わせ | 意味 |
|---|---|
| Step 34 + Step 33（加速度） | 「どのフェーズで」「どの速さで」売れているかの2次元評価 |
| Step 34 + Step 36（乖離方向） | 「直前フェーズで単複乖離が拡大」→ 複勝圏入りの強いシグナル |
| Step 34 + ロジックC（トレンド逸脱） | 実質的にロジックCの上位互換。フェーズ情報がない場合はロジックCにフォールバック |

---

## 目的

- 「朝型人気」と「直前急騰」を区別し、アラートの解釈可能性を高める
- 各フェーズでの乖離を個別に検知・通知することで、投票行動の「文脈」を提供する

---

## データ構造の変更

### 追加するフィールド

```java
// フェーズ定義
enum Phase { MORNING, PRE_30, PRE_10 }

// フェーズ別基準点: キー="URL:馬番", 値=Map<Phase, Double>
private final ConcurrentHashMap<String, Map<Phase, Double>> phaseBaselines = new ConcurrentHashMap<>();
```

既存の `baselineWinOdds`（`ConcurrentHashMap<String, Double>`）は MORNING フェーズと同等。段階的に移行するため、ロジックCの `baselineWinOdds` をそのまま MORNING として流用することを推奨する（リファクタリングコストを最小化）。

---

## 計算式・アルゴリズム

### フェーズ判定

```java
private Phase getCurrentPhase(String url) {
    Optional<LocalTime> startTime = oddsSyncService.getCachedStartTime(url);
    if (startTime.isEmpty()) return Phase.MORNING;  // フォールバック

    long minutesUntilStart = ChronoUnit.MINUTES.between(LocalTime.now(clock), startTime.get());

    if (minutesUntilStart <= 10) return Phase.PRE_10;
    if (minutesUntilStart <= 30) return Phase.PRE_30;
    return Phase.MORNING;
}
```

### 基準点の設定

```java
Map<Phase, Double> baselines = phaseBaselines.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
baselines.putIfAbsent(phase, currentOdds);  // 各フェーズで最初の1回のみ設定
```

### 逸脱量の計算（既存ロジックCと同一式）

```
逸脱量 = (1 / 現在オッズ) - (1 / フェーズ基準オッズ)
```

---

## 判定基準・閾値

既存ロジックCの閾値を流用する:

```java
static final BigDecimal TREND_DEVIATION_THRESHOLD = new BigDecimal("0.05");  // +5.0%
```

各フェーズ個別に判定し、それぞれアラートを生成する。

---

## 除外条件

1. 発走時刻が取得できない場合（`getCachedStartTime()` が `Optional.empty()`）は MORNING フェーズのみ処理
2. 単勝上位3番人気は除外（既存ロジックCと同様）
3. 現在または基準オッズが `null` / `<= 0` はスキップ
4. `minutesUntilStart < 0`（発走後）の場合は全フェーズのアラートを停止

---

## アラート形式

| フィールド | 値 |
|---|---|
| `alertType` | `"フェーズ逸脱[朝]"` / `"フェーズ逸脱[30分前]"` / `"フェーズ逸脱[10分前]"` |
| `value` | 逸脱量（BigDecimal → double、小数点4桁） |

**例**: `alertType="フェーズ逸脱[30分前]"`, `value=0.063`

---

## 既存コードとの結合点

| 変更対象 | 変更内容 |
|---|---|
| `OddsAnomalyDetector.java` | `phaseBaselines` フィールド追加、`Phase` enum 追加 |
| `OddsAnomalyDetector` のコンストラクタ | `OddsSyncService` を DI として受け取る（発走時刻参照のため） |
| `detectTrendDeviation()` | フェーズ判定を追加し、ループ内でフェーズ別基準点を設定・比較 |
| `resetBaselineIfNewDay()` | `phaseBaselines` のクリアも追加 |
| 新メソッド | `clearBaselineForUrl(String url)` — URL削除時に `OddsTargetsController` から呼び出す |
| `detect()` シグネチャ | `detect(List<OddsData> oddsList, String url)` に変更してフェーズ判定のためにURLを渡す |

> `OddsAnomalyDetector` に `OddsSyncService` を DI する場合、循環依存に注意。`OddsSyncService` が `OddsAnomalyDetector` を持つため、`OddsSyncService` → `OddsAnomalyDetector` → `OddsSyncService` の循環が発生する。解決策: 発走時刻を `detect()` メソッドの引数として渡す（`Optional<LocalTime> startTime`）。

---

## テスト方針

`OddsAnomalyDetectorTest` に以下のケースを追加する:

| テストケース | 期待結果 |
|---|---|
| 発走60分前、朝比較で+5%以上 | `"フェーズ逸脱[朝]"` アラート |
| 発走25分前、30分前比較で+5%以上 | `"フェーズ逸脱[30分前]"` アラート |
| 発走8分前、10分前比較で+5%以上 | `"フェーズ逸脱[10分前]"` アラート |
| 発走時刻未取得（Optional.empty） | MORNING フェーズのみ動作 |
| 同フェーズで2回実行 | 基準点は初回のみ設定（putIfAbsent） |
| 日付変更後 | 全基準点がリセットされ再設定 |

---

## 実装時の注意事項

- `detect()` メソッドに `Optional<LocalTime> startTime` を追加し、`OddsSyncService` との循環依存を回避すること
- `OddsSyncService.fetchAndSaveOdds()` 内での `detect()` 呼び出し箇所を `anomalyDetector.detect(oddsListWithUrl, startTime)` に変更する
- `clearBaselineForUrl(url)` は URL 削除時（`OddsTargetsController.deleteUrl()`）に呼び出す。`clearCachedStartTime()` と並べて呼び出すと整合性が保てる
- `Phase.PRE_10` は `Phase.PRE_30` の条件と重複するため、判定順序は `PRE_10 → PRE_30 → MORNING` の順で評価する
