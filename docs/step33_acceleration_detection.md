# Step 33: 支持率の加速度（時間正規化検知）

## 概要

スクレイピング間隔が1分〜30分と変動する環境で、「単位時間あたりの支持率変化量」を算出することで、インターバルに依存しない一貫した異常検知を実現する。ロジックA（前回比）と並行して新たにロジックD として追加し、短時間の集中的な投票行動を正確に捉える。

---

## このステップで実施するリファクタリング

Step 33 の実装に合わせて以下のリファクタリングを同時に行う。後続ステップ（Step 34〜36）の土台となるため、このステップで完結させること。

### 1. `previousWinOdds` → `previousSnapshots` への置き換え

**変更前:**
```java
private final ConcurrentHashMap<String, Double> previousWinOdds = new ConcurrentHashMap<>();
```

**変更後:**
```java
record OddsSnapshot(double winOdds, Instant processedAt) {}
private final ConcurrentHashMap<String, OddsSnapshot> previousSnapshots = new ConcurrentHashMap<>();
```

- `OddsSnapshot` は `OddsAnomalyDetector` の inner record として定義
- ロジックA の `detectSupportRateIncrease()` 内の `previousWinOdds.get(key)` を `previousSnapshots.get(key).winOdds()` に修正
- `detect()` 末尾の更新処理を `previousSnapshots.put(key, new OddsSnapshot(d.winOdds(), now))` に変更
- `now` は `detect()` の先頭で `Instant now = Instant.now(clock)` として一度取得し、各サブメソッドに渡す

### 2. `clearStateForUrl(String url)` の追加

URL監視対象削除時に呼び出す一元クリーンアップメソッドを追加する。後続ステップで追加される Map もここに集約していく。

```java
public void clearStateForUrl(String url) {
    String prefix = url + ":";
    previousSnapshots.keySet().removeIf(k -> k.startsWith(prefix));
    baselineWinOdds.keySet().removeIf(k -> k.startsWith(prefix));
}
```

- `OddsSyncService` に `clearStateForUrl(String url)` 委譲メソッドを追加
- `OddsTargetsController.removeTarget()` で `oddsSyncService.clearCachedStartTime(url)` の直後に `oddsSyncService.clearStateForUrl(url)` を呼び出す

### 3. `resetBaselineIfNewDay()` への `previousSnapshots` クリア追加

日付変更時に昨日のスナップショットを破棄する（大幅な時間差による誤検知防止）:
```java
baselineWinOdds.clear();
previousSnapshots.clear();  // 追加
```

---

## なぜ有用か（予想への活用観点）

### この検知が捉える市場現象

発走10〜30分前に0.5%/分以上の加速度が検出された場合、それは「複数回のスクレイピングにわたって継続的に投票が集中している」状態を意味する。スクレイピング間隔が長い（例：10分）ときに前回比2%のアラートが出るのとは異なり、加速度では「1分あたりに換算しても速い」馬のみを抽出できる。

### 予想ファクターとしての使い方

- **買い根拠**: 加速度が高い馬は、馬場発表・パドック情報・陣営コメントなど、レース直前に出回る「確度の高い情報」を持つ関係者の資金流入を反映している可能性がある。特に上位10番人気以内の馬で加速度アラートが出た場合は積極的に注目。
- **消し根拠**: 加速度が突然0〜マイナスに転じた（減速）場合は、一時的な資金流入が終わった可能性があり、単独ファクターとしての信頼性が低下する。
- **券種の選択**: 単勝加速が高い馬は「1着候補」として単勝・馬単の1着固定に活用。

### 他の検知パターンとの組み合わせ例

| 組み合わせ | 意味 |
|---|---|
| Step 33 + Step 34（フェーズ別基準点） | 「直前フェーズで加速している」→ 最も信頼度が高い直前情報系シグナル |
| Step 33 + Step 35（オッズ断層） | 「断層を超えて内側に入り込んできた馬の加速」→ 本命圏への格上げ |
| Step 33 + ロジックB（単複乖離） | 「単複同時加速」→ 幅広い支持、馬券圏内ほぼ確実の可能性 |

---

## 目的

- スクレイピング間隔の不均一さによる誤検知・検知漏れをなくす
- 短時間に集中する投票行動（インサイダー的な動き）をノイズと区別して捕捉する

---

## データ構造の変更

### 追加する内部クラス

`OddsAnomalyDetector.java` 内に以下の inner record を定義する:

```java
record OddsSnapshot(double winOdds, Instant processedAt) {}
```

### フィールドの変更

**変更前:**
```java
private final ConcurrentHashMap<String, Double> previousWinOdds = new ConcurrentHashMap<>();
```

**変更後:**
```java
private final ConcurrentHashMap<String, OddsSnapshot> previousSnapshots = new ConcurrentHashMap<>();
```

> 注意: `previousWinOdds` を参照していた既存のロジックA（`detectSupportRateIncrease`）も合わせて修正が必要。変更後は `previousSnapshots.get(key).winOdds()` で前回オッズを取得する。

---

## 計算式・アルゴリズム

```
支持率 = 1 / 単勝オッズ

Δ支持率 = (1 / 現在オッズ) - (1 / 前回オッズ)

Δ時刻[分] = ChronoUnit.SECONDS.between(前回Instant, 現在Instant) / 60.0

加速度 = Δ支持率 / Δ時刻[分]
```

精度確保のため BigDecimal を使用する（既存ロジックAと同様）。

```java
BigDecimal currentRate = BigDecimal.ONE.divide(BigDecimal.valueOf(currentOdds), 10, RoundingMode.HALF_UP);
BigDecimal prevRate    = BigDecimal.ONE.divide(BigDecimal.valueOf(prevOdds), 10, RoundingMode.HALF_UP);
BigDecimal deltaRate   = currentRate.subtract(prevRate);
BigDecimal deltaMin    = BigDecimal.valueOf(deltaSeconds / 60.0);
BigDecimal acceleration = deltaRate.divide(deltaMin, 6, RoundingMode.HALF_UP);
```

---

## 判定基準・閾値

```java
static final BigDecimal ACCELERATION_THRESHOLD = new BigDecimal("0.005");  // 0.5% / 分
```

`acceleration >= ACCELERATION_THRESHOLD` の場合にアラートを発生させる。

---

## 除外条件

1. 前回スナップショットが存在しない場合（初回実行）はスキップ
2. 単勝上位3番人気（`top3Keys` に含まれるキー）は除外
3. `deltaSeconds <= 0` の場合（同一秒・時刻逆転）はスキップ（ゼロ除算防止）
4. 前回または現在のオッズが `null` / `<= 0` の場合はスキップ

---

## アラート形式

| フィールド | 値 |
|---|---|
| `alertType` | `"支持率加速"` |
| `value` | 加速度値（小数点3桁に丸める） |

**例**: `alertType="支持率加速"`, `value=0.008`（= 0.8%/分の加速）

---

## 既存コードとの結合点

| 変更対象 | 変更内容 |
|---|---|
| `OddsAnomalyDetector.java` | `previousWinOdds` → `previousSnapshots` へのリネームと型変更 |
| `detectSupportRateIncrease()` | スナップショットから `.winOdds()` を取り出す形に修正 |
| `detect()` メソッド末尾 | `previousWinOdds.put(key, odds)` → `previousSnapshots.put(key, new OddsSnapshot(odds, Instant.now(clock)))` に変更 |
| 新メソッド追加 | `detectAcceleration(List<OddsData> oddsList)` を追加し、`detect()` から呼び出す |

---

## テスト方針

`OddsAnomalyDetectorTest` に以下のケースを追加する:

| テストケース | 期待結果 |
|---|---|
| 1分後に支持率が0.8%上昇 | アラート発生（0.8%/分 > 閾値） |
| 10分後に支持率が2%上昇 | アラートなし（0.2%/分 < 閾値） |
| 同一秒の2回実行 | アラートなし（ゼロ除算除外） |
| 上位3番人気の加速 | アラートなし（除外対象） |
| 初回実行（前回データなし） | アラートなし |

---

## 実装時の注意事項

- `Instant.now(clock)` を使って現在時刻を取得すること（テスト用 `Clock` の差し替えに対応）
- `previousSnapshots` の更新タイミングは `detect()` の末尾で行う（ロジックA・新ロジックD両方で参照後に更新）
- `OddsSnapshot` の `processedAt` は `Instant` 型とし、タイムゾーン非依存にする
- 既存の `detectSupportRateIncrease()` への影響範囲はフィールド参照の変更のみで、アルゴリズム自体は変わらない
