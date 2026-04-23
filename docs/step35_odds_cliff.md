# Step 35: オッズ断層（クリフ）の動的検知

## 概要

単勝オッズの人気順リストにおける「隣接する馬同士のオッズ比」を計算し、急激な乖離（=断層）が存在する位置を特定する。さらに断層位置の時系列変化（凝縮方向・拡散方向）を監視し、市場全体の「混戦度」の変化を検知する。

---

## このステップで実施するリファクタリング

### 1. `sortedByWin` を `detect()` から各サブメソッドに渡す形に統一

`detectOddsCliff()` も `sortedByWin` を必要とするが、現状は `detectRankDivergence()` 内でローカルに `validPlaceList` だけ生成している。`sortedByWin` は `detect()` 内ですでに生成されているため、このリストをサブメソッドの引数として明示的に渡すよう整理する。

**変更後のサブメソッドシグネチャ（代表例）:**
```java
private void detectOddsCliff(List<OddsData> sortedByWin, String url, List<AnomalyAlertDto> alerts)
```

`url` は `sortedByWin.get(0).url()` でも取れるが、明示的に渡すほうが読みやすい。`detect()` 内で `sortedByWin` が空でない場合に `sortedByWin.get(0).url()` で取得してローカル変数に保持し、各メソッドに渡す。

### 2. `previousCliffPosition` の追加

```java
// 前回の断層位置（n番人気とn+1番人気の間）: キー=URL
private final ConcurrentHashMap<String, Integer> previousCliffPosition = new ConcurrentHashMap<>();
```

### 3. `clearStateForUrl()` への `previousCliffPosition` クリアの追加

```java
public void clearStateForUrl(String url) {
    String prefix = url + ":";
    previousSnapshots.keySet().removeIf(k -> k.startsWith(prefix));
    baselineWinOdds.keySet().removeIf(k -> k.startsWith(prefix));
    phaseBaselines.keySet().removeIf(k -> k.startsWith(prefix));
    previousCliffPosition.remove(url);  // 追加（キーが URL 単体）
}
```

### 4. `resetBaselineIfNewDay()` への `previousCliffPosition` クリアの追加

```java
previousCliffPosition.clear();  // 追加
```

---

## なぜ有用か（予想への活用観点）

### この検知が捉える市場現象

単勝オッズの分布には、「ここまでは勝負になる・ここから先は格落ち」という市場の評価ラインが存在する。例えば 1番人気1.8倍、2番人気3.5倍、3番人気4.2倍、4番人気12倍 という分布では、3番人気と4番人気の間に明確な断層がある（比率 = 12/4.2 ≒ 2.86）。これは市場が「3頭の争い」と認識していることを示す。

### 予想ファクターとしての使い方（静的）

| 断層位置 | 市場評価 | 予想戦略 |
|---|---|---|
| 1-2番人気間（比率≥1.5） | 1強の圧倒的支持 | 単勝・馬単の1着固定で高回収 |
| 2-3番人気間 | 2強対決 | 馬連・三連単の軸2頭を絞る |
| 3-4番人気間 | 3頭の争い（最頻パターン） | 三連複・三連単の軸3頭 |
| 6番人気以降 | 混戦（断層が深い位置） | 荒れ馬場・長距離戦で穴狙い |

### 予想ファクターとしての使い方（動的）

| 断層の移動方向 | 意味 | 戦略 |
|---|---|---|
| **凝縮**（位置が上位へ移動） | 特定馬への絞り込みが進む。市場の確信が強まっている | 断層内の上位馬を本命視。三連単の的中率が上がる |
| **拡散**（位置が下位へ移動） | 下位人気馬にも資金が流入し混戦化 | 本命馬のオッズ妙味低下。穴狙い戦略に切り替え検討 |

### 他の検知パターンとの組み合わせ例

| 組み合わせ | 意味 |
|---|---|
| Step 35 + Step 33（加速度） | 「断層内に新たに入り込んできた馬」を加速度で捉える→ 本命圏格上げシグナル |
| Step 35 + Step 34（フェーズ別） | 「直前フェーズで断層が凝縮した」→ 最終的な本命馬の確定に近い |
| Step 35 + ロジックB（単複乖離） | 「断層内の馬で単複乖離がある」→ 馬券的な買い方の精密化 |

---

## 目的

- レース全体の「予測しやすさ（混戦度）」を定量化する
- 断層の移動（凝縮/拡散）を検知し、市場の評価変化をアラートとして通知する

---

## データ構造の変更

### 追加するフィールド

```java
// 前回の断層位置（n番人気とn+1番人気の間）: キー=URL
private final ConcurrentHashMap<String, Integer> previousCliffPosition = new ConcurrentHashMap<>();
```

値の意味: `3` → 「3番人気と4番人気の間に断層」を表す

---

## 計算式・アルゴリズム

### 断層位置の検出

```java
// sortedByWin: 単勝オッズ昇順にソートされたリスト（既存の sortedByWin と同一）
for (int i = 1; i < sortedByWin.size(); i++) {
    double ratio = sortedByWin.get(i).winOdds() / sortedByWin.get(i - 1).winOdds();
    if (ratio >= CLIFF_RATIO_THRESHOLD) {
        // i番目の馬と(i-1)番目の馬の間に断層あり → 断層位置 = i（i番人気の直前）
        cliffPosition = i;
        break;  // 最上位の断層のみ検出
    }
}
```

### 断層移動の判定

```java
Integer prevPosition = previousCliffPosition.get(url);
if (prevPosition != null && !prevPosition.equals(cliffPosition)) {
    String direction = cliffPosition < prevPosition ? "凝縮" : "拡散";
    // アラート生成
}
```

---

## 判定基準・閾値

```java
static final double CLIFF_RATIO_THRESHOLD = 1.5;  // 隣接オッズ比 1.5倍以上で断層とみなす
```

断層が複数存在する場合は「最上位（人気順で最も上位にある）断層」のみを対象とする。

---

## 除外条件

1. 有効な単勝オッズを持つ馬が3頭未満の場合は計算をスキップ
2. 断層位置が前回と同一の場合はアラートを生成しない（重複通知防止）
3. 断層が検出されない場合（全隣接比率が閾値未満）は `previousCliffPosition` を更新しない

---

## アラート形式

断層検知のアラートは馬単位ではなく「レース単位」のため、代表馬として断層の直前（最後の「勝負圏内」）馬の情報を使用する。

| フィールド | 値 |
|---|---|
| `alertType` | `"オッズ断層[凝縮]"` または `"オッズ断層[拡散]"` |
| `value` | 断層のオッズ比（小数点2桁） |
| `horseNumber` | 断層直前（断層の人気順で直上）の馬番 |
| `horseName` | 断層直前の馬名 |

**例**: `alertType="オッズ断層[凝縮]"`, `value=2.1`（断層比率）, `horseNumber="3"`, `horseName="ディープサクセス"`

---

## 既存コードとの結合点

| 変更対象 | 変更内容 |
|---|---|
| `OddsAnomalyDetector.java` | `previousCliffPosition` フィールド追加 |
| 新メソッド | `detectOddsCliff(List<OddsData> oddsList, String url)` を追加し `detect()` から呼び出す |
| `detect()` のシグネチャ | Step 34 と同様に `url` を引数として受け取る（`detect(List<OddsData> oddsList, String url, Optional<LocalTime> startTime)`） |
| `resetBaselineIfNewDay()` | `previousCliffPosition` のクリアも追加 |

---

## テスト方針

`OddsAnomalyDetectorTest` に以下のケースを追加する:

| テストケース | 期待結果 |
|---|---|
| 4番人気オッズ比が1.8（閾値超え） | 断層位置 = 3（4番目の直前）を検出 |
| 断層位置が前回と同じ | アラートなし |
| 断層位置が前回3から前回2に移動（凝縮） | `"オッズ断層[凝縮]"` アラート |
| 断層位置が前回3から前回5に移動（拡散） | `"オッズ断層[拡散]"` アラート |
| 全隣接比率が1.5未満 | 断層なし、アラートなし |
| 有効馬2頭未満 | スキップ |
| 日付変更後 | `previousCliffPosition` がクリアされ再検出 |

---

## 実装時の注意事項

- `sortedByWin` は既存の `detectRankDivergence()` 内で生成されている。`detectOddsCliff()` では同じソートロジックを再利用するか、`detect()` メソッドで1回ソートして各検知メソッドに渡すようリファクタリングを推奨する
- 断層比率の計算は `double` で十分（比率なので相対値。BigDecimalは不要）
- URL単位の状態管理であるため、`OddsTargetsController.deleteUrl()` 時に `previousCliffPosition.remove(url)` を呼び出すこと
- `sortedByWin.get(i - 1).winOdds()` が0の場合はゼロ除算が発生するため、`winOdds > 0` の馬のみでフィルタリングしてからソートすること
