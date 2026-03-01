package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.AnomalyAlertDto;
import com.oddsalchemist.backend.dto.OddsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * オッズデータの異常を検知するサービス。
 * 以下の2種類の異常を検知する:
 * <ul>
 *   <li>ロジックA: 支持率の急増（+2.0%以上）</li>
 *   <li>ロジックB: 単複オッズの順位乖離（ギャップ3以上）</li>
 * </ul>
 * 上位3番人気（単勝1〜3位）はノイズが大きいため検知対象から除外する。
 */
@Service
public class OddsAnomalyDetector {

    private static final Logger logger = LoggerFactory.getLogger(OddsAnomalyDetector.class);

    /** 支持率急増の閾値（+2.0% = 0.02） */
    static final BigDecimal SUPPORT_RATE_THRESHOLD = new BigDecimal("0.02");

    /** 単複順位乖離の閾値 */
    static final int RANK_GAP_THRESHOLD = 3;

    /** BigDecimal除算時の小数点以下桁数 */
    private static final int SUPPORT_RATE_SCALE = 10;

    /**
     * 前回の単勝オッズを保持するインメモリキャッシュ。
     * キー: "レース名:馬番"（一意な馬識別子）
     */
    private final ConcurrentHashMap<String, Double> previousWinOdds = new ConcurrentHashMap<>();

    /** 最新の異常検知アラートリスト（スレッドセーフ） */
    private final List<AnomalyAlertDto> latestAlerts = new CopyOnWriteArrayList<>();

    /**
     * オッズデータリストを解析し、異常を検知してアラートリストを返します。
     * 検知結果は内部の最新アラートリストに保存されます。
     *
     * @param oddsList 最新のパース済みオッズデータ
     * @return 検知されたアラートのリスト（変更不可）
     */
    public List<AnomalyAlertDto> detect(List<OddsData> oddsList) {
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
                    .map(d -> buildKey(d.raceName(), d.horseNumber()))
                    .collect(Collectors.toSet());

            // 単勝順位マップ（キー: "レース名:馬番", 値: 1始まりの順位）
            Map<String, Integer> winRankMap = new HashMap<>();
            for (int i = 0; i < sortedByWin.size(); i++) {
                OddsData d = sortedByWin.get(i);
                winRankMap.put(buildKey(d.raceName(), d.horseNumber()), i + 1);
            }

            // ロジックA: 支持率急増検知
            detectSupportRateIncrease(validList, top3Keys, alerts);

            // ロジックB: 単複オッズ順位乖離検知
            detectRankDivergence(validList, top3Keys, winRankMap, alerts);

            // 前回データを更新（上位3番人気を含む全有効馬）
            validList.forEach(d -> previousWinOdds.put(buildKey(d.raceName(), d.horseNumber()), d.winOdds()));
        }

        // 最新アラートリストを更新
        latestAlerts.clear();
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
            String key = buildKey(current.raceName(), current.horseNumber());
            if (top3Keys.contains(key)) {
                continue; // 上位3番人気は除外
            }

            Double prevOdds = previousWinOdds.get(key);
            if (prevOdds == null || prevOdds <= 0) {
                continue; // 前回データなし（初回実行）はスキップ
            }

            BigDecimal currentRate = BigDecimal.ONE.divide(
                    BigDecimal.valueOf(current.winOdds()), SUPPORT_RATE_SCALE, RoundingMode.HALF_UP);
            BigDecimal prevRate = BigDecimal.ONE.divide(
                    BigDecimal.valueOf(prevOdds), SUPPORT_RATE_SCALE, RoundingMode.HALF_UP);
            BigDecimal increase = currentRate.subtract(prevRate);

            if (increase.compareTo(SUPPORT_RATE_THRESHOLD) >= 0) {
                double increaseValue = increase.doubleValue();
                alerts.add(new AnomalyAlertDto(
                        current.horseNumber(),
                        current.horseName(),
                        "支持率急増",
                        increaseValue));
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
            placeRankMap.put(buildKey(d.raceName(), d.horseNumber()), i + 1);
        }

        for (OddsData data : validList) {
            String key = buildKey(data.raceName(), data.horseNumber());
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
                        data.horseNumber(),
                        data.horseName(),
                        "順位乖離",
                        (double) gap));
                logger.info("【順位乖離検知】馬番={}, 馬名={}, 単勝順位={}, 複勝順位={}, ギャップ={}",
                        data.horseNumber(), data.horseName(), winRank, placeRank, gap);
            }
        }
    }

    /**
     * 最新の異常検知アラートリストを返します。
     *
     * @return アラートリスト（変更不可）
     */
    public List<AnomalyAlertDto> getLatestAlerts() {
        return Collections.unmodifiableList(new ArrayList<>(latestAlerts));
    }

    /** 馬を一意に識別するキーを生成します。 */
    private String buildKey(String raceName, String horseNumber) {
        return raceName + ":" + horseNumber;
    }
}
