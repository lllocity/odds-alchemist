package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.AnomalyAlertDto;
import com.oddsalchemist.backend.dto.OddsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.oddsalchemist.backend.util.SheetsDates;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * オッズデータの異常を検知するサービス。
 * 以下の6種類の異常を検知する:
 * <ul>
 *   <li>ロジックA: 支持率の急増（前回比 +2.0%以上）</li>
 *   <li>ロジックB: 単複オッズの順位乖離（ギャップ3以上）</li>
 *   <li>ロジックC: その日の初回detect()呼び出し時のオッズを基準値としたトレンド逸脱（基準比 +5.0%以上, 中穴・大穴帯）</li>
 *   <li>ロジックD: 支持率の加速度（時間正規化、0.5%/分以上）</li>
 *   <li>ロジックE: フェーズ別トレンド逸脱（発走までの残り時間に応じた3段階基準点）</li>
 *   <li>ロジックF: オッズ断層（クリフ）の動的検知（断層位置の凝縮/拡散）</li>
 * </ul>
 * 上位3番人気（単勝1〜3位）はノイズが大きいため検知対象から除外する。
 * 初期基準値は日次リセットされ、その日の最初の検知呼び出し時に設定される。
 */
@Service
public class OddsAnomalyDetector {

    private static final Logger logger = LoggerFactory.getLogger(OddsAnomalyDetector.class);

    /** 支持率急増の閾値（前回比 +2.0% = 0.02） */
    static final BigDecimal SUPPORT_RATE_THRESHOLD = new BigDecimal("0.02");

    /** その日の初回detect()呼び出し時のオッズからのトレンド逸脱閾値（基準比 +5.0% = 0.05） */
    static final BigDecimal TREND_DEVIATION_THRESHOLD = new BigDecimal("0.05");

    /** 単複順位乖離の閾値 */
    static final int RANK_GAP_THRESHOLD = 3;

    /** トレンド逸脱の対象人気帯（中穴: 5〜8番人気, 大穴: 9〜12番人気） */
    static final int TREND_RANK_MIN = 5;
    static final int TREND_RANK_MAX = 12;

    /** 支持率加速度の閾値（0.5%/分 = 0.005） */
    static final BigDecimal ACCELERATION_THRESHOLD = new BigDecimal("0.005");

    /** オッズ断層（クリフ）の閾値（隣接オッズ比 1.5倍以上で断層とみなす） */
    static final double CLIFF_RATIO_THRESHOLD = 1.5;

    /** BigDecimal除算時の小数点以下桁数 */
    private static final int SUPPORT_RATE_SCALE = 10;

    /** 発走までの残り時間に応じたフェーズ定義 */
    enum Phase { MORNING, PRE_30, PRE_10 }

    /** 前回スナップショット（単勝オッズ＋取得時刻）を保持するインメモリキャッシュ。キー: "URL:馬番" */
    private final ConcurrentHashMap<String, OddsSnapshot> previousSnapshots = new ConcurrentHashMap<>();

    /**
     * その日の初回detect()呼び出し時のオッズを保持するインメモリキャッシュ。
     * putIfAbsent で初回のみ設定され、日次でリセットされる。
     * キー: "URL:馬番"
     */
    private final ConcurrentHashMap<String, Double> baselineWinOdds = new ConcurrentHashMap<>();

    /**
     * フェーズ別基準点を保持するインメモリキャッシュ。
     * キー: "URL:馬番", 値: Phase → 基準単勝オッズ
     */
    private final ConcurrentHashMap<String, Map<Phase, Double>> phaseBaselines = new ConcurrentHashMap<>();

    /**
     * 前回の断層位置（n番人気とn+1番人気の間）を保持するキャッシュ。
     * キー: URL, 値: 断層インデックス（sortedByWin の 1-based の右側位置）
     */
    private final ConcurrentHashMap<String, Integer> previousCliffPosition = new ConcurrentHashMap<>();

    /** 最新の異常検知アラートリスト（スレッドセーフ） */
    private final List<AnomalyAlertDto> latestAlerts = new CopyOnWriteArrayList<>();

    /** 基準値の最終リセット日（日付変更を検知するために使用） */
    private volatile LocalDate lastBaselineResetDate = LocalDate.MIN;

    /** 時刻取得に使用するクロック（テストで差し替え可能） */
    private final Clock clock;

    /** Spring が使用するデフォルトコンストラクタ */
    public OddsAnomalyDetector() {
        this(Clock.systemDefaultZone());
    }

    /** テスト用コンストラクタ（任意のClockを注入可能） */
    OddsAnomalyDetector(Clock clock) {
        this.clock = clock;
    }

    /**
     * オッズデータリストを解析し、異常を検知してアラートリストを返します。
     * 発走時刻なし（MORNING フェーズ固定）で呼び出す後方互換オーバーロード。
     *
     * @param oddsList 最新のパース済みオッズデータ
     * @return 検知されたアラートのリスト（変更不可）
     */
    public List<AnomalyAlertDto> detect(List<OddsData> oddsList) {
        return detect(oddsList, Optional.empty());
    }

    /**
     * オッズデータリストを解析し、異常を検知してアラートリストを返します。
     * 検知結果は内部の最新アラートリストに保存されます。
     *
     * @param oddsList  最新のパース済みオッズデータ
     * @param startTime 発走予定時刻（フェーズ判定に使用。空の場合は MORNING 固定）
     * @return 検知されたアラートのリスト（変更不可）
     */
    public List<AnomalyAlertDto> detect(List<OddsData> oddsList, Optional<LocalTime> startTime) {
        // 日付変更時に初期基準値をリセット
        resetBaselineIfNewDay();

        // 現在時刻を1回だけ取得（加速度計算とスナップショット保存で同じ基準時刻を使う）
        Instant now = Instant.now(clock);

        List<AnomalyAlertDto> alerts = new ArrayList<>();

        // 単勝オッズが有効なデータのみを対象とする
        List<OddsData> validList = oddsList.stream()
                .filter(d -> d.winOdds() != null && d.winOdds() > 0)
                .collect(Collectors.toList());

        if (!validList.isEmpty()) {
            // 単勝オッズ昇順でソート（値が小さいほど人気上位）
            List<OddsData> sortedByWin = validList.stream()
                    .sorted(Comparator.comparingDouble(OddsData::winOdds))
                    .collect(Collectors.toList());

            // 上位3番人気の除外対象キーセット（単勝1〜3位）
            Set<String> top3Keys = sortedByWin.stream()
                    .limit(3)
                    .map(d -> buildKey(d.url(), d.horseNumber()))
                    .collect(Collectors.toSet());

            // 単勝順位マップ（キー: "レース名:馬番", 値: 1始まりの順位）
            Map<String, Integer> winRankMap = new HashMap<>();
            for (int i = 0; i < sortedByWin.size(); i++) {
                OddsData d = sortedByWin.get(i);
                winRankMap.put(buildKey(d.url(), d.horseNumber()), i + 1);
            }

            // ロジックA: 支持率急増検知
            detectSupportRateIncrease(validList, top3Keys, alerts);

            // ロジックB: 単複オッズ順位乖離検知
            detectRankDivergence(validList, top3Keys, winRankMap, alerts);

            // ロジックC: その日の初回detect()呼び出し時のオッズからのトレンド逸脱検知
            detectTrendDeviation(validList, top3Keys, winRankMap, alerts);

            // ロジックD: 支持率の加速度検知（時間正規化）
            detectAcceleration(validList, top3Keys, now, alerts);

            // ロジックE: フェーズ別トレンド逸脱検知
            detectPhaseDeviation(validList, top3Keys, startTime, alerts);

            // ロジックF: オッズ断層（クリフ）の動的検知
            detectOddsCliff(sortedByWin, sortedByWin.get(0).url(), alerts);

            // 前回スナップショットを更新（上位3番人気を含む全有効馬）
            validList.forEach(d -> previousSnapshots.put(buildKey(d.url(), d.horseNumber()),
                    new OddsSnapshot(d.winOdds(), now)));
        }

        // 検知したアラートを累積リストに追加（起動後の全検知履歴を保持）
        latestAlerts.addAll(alerts);

        return Collections.unmodifiableList(alerts);
    }

    /**
     * ロジックA: 支持率の急増を検知します。
     * 支持率 = 1 / 単勝オッズ（オッズの逆数）
     * 計算式: (1 / 直近オッズ) - (1 / 過去オッズ) >= 0.02
     * Double型の精度問題を避けるため、BigDecimalで計算する。
     */
    private void detectSupportRateIncrease(
            List<OddsData> validList,
            Set<String> top3Keys,
            List<AnomalyAlertDto> alerts) {

        for (OddsData current : validList) {
            String key = buildKey(current.url(), current.horseNumber());
            if (top3Keys.contains(key)) {
                continue; // 上位3番人気は除外
            }

            OddsSnapshot prevSnapshot = previousSnapshots.get(key);
            if (prevSnapshot == null || prevSnapshot.winOdds() <= 0) {
                continue; // 前回データなし（初回実行）はスキップ
            }
            double prevOdds = prevSnapshot.winOdds();

            BigDecimal increase = toSupportRate(current.winOdds()).subtract(toSupportRate(prevOdds));

            if (increase.compareTo(SUPPORT_RATE_THRESHOLD) >= 0) {
                alerts.add(new AnomalyAlertDto(
                        current.raceName(),
                        current.horseNumber(),
                        current.horseName(),
                        "支持率急増",
                        increase.doubleValue(),
                        LocalDateTime.now(clock).format(SheetsDates.FORMATTER)));
                logger.info("【支持率急増検知】馬番={}, 馬名={}, 支持率増加={}, 前回オッズ={}, 現在オッズ={}",
                        current.horseNumber(), current.horseName(), increase, prevOdds, current.winOdds());
            }
        }
    }

    /**
     * ロジックB: 単複オッズの順位乖離（歪み）を検知します。
     * 計算式: 単勝人気の順位 - 複勝下限オッズの順位 >= 3
     * 単勝より複勝の方が相対的に有利な馬を抽出する。
     */
    private void detectRankDivergence(
            List<OddsData> validList,
            Set<String> top3Keys,
            Map<String, Integer> winRankMap,
            List<AnomalyAlertDto> alerts) {

        // 複勝下限オッズが有効なデータで昇順ソートし、順位マップを作成
        List<OddsData> validPlaceList = validList.stream()
                .filter(d -> d.placeOddsMin() != null && d.placeOddsMin() > 0)
                .sorted(Comparator.comparingDouble(OddsData::placeOddsMin))
                .collect(Collectors.toList());

        Map<String, Integer> placeRankMap = new HashMap<>();
        for (int i = 0; i < validPlaceList.size(); i++) {
            OddsData d = validPlaceList.get(i);
            placeRankMap.put(buildKey(d.url(), d.horseNumber()), i + 1);
        }

        for (OddsData data : validList) {
            String key = buildKey(data.url(), data.horseNumber());
            if (top3Keys.contains(key)) {
                continue; // 上位3番人気は除外
            }

            Integer winRank = winRankMap.get(key);
            Integer placeRank = placeRankMap.get(key);

            if (winRank == null || placeRank == null) {
                continue; // 複勝オッズ未確定などで順位が算出できない場合はスキップ
            }

            int gap = winRank - placeRank;
            if (gap >= RANK_GAP_THRESHOLD) {
                alerts.add(new AnomalyAlertDto(
                        data.raceName(),
                        data.horseNumber(),
                        data.horseName(),
                        "順位乖離",
                        (double) gap,
                        LocalDateTime.now(clock).format(SheetsDates.FORMATTER)));
                logger.info("【順位乖離検知】馬番={}, 馬名={}, 単勝順位={}, 複勝順位={}, ギャップ={}",
                        data.horseNumber(), data.horseName(), winRank, placeRank, gap);
            }
        }
    }

    /**
     * ロジックC: その日の初回detect()呼び出し時のオッズからのトレンド逸脱を検知します。
     * 中穴帯（5〜8番人気）・大穴帯（9〜12番人気）の馬を対象とする。
     * 計算式: (1 / 現在オッズ) - (1 / 基準オッズ) >= 0.05
     * 初回呼び出し時に基準値を設定し、以降は比較のみ行う（日次リセットあり）。
     * 基準値は人気帯変動に備えて対象外の馬にも設定する。
     */
    private void detectTrendDeviation(
            List<OddsData> validList,
            Set<String> top3Keys,
            Map<String, Integer> winRankMap,
            List<AnomalyAlertDto> alerts) {

        for (OddsData current : validList) {
            String key = buildKey(current.url(), current.horseNumber());
            if (top3Keys.contains(key)) {
                continue; // 上位3番人気は除外
            }

            // 基準値を全馬に設定（初回のみ: 人気帯変動に備えて範囲外でも記録）
            baselineWinOdds.putIfAbsent(key, current.winOdds());

            // 中穴・大穴帯（5〜12番人気）のみアラート判定
            Integer winRank = winRankMap.get(key);
            if (winRank == null || winRank < TREND_RANK_MIN || winRank > TREND_RANK_MAX) {
                continue;
            }

            Double baselineOdds = baselineWinOdds.get(key);
            if (baselineOdds == null || baselineOdds <= 0) {
                continue;
            }

            BigDecimal deviation = toSupportRate(current.winOdds()).subtract(toSupportRate(baselineOdds));

            if (deviation.compareTo(TREND_DEVIATION_THRESHOLD) >= 0) {
                alerts.add(new AnomalyAlertDto(
                        current.raceName(),
                        current.horseNumber(),
                        current.horseName(),
                        "トレンド逸脱",
                        deviation.doubleValue(),
                        LocalDateTime.now(clock).format(SheetsDates.FORMATTER)));
                logger.info("【トレンド逸脱検知】馬番={}, 馬名={}, 基準オッズ={}, 現在オッズ={}, 逸脱量={}, 単勝順位={}",
                        current.horseNumber(), current.horseName(), baselineOdds, current.winOdds(), deviation, winRank);
            }
        }
    }

    /**
     * ロジックE: フェーズ別トレンド逸脱を検知します。
     * 発走までの残り時間に応じた3段階のフェーズ（MORNING / PRE_30 / PRE_10）ごとに
     * 基準点を設け、同フェーズ内でのオッズ変化量を監視します。
     * 上位3番人気を除いた全馬が対象（ロジックCの人気帯制限なし）。
     */
    private void detectPhaseDeviation(
            List<OddsData> validList,
            Set<String> top3Keys,
            Optional<LocalTime> startTime,
            List<AnomalyAlertDto> alerts) {

        Phase phase = determinePhase(startTime);
        if (phase == null) return; // 発走後はスキップ

        for (OddsData current : validList) {
            String key = buildKey(current.url(), current.horseNumber());
            if (top3Keys.contains(key)) continue;

            Map<Phase, Double> baselines = phaseBaselines.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            baselines.putIfAbsent(phase, current.winOdds());
            Double baselineOdds = baselines.get(phase);
            if (baselineOdds == null || baselineOdds <= 0) continue;

            BigDecimal deviation = toSupportRate(current.winOdds()).subtract(toSupportRate(baselineOdds));
            if (deviation.compareTo(TREND_DEVIATION_THRESHOLD) >= 0) {
                String alertType = switch (phase) {
                    case MORNING -> "フェーズ逸脱[朝]";
                    case PRE_30  -> "フェーズ逸脱[30分前]";
                    case PRE_10  -> "フェーズ逸脱[10分前]";
                };
                alerts.add(new AnomalyAlertDto(
                        current.raceName(),
                        current.horseNumber(),
                        current.horseName(),
                        alertType,
                        deviation.setScale(4, RoundingMode.HALF_UP).doubleValue(),
                        LocalDateTime.now(clock).format(SheetsDates.FORMATTER)));
                logger.info("【フェーズ逸脱検知】馬番={}, 馬名={}, フェーズ={}, 基準オッズ={}, 現在オッズ={}, 逸脱量={}",
                        current.horseNumber(), current.horseName(), phase,
                        baselineOdds, current.winOdds(), deviation);
            }
        }
    }

    /**
     * ロジックF: オッズ断層（クリフ）の動的検知。
     * 単勝オッズの隣接比率が {@code CLIFF_RATIO_THRESHOLD} 以上となる位置を「断層」とし、
     * 前回と比べて断層位置が上位方向に移動した場合を「凝縮」、下位方向を「拡散」として検知する。
     * 代表馬は断層直前（最後の「勝負圏内」）の馬を使用する。
     *
     * @param sortedByWin 単勝オッズ昇順ソート済みリスト（winOdds > 0 のみ）
     * @param url         レース識別URL
     * @param alerts      検知アラートの追記先
     */
    private void detectOddsCliff(List<OddsData> sortedByWin, String url, List<AnomalyAlertDto> alerts) {
        if (sortedByWin.size() < 3) return;

        int cliffPosition = -1;
        double cliffRatio = 0.0;
        for (int i = 1; i < sortedByWin.size(); i++) {
            double ratio = sortedByWin.get(i).winOdds() / sortedByWin.get(i - 1).winOdds();
            if (ratio >= CLIFF_RATIO_THRESHOLD) {
                cliffPosition = i;
                cliffRatio = ratio;
                break; // 最上位の断層のみ対象
            }
        }

        if (cliffPosition < 0) return; // 断層なし → previousCliffPosition は更新しない

        Integer prevPosition = previousCliffPosition.get(url);
        previousCliffPosition.put(url, cliffPosition);

        if (prevPosition == null || prevPosition.equals(cliffPosition)) return; // 初回 or 変化なし

        String direction = cliffPosition < prevPosition ? "凝縮" : "拡散";
        OddsData representative = sortedByWin.get(cliffPosition - 1); // 断層直前の馬
        double roundedRatio = BigDecimal.valueOf(cliffRatio).setScale(2, RoundingMode.HALF_UP).doubleValue();
        alerts.add(new AnomalyAlertDto(
                representative.raceName(),
                representative.horseNumber(),
                representative.horseName(),
                "オッズ断層[" + direction + "]",
                roundedRatio,
                LocalDateTime.now(clock).format(SheetsDates.FORMATTER)));
        logger.info("【オッズ断層検知】方向={}, 断層位置={}, 断層比率={}, 断層直前馬番={}, 馬名={}",
                direction, cliffPosition, roundedRatio,
                representative.horseNumber(), representative.horseName());
    }

    /**
     * 発走時刻と現在時刻からフェーズを判定します。
     * @return MORNING / PRE_30 / PRE_10 のいずれか、発走後は null
     */
    private Phase determinePhase(Optional<LocalTime> startTime) {
        if (startTime.isEmpty()) return Phase.MORNING;
        long minutesUntilStart = ChronoUnit.MINUTES.between(LocalTime.now(clock), startTime.get());
        if (minutesUntilStart < 0)  return null;      // 発走後
        if (minutesUntilStart <= 10) return Phase.PRE_10;
        if (minutesUntilStart <= 30) return Phase.PRE_30;
        return Phase.MORNING;
    }

    /**
     * 日付が変わった場合に初期基準値をリセットします。
     * 毎日の初回スクレイピングで新たな基準値が設定されます。
     */
    private void resetBaselineIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(lastBaselineResetDate)) {
            baselineWinOdds.clear();
            previousSnapshots.clear();
            phaseBaselines.clear();
            previousCliffPosition.clear();
            lastBaselineResetDate = today;
            logger.info("日付変更を検知しました。初回オッズ基準値をリセットします: {}", today);
        }
    }

    /**
     * ロジックD: 支持率の加速度（時間正規化）を検知します。
     * スクレイピング間隔が変動しても「単位時間あたりの支持率変化量」で一貫して判定する。
     * 計算式: (Δ支持率) / (Δ時刻[分]) >= ACCELERATION_THRESHOLD (0.005 = 0.5%/分)
     */
    private void detectAcceleration(
            List<OddsData> validList,
            Set<String> top3Keys,
            Instant now,
            List<AnomalyAlertDto> alerts) {

        for (OddsData current : validList) {
            String key = buildKey(current.url(), current.horseNumber());
            if (top3Keys.contains(key)) {
                continue;
            }

            OddsSnapshot prev = previousSnapshots.get(key);
            if (prev == null || prev.winOdds() <= 0) {
                continue;
            }

            long deltaSeconds = ChronoUnit.SECONDS.between(prev.processedAt(), now);
            if (deltaSeconds <= 0) {
                continue;
            }

            BigDecimal currentRate = toSupportRate(current.winOdds());
            BigDecimal prevRate    = toSupportRate(prev.winOdds());
            BigDecimal deltaRate   = currentRate.subtract(prevRate);
            BigDecimal deltaMin    = BigDecimal.valueOf(deltaSeconds / 60.0);
            BigDecimal acceleration = deltaRate.divide(deltaMin, 6, RoundingMode.HALF_UP);

            if (acceleration.compareTo(ACCELERATION_THRESHOLD) >= 0) {
                alerts.add(new AnomalyAlertDto(
                        current.raceName(),
                        current.horseNumber(),
                        current.horseName(),
                        "支持率加速",
                        acceleration.setScale(3, RoundingMode.HALF_UP).doubleValue(),
                        LocalDateTime.now(clock).format(SheetsDates.FORMATTER)));
                logger.info("【支持率加速検知】馬番={}, 馬名={}, 加速度={}/分, 経過秒={}, 前回オッズ={}, 現在オッズ={}",
                        current.horseNumber(), current.horseName(), acceleration, deltaSeconds,
                        prev.winOdds(), current.winOdds());
            }
        }
    }

    /**
     * 指定URLの前回スナップショット・基準値キャッシュを削除します。
     * URL監視対象から削除する際に呼び出します。
     *
     * @param url 削除対象URL
     */
    public void clearStateForUrl(String url) {
        String prefix = url + ":";
        previousSnapshots.keySet().removeIf(key -> key.startsWith(prefix));
        baselineWinOdds.keySet().removeIf(key -> key.startsWith(prefix));
        phaseBaselines.keySet().removeIf(key -> key.startsWith(prefix));
        previousCliffPosition.remove(url);
        logger.info("URLの検知状態をクリアしました: {}", url);
    }

    /**
     * 最新の異常検知アラートリストを返します。
     *
     * @return アラートリスト（変更不可）
     */
    public List<AnomalyAlertDto> getLatestAlerts() {
        return Collections.unmodifiableList(new ArrayList<>(latestAlerts));
    }

    /** オッズを支持率（1 / オッズ）に変換します。BigDecimalで精度を保証します。 */
    private BigDecimal toSupportRate(double odds) {
        return BigDecimal.ONE.divide(BigDecimal.valueOf(odds), SUPPORT_RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 馬を一意に識別するキーを生成します。同一レース名が複数存在しうるためURLで識別します。 */
    private String buildKey(String url, String horseNumber) {
        return url + ":" + horseNumber;
    }

    /** 前回オッズと取得時刻のスナップショット（ロジックD の加速度計算に使用）。 */
    record OddsSnapshot(double winOdds, Instant processedAt) {}
}
