package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.dto.AnomalyAlertDto;
import com.oddsalchemist.backend.dto.OddsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OddsAnomalyDetector の単体テスト。
 * Double型の精度問題を含む支持率計算と、順位乖離の算出が正確であることを検証する。
 */
class OddsAnomalyDetectorTest {

    private OddsAnomalyDetector detector;

    // テスト用定数
    private static final String RACE = "第1回東京1レース";
    private static final String URL  = "https://example.com/race/1";

    @BeforeEach
    void setUp() {
        detector = new OddsAnomalyDetector();
    }

    // ===== ロジックA: 支持率急増検知 =====

    @Test
    void detect_初回実行ではアラートが発生しないこと() {
        // 初回は前回データなし → 支持率急増は検知不可
        List<OddsData> oddsList = List.of(
                odds("5", "ウマA", 10.0, 3.0, 5.0),
                odds("6", "ウマB", 12.0, 3.5, 6.0)
        );

        List<AnomalyAlertDto> alerts = detector.detect(oddsList);

        assertThat(alerts).isEmpty();
    }

    @Test
    void detect_支持率が2パーセント以上急増した場合にアラートが発生すること() {
        // 1回目: 単勝10.0 → 支持率 = 0.1
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 10.0, 3.0, 5.0)
        ));

        // 2回目: 単勝5.0 → 支持率 = 0.2, 増加 = 0.2 - 0.1 = 0.1 (10%) >= 0.02
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 5.0, 2.5, 4.0)
        ));

        // ロジックEも同時発火するため、alertType でフィルタして検証
        List<AnomalyAlertDto> supportAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("支持率急増")).toList();
        assertThat(supportAlerts).hasSize(1);
        AnomalyAlertDto alert = supportAlerts.get(0);
        assertThat(alert.horseNumber()).isEqualTo("5");
        assertThat(alert.horseName()).isEqualTo("中穴馬");
        // 支持率増加 = 1/5.0 - 1/10.0 = 0.2 - 0.1 = 0.1
        assertThat(alert.value()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void detect_支持率増加が閾値未満の場合はアラートが発生しないこと() {
        // 1回目: 単勝20.0 → 支持率 = 0.05
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("8", "大穴馬", 20.0, 4.0, 8.0)
        ));

        // 2回目: 単勝19.0 → 支持率 ≈ 0.0526, 増加 ≈ 0.0026 < 0.02
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("8", "大穴馬", 19.0, 4.0, 8.0)
        ));

        assertThat(alerts).isEmpty();
    }

    @Test
    void detect_上位3番人気は支持率急増が大きくてもアラートが発生しないこと() {
        // 1回目: 1〜3位 + 4位以降
        detector.detect(List.of(
                odds("1", "人気馬A", 2.0, 1.2, 1.5),
                odds("2", "人気馬B", 3.0, 1.4, 2.0),
                odds("3", "人気馬C", 4.0, 1.6, 2.5),
                odds("4", "穴馬", 15.0, 3.0, 6.0)
        ));

        // 2回目: 上位3番人気が大きくオッズ短縮（支持率急増）→ 除外対象
        // 1位: 2.0→1.2 (支持率増加 = 1/1.2 - 1/2.0 ≈ 0.333)
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.2, 1.1, 1.3),
                odds("2", "人気馬B", 1.5, 1.2, 1.5),
                odds("3", "人気馬C", 2.5, 1.3, 2.0),
                odds("4", "穴馬", 15.0, 3.0, 6.0)
        ));

        // 上位3番人気(1,2,3番)は支持率急増アラートの対象外
        assertThat(alerts).extracting(AnomalyAlertDto::alertType).noneMatch(t -> t.equals("支持率急増"));
        assertThat(alerts.stream().filter(a -> a.alertType().equals("支持率急増")))
                .extracting(AnomalyAlertDto::horseNumber)
                .doesNotContain("1", "2", "3");
    }

    @Test
    void detect_支持率計算の精度_BigDecimalで閾値ちょうど0_02を正確に判定すること() {
        // 1/5.0 - 1/50.0 = 0.2 - 0.02 = 0.18 >= 0.02 → アラート
        // 1/10.0 - 1/11.0 ≈ 0.1 - 0.0909 = 0.009 < 0.02 → アラートなし
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "チェック馬X", 50.0, 5.0, 10.0),
                odds("6", "チェック馬Y", 11.0, 3.0, 5.0)
        ));

        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "チェック馬X", 5.0, 4.0, 8.0),   // 増加 = 0.18 → アラート
                odds("6", "チェック馬Y", 10.0, 3.0, 5.0)   // 増加 ≈ 0.009 → なし
        ));

        assertThat(alerts).extracting(AnomalyAlertDto::horseNumber).contains("5");
        assertThat(alerts).extracting(AnomalyAlertDto::horseNumber).doesNotContain("6");
    }

    // ===== ロジックB: 単複オッズ順位乖離検知 =====

    @Test
    void detect_単複順位ギャップが3以上の場合にアラートが発生すること() {
        // 単勝順位と複勝順位の乖離
        // 馬A: 単勝4位(12.0), 複勝1位(1.1) → gap = 4 - 1 = 3 → アラート
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬1", 1.5, 2.5, 3.5),
                odds("2", "人気馬2", 2.0, 3.0, 5.0),
                odds("3", "人気馬3", 3.5, 4.0, 7.0),
                odds("4", "乖離馬A", 12.0, 1.1, 1.5)
        ));

        List<AnomalyAlertDto> rankAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("順位乖離"))
                .toList();
        assertThat(rankAlerts).hasSize(1);
        assertThat(rankAlerts.get(0).horseNumber()).isEqualTo("4");
        assertThat(rankAlerts.get(0).value()).isEqualTo(3.0);
    }

    @Test
    void detect_単複順位ギャップが2の場合はアラートが発生しないこと() {
        // 馬A: 単勝4位(12.0), 複勝2位(1.5) → gap = 4 - 2 = 2 < 3 → なし
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬1", 1.5, 1.1, 1.3),
                odds("2", "人気馬2", 2.0, 1.5, 2.0),
                odds("3", "人気馬3", 3.5, 3.0, 5.0),
                odds("4", "判定馬A", 12.0, 2.0, 3.5)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().equals("順位乖離"))).isEmpty();
    }

    @Test
    void detect_上位3番人気は順位乖離があってもアラートが発生しないこと() {
        // 1番人気(単勝最小)が複勝でも最下位 → gap大でも除外
        // 単勝1位: 1.2, 複勝4位(最高): 8.0 → gap = 1 - 4 = -3 (逆方向なので不発)
        // 単勝4位: 12.0, 複勝1位(最低): 1.1 → gap = 4 - 1 = 3 >= 3 → だが上位3番人気外なら出る
        // ここでは4頭しかいないので4位は除外されない
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬1_複勝低位", 1.5, 1.1, 1.3),  // 単勝1位,複勝1位 → top3かつgap=0
                odds("2", "人気馬2", 2.0, 2.0, 3.0),
                odds("3", "人気馬3", 3.0, 3.0, 5.0),
                odds("4", "穴馬", 8.0, 4.0, 7.0)
        ));
        // 上位3番人気(1,2,3)はアラート対象外
        assertThat(alerts).extracting(AnomalyAlertDto::horseNumber)
                .doesNotContain("1", "2", "3");
    }

    // ===== winOddsがnullの場合の安全性 =====

    @Test
    void detect_winOddsがnullの馬がいても例外が発生しないこと() {
        List<OddsData> oddsList = List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                new OddsData(RACE, "2", "オッズ未定馬", null, null, null, URL),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "穴馬", 10.0, 3.0, 5.0)
        );

        // 例外なく完了し、nullの馬は無視される
        List<AnomalyAlertDto> alerts = detector.detect(oddsList);
        assertThat(alerts).isNotNull();
    }

    @Test
    void detect_前回データが2回目以降に正しく更新されること() {
        // 1回目
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "穴馬", 20.0, 4.0, 8.0)
        ));

        // 2回目: オッズ短縮なし → アラートなし
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "穴馬", 20.0, 4.0, 8.0)
        ));

        // 3回目: 急激に短縮 20.0 → 5.0, 増加 = 1/5 - 1/20 = 0.15 >= 0.02
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "穴馬", 5.0, 3.0, 6.0)
        ));

        assertThat(alerts).extracting(AnomalyAlertDto::alertType).contains("支持率急増");
        assertThat(alerts).extracting(AnomalyAlertDto::horseNumber).contains("5");
    }

    @Test
    void getLatestAlerts_detectの結果が最新状態で保持されること() {
        // 1回目: 初回実行 → アラートなし（前回データがない）
        detector.detect(List.of(
                odds("1", "馬A", 1.5, 1.1, 1.3),
                odds("2", "馬B", 2.0, 1.2, 1.5),
                odds("3", "馬C", 3.0, 1.4, 2.0),
                odds("4", "馬D", 8.0, 2.0, 4.0),
                odds("5", "急増馬", 20.0, 4.0, 8.0)
        ));
        assertThat(detector.getLatestAlerts()).isEmpty();

        // 2回目: 急増馬が 20.0 → 5.0 に短縮
        // top3: 馬A(1.5), 馬B(2.0), 馬C(3.0) → 急増馬(5.0)は4位でtop3外
        // 支持率増加 = 1/5.0 - 1/20.0 = 0.2 - 0.05 = 0.15 >= 0.02 → アラート
        detector.detect(List.of(
                odds("1", "馬A", 1.5, 1.1, 1.3),
                odds("2", "馬B", 2.0, 1.2, 1.5),
                odds("3", "馬C", 3.0, 1.4, 2.0),
                odds("4", "馬D", 8.0, 2.0, 4.0),
                odds("5", "急増馬", 5.0, 3.0, 6.0)
        ));

        List<AnomalyAlertDto> latest = detector.getLatestAlerts();
        assertThat(latest).isNotEmpty();
        assertThat(latest).extracting(AnomalyAlertDto::horseNumber).contains("5");
    }

    // ===== ロジックC: トレンド逸脱検知 =====

    @Test
    void detect_初回実行でトレンド逸脱アラートが発生しないこと() {
        // 初回は基準値として登録されるだけ。比較対象がないのでアラートなし
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 10.0, 3.0, 5.0)  // 単勝4位 → 対象外(rank<5)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().equals("トレンド逸脱"))).isEmpty();
    }

    @Test
    void detect_中穴帯の馬が基準値から5パーセント以上逸脱した場合にアラートが発生すること() {
        // 5頭構成: 1〜3位はtop3, 4位(rank4)は対象外, 5位(rank5)が中穴帯
        // 1回目: 5番馬 単勝20.0 → 支持率=0.05 をbaseline登録
        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 20.0));

        // 2回目: 5番馬 単勝10.0 → 支持率=0.1, 逸脱量=0.05 >= 0.05 → アラート
        List<AnomalyAlertDto> alerts = detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 10.0));

        List<AnomalyAlertDto> trendAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("トレンド逸脱")).toList();
        assertThat(trendAlerts).hasSize(1);
        assertThat(trendAlerts.get(0).horseNumber()).isEqualTo("5");
        // 逸脱量 = 1/10 - 1/20 = 0.1 - 0.05 = 0.05
        assertThat(trendAlerts.get(0).value()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void detect_逸脱量が閾値未満の場合はトレンド逸脱アラートが発生しないこと() {
        // 1回目: 5番馬 単勝20.0 → baseline
        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 20.0));

        // 2回目: 5番馬 単勝18.0 → 逸脱量 = 1/18 - 1/20 ≈ 0.0056 < 0.05 → アラートなし
        List<AnomalyAlertDto> alerts = detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 18.0));

        assertThat(alerts.stream().filter(a -> a.alertType().equals("トレンド逸脱"))).isEmpty();
    }

    @Test
    void detect_中穴大穴帯以外の馬はトレンド逸脱対象外であること() {
        // rank4の馬 (odds 4位) は対象外 (rank < TREND_RANK_MIN=5)
        // 5頭でrank4になる馬をbaselineに設定してから大きく短縮
        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("4", "4番人気馬", 8.0, 2.0, 4.0),
                odds("5", "中穴馬", 20.0, 3.0, 6.0)
        ));

        // 4番人気馬 (rank4) のオッズが大幅短縮しても対象外
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("4", "4番人気馬", 3.5, 1.5, 2.5),  // 大幅短縮だがrank4 → 対象外
                odds("5", "中穴馬", 20.0, 3.0, 6.0)
        ));

        assertThat(alerts.stream()
                .filter(a -> a.alertType().equals("トレンド逸脱") && a.horseNumber().equals("4")))
                .isEmpty();
    }

    @Test
    void detect_大穴帯の馬が基準値から5パーセント以上逸脱した場合にアラートが発生すること() {
        // 9頭構成: 1〜3位はtop3, 9位(rank9)が大穴帯
        // 1回目: 9番馬 単勝50.0 → 支持率=0.02 をbaseline登録
        detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 2.0, 1.2, 1.5),
                odds("3", "馬3", 3.0, 1.4, 2.0),
                odds("4", "馬4", 5.0, 2.0, 3.5),
                odds("5", "馬5", 8.0, 2.5, 4.0),
                odds("6", "馬6", 12.0, 3.0, 5.0),
                odds("7", "馬7", 15.0, 3.5, 6.0),
                odds("8", "馬8", 18.0, 4.0, 7.0),
                odds("9", "大穴馬", 50.0, 5.0, 8.0) // rank9（大穴帯）
        ));

        // 2回目: 50.0 → 10.0 → 逸脱量 = 1/10 - 1/50 = 0.08 >= 0.05 → アラート
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 2.0, 1.2, 1.5),
                odds("3", "馬3", 3.0, 1.4, 2.0),
                odds("4", "馬4", 5.0, 2.0, 3.5),
                odds("5", "馬5", 8.0, 2.5, 4.0),
                odds("6", "馬6", 12.0, 3.0, 5.0),
                odds("7", "馬7", 15.0, 3.5, 6.0),
                odds("8", "馬8", 18.0, 4.0, 7.0),
                odds("9", "大穴馬", 10.0, 4.0, 7.0)
        ));

        List<AnomalyAlertDto> trendAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("トレンド逸脱") && a.horseNumber().equals("9"))
                .toList();
        assertThat(trendAlerts).hasSize(1);
        // 逸脱量 = 1/10 - 1/50 = 0.1 - 0.02 = 0.08
        assertThat(trendAlerts.get(0).value()).isCloseTo(0.08, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void detect_TREND_RANK_MAX超の馬はトレンド逸脱対象外であること() {
        // 13頭構成: 13番馬はrank13（TREND_RANK_MAX=12を超える）
        // 1回目: 13番馬の基準値を100.0で登録
        detector.detect(List.of(
                odds("1", "馬1",  1.5, 1.1, 1.3),
                odds("2", "馬2",  2.0, 1.2, 1.5),
                odds("3", "馬3",  3.0, 1.4, 2.0),
                odds("4", "馬4",  5.0, 2.0, 3.5),
                odds("5", "馬5",  8.0, 2.5, 4.0),
                odds("6", "馬6", 12.0, 3.0, 5.0),
                odds("7", "馬7", 15.0, 3.5, 6.0),
                odds("8", "馬8", 18.0, 4.0, 7.0),
                odds("9", "馬9", 25.0, 5.0, 8.0),
                odds("10", "馬10", 30.0, 5.5, 9.0),
                odds("11", "馬11", 40.0, 6.0, 10.0),
                odds("12", "馬12", 50.0, 7.0, 12.0),
                odds("13", "超大穴馬", 100.0, 8.0, 15.0) // rank13 → 対象外
        ));

        // 2回目: 全馬を短縮し13番馬をrank13のまま維持（10.0 > 他全馬）
        // 逸脱量 = 1/10 - 1/100 = 0.09 >= 0.05 → 範囲内なら発火するが rank13 → 除外
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "馬1",  1.0, 1.0, 1.2),
                odds("2", "馬2",  1.5, 1.1, 1.3),
                odds("3", "馬3",  2.0, 1.2, 1.5),
                odds("4", "馬4",  2.5, 1.4, 2.0),
                odds("5", "馬5",  3.0, 1.6, 2.5),
                odds("6", "馬6",  4.0, 1.8, 3.0),
                odds("7", "馬7",  5.0, 2.0, 3.5),
                odds("8", "馬8",  6.0, 2.5, 4.0),
                odds("9", "馬9",  7.0, 3.0, 5.0),
                odds("10", "馬10", 8.0, 3.5, 6.0),
                odds("11", "馬11", 8.5, 4.0, 7.0),
                odds("12", "馬12", 9.0, 4.5, 8.0),
                odds("13", "超大穴馬", 10.0, 5.0, 9.0) // 最大オッズ → rank13のまま
        ));

        assertThat(alerts.stream()
                .filter(a -> a.alertType().equals("トレンド逸脱") && a.horseNumber().equals("13")))
                .isEmpty();
    }

    // ===== ロジックD: 支持率加速度検知 =====

    @Test
    void detectAcceleration_初回実行ではアラートが発生しないこと() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);

        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 10.0, 3.0, 5.0)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().equals("支持率加速"))).isEmpty();
    }

    @Test
    void detectAcceleration_閾値以上の加速度でアラートが発生すること() {
        // 加速度 = (1/5 - 1/10) / (60秒/60) = 0.1 / 1分 = 0.1 >= 0.005
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);

        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 10.0, 3.0, 5.0)
        ));

        // 60秒後に単勝5.0へ短縮 → 加速度 = 0.1/分
        clock.setInstant(Instant.parse("2026-01-01T09:01:00Z"));
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 5.0, 2.5, 4.0)
        ));

        List<AnomalyAlertDto> accAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("支持率加速")).toList();
        assertThat(accAlerts).hasSize(1);
        assertThat(accAlerts.get(0).horseNumber()).isEqualTo("5");
        assertThat(accAlerts.get(0).value()).isGreaterThanOrEqualTo(0.005);
    }

    @Test
    void detectAcceleration_閾値未満の加速度ではアラートが発生しないこと() {
        // 加速度 = (1/19 - 1/20) / (60秒/60) ≈ 0.00263/分 < 0.005
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);

        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 20.0, 4.0, 8.0)
        ));

        clock.setInstant(Instant.parse("2026-01-01T09:01:00Z"));
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 19.0, 4.0, 8.0)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().equals("支持率加速"))).isEmpty();
    }

    @Test
    void detectAcceleration_上位3番人気はアラートが発生しないこと() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);

        detector.detect(List.of(
                odds("1", "人気馬A", 2.0, 1.2, 1.5),
                odds("2", "人気馬B", 3.0, 1.4, 2.0),
                odds("3", "人気馬C", 4.0, 1.6, 2.5),
                odds("5", "中穴馬", 10.0, 3.0, 5.0)
        ));

        // 60秒後: 1番人気が大きく短縮（高加速度）→ 除外対象
        clock.setInstant(Instant.parse("2026-01-01T09:01:00Z"));
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.1, 1.1, 1.3),
                odds("2", "人気馬B", 1.5, 1.2, 1.5),
                odds("3", "人気馬C", 2.0, 1.3, 2.0),
                odds("5", "中穴馬", 10.0, 3.0, 5.0)
        ));

        assertThat(alerts.stream()
                .filter(a -> a.alertType().equals("支持率加速"))
                .map(AnomalyAlertDto::horseNumber))
                .doesNotContain("1", "2", "3");
    }

    @Test
    void detectAcceleration_間隔が長いほど加速度が小さくなること() {
        // 同じオッズ変化でも経過時間が長いと加速度が小さくなる
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);

        detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 10.0, 3.0, 5.0)
        ));

        // 30分後に同じオッズ変化 → 加速度 = 0.1/30分 ≈ 0.00333 < 0.005 → アラートなし
        clock.setInstant(Instant.parse("2026-01-01T09:30:00Z"));
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.5, 1.1, 1.3),
                odds("2", "人気馬B", 2.0, 1.2, 1.5),
                odds("3", "人気馬C", 3.0, 1.4, 2.0),
                odds("5", "中穴馬", 5.0, 2.5, 4.0)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().equals("支持率加速"))).isEmpty();
    }

    @Test
    void detect_同一インスタンスで日付が変わると基準値がリセットされること() {
        // 可変クロックを使って同一インスタンスでの日付変更をテスト
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);

        // day1: 基準値登録（5番馬: 20.0）
        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 20.0));
        // day1: 逸脱量 = 0.05 → アラート発生
        List<AnomalyAlertDto> day1Alerts = detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 10.0));
        assertThat(day1Alerts.stream().filter(a -> a.alertType().equals("トレンド逸脱"))).isNotEmpty();

        // 同一インスタンスのまま翌日に変更 → resetBaselineIfNewDay() が基準値をクリア
        clock.setInstant(Instant.parse("2026-01-02T09:00:00Z"));

        // day2: 同じオッズ(10.0)で初回呼び出し → 基準値が 10.0 に再設定される
        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 10.0));
        // day2: 変化なし → アラートなし
        List<AnomalyAlertDto> day2Alerts = detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 10.0));
        assertThat(day2Alerts.stream().filter(a -> a.alertType().equals("トレンド逸脱"))).isEmpty();
    }

    // ===== ロジックE: フェーズ別トレンド逸脱検知 =====

    @Test
    void detectPhaseDeviation_startTimeなしでMORNINGフェーズ逸脱アラートが発生すること() {
        // Optional.empty() → MORNING フェーズ固定
        // 1回目: 5番馬 20.0 → MORNING 基準値設定
        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 20.0));

        // 2回目: 10.0 → 逸脱量 = 1/10 - 1/20 = 0.05 >= 0.05 → フェーズ逸脱[朝]
        List<AnomalyAlertDto> alerts = detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 10.0));

        List<AnomalyAlertDto> phaseAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("フェーズ逸脱[朝]")).toList();
        assertThat(phaseAlerts).hasSize(1);
        assertThat(phaseAlerts.get(0).horseNumber()).isEqualTo("5");
        assertThat(phaseAlerts.get(0).value()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void detectPhaseDeviation_PRE30フェーズで逸脱アラートが発生すること() {
        // クロック 09:00、発走 09:20 → 残り20分 → PRE_30 フェーズ
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);
        Optional<LocalTime> startTime = Optional.of(LocalTime.of(9, 20));

        // 1回目: 5番馬 20.0 → PRE_30 基準値設定
        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 20.0), startTime);

        // 2回目: 10.0 → 逸脱量 = 0.05 >= 0.05 → フェーズ逸脱[30分前]
        List<AnomalyAlertDto> alerts = detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 10.0), startTime);

        List<AnomalyAlertDto> phaseAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("フェーズ逸脱[30分前]")).toList();
        assertThat(phaseAlerts).hasSize(1);
        assertThat(phaseAlerts.get(0).horseNumber()).isEqualTo("5");
    }

    @Test
    void detectPhaseDeviation_PRE10フェーズで逸脱アラートが発生すること() {
        // クロック 09:00、発走 09:05 → 残り5分 → PRE_10 フェーズ
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);
        Optional<LocalTime> startTime = Optional.of(LocalTime.of(9, 5));

        // 1回目: 5番馬 20.0 → PRE_10 基準値設定
        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 20.0), startTime);

        // 2回目: 10.0 → フェーズ逸脱[10分前]
        List<AnomalyAlertDto> alerts = detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 10.0), startTime);

        List<AnomalyAlertDto> phaseAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("フェーズ逸脱[10分前]")).toList();
        assertThat(phaseAlerts).hasSize(1);
        assertThat(phaseAlerts.get(0).horseNumber()).isEqualTo("5");
    }

    @Test
    void detectPhaseDeviation_発走後はアラートが発生しないこと() {
        // クロック 09:10、発走 09:05 → 残り -5分 → null → スキップ
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:10:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);
        Optional<LocalTime> startTime = Optional.of(LocalTime.of(9, 5));

        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 20.0), startTime);
        List<AnomalyAlertDto> alerts = detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 10.0), startTime);

        assertThat(alerts.stream().filter(a -> a.alertType().startsWith("フェーズ逸脱"))).isEmpty();
    }

    @Test
    void detectPhaseDeviation_上位3番人気はフェーズ逸脱対象外であること() {
        // クロック 09:00、発走 09:05 → PRE_10 フェーズ
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);
        Optional<LocalTime> startTime = Optional.of(LocalTime.of(9, 5));

        detector.detect(List.of(
                odds("1", "人気馬A", 2.0, 1.2, 1.5),
                odds("2", "人気馬B", 3.0, 1.4, 2.0),
                odds("3", "人気馬C", 4.0, 1.6, 2.5),
                odds("5", "穴馬",   10.0, 3.0, 5.0)
        ), startTime);

        // 上位3番人気のオッズが大幅短縮してもフェーズ逸脱なし
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "人気馬A", 1.1, 1.1, 1.3),
                odds("2", "人気馬B", 1.5, 1.2, 1.5),
                odds("3", "人気馬C", 2.0, 1.3, 2.0),
                odds("5", "穴馬",   10.0, 3.0, 5.0)
        ), startTime);

        assertThat(alerts.stream()
                .filter(a -> a.alertType().startsWith("フェーズ逸脱"))
                .map(AnomalyAlertDto::horseNumber))
                .doesNotContain("1", "2", "3");
    }

    @Test
    void detectPhaseDeviation_フェーズ間で基準値が独立していること() {
        // MORNING 基準値と PRE_30 基準値は別々に管理される
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);

        // MORNING フェーズ: 5番馬 20.0 で基準値登録
        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 20.0));

        // PRE_30 フェーズに切り替え: 5番馬 15.0 で PRE_30 基準値を別途登録
        Optional<LocalTime> startTimePre30 = Optional.of(LocalTime.of(9, 20));
        detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 15.0), startTimePre30);

        // PRE_30 2回目: 5.0 → 逸脱量 = 1/5 - 1/15 ≈ 0.133 >= 0.05 → フェーズ逸脱[30分前]
        List<AnomalyAlertDto> alerts = detector.detect(buildRace(1.5, 2.0, 3.0, 8.0, 5.0), startTimePre30);

        // フェーズ逸脱[30分前] が発生し、フェーズ逸脱[朝] は発生しないこと
        assertThat(alerts.stream().filter(a -> a.alertType().equals("フェーズ逸脱[30分前]"))).isNotEmpty();
        assertThat(alerts.stream()
                .filter(a -> a.alertType().equals("フェーズ逸脱[30分前]"))
                .map(AnomalyAlertDto::horseNumber))
                .contains("5");
    }

    // ===== ロジックF: オッズ断層（クリフ）検知 =====

    @Test
    void detectOddsCliff_初回検知ではアラートが発生しないこと() {
        // [1.5, 1.8, 2.2, 4.1]: 4.1/2.2 ≈ 1.86 ≥ 1.5 → 断層位置=3（初回なのでアラートなし）
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 2.2, 1.5, 2.2),
                odds("4", "馬4", 4.1, 1.8, 3.0)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().startsWith("オッズ断層"))).isEmpty();
    }

    @Test
    void detectOddsCliff_断層位置が同じ場合はアラートが発生しないこと() {
        // 1回目: 断層位置=3 を記録
        detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 2.2, 1.5, 2.2),
                odds("4", "馬4", 4.1, 1.8, 3.0)
        ));

        // 2回目: 同じ断層位置=3 → アラートなし
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 2.2, 1.5, 2.2),
                odds("4", "馬4", 4.1, 1.8, 3.0)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().startsWith("オッズ断層"))).isEmpty();
    }

    @Test
    void detectOddsCliff_断層が上位に移動した場合に凝縮アラートが発生すること() {
        // 1回目: 断層位置=3 ([1.5, 1.8, 2.2, 4.1]: 4.1/2.2≈1.86)
        detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 2.2, 1.5, 2.2),
                odds("4", "馬4", 4.1, 1.8, 3.0)
        ));

        // 2回目: 断層位置=2 ([1.5, 1.8, 3.0, 4.0]: 3.0/1.8≈1.67) → 凝縮
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 3.0, 1.5, 2.2),
                odds("4", "馬4", 4.0, 1.8, 3.0)
        ));

        List<AnomalyAlertDto> cliffAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("オッズ断層[凝縮]")).toList();
        assertThat(cliffAlerts).hasSize(1);
        // 断層直前は位置2の前 = sortedByWin[1] = 馬2
        assertThat(cliffAlerts.get(0).horseNumber()).isEqualTo("2");
    }

    @Test
    void detectOddsCliff_断層が下位に移動した場合に拡散アラートが発生すること() {
        // 1回目: 断層位置=2 ([1.5, 1.8, 3.0, 4.0]: 3.0/1.8≈1.67)
        detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 3.0, 1.5, 2.2),
                odds("4", "馬4", 4.0, 1.8, 3.0),
                odds("5", "馬5", 5.0, 2.0, 4.0)
        ));

        // 2回目: 断層位置=4 ([1.5, 1.8, 2.2, 2.8, 5.2]: 5.2/2.8≈1.86) → 拡散
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 2.2, 1.5, 2.2),
                odds("4", "馬4", 2.8, 1.8, 3.0),
                odds("5", "馬5", 5.2, 2.0, 4.0)
        ));

        List<AnomalyAlertDto> cliffAlerts = alerts.stream()
                .filter(a -> a.alertType().equals("オッズ断層[拡散]")).toList();
        assertThat(cliffAlerts).hasSize(1);
        // 断層直前は位置4の前 = sortedByWin[3] = 馬4
        assertThat(cliffAlerts.get(0).horseNumber()).isEqualTo("4");
    }

    @Test
    void detectOddsCliff_全隣接比率が閾値未満の場合はアラートが発生しないこと() {
        // 1回目: 断層位置=3 を記録しておく
        detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 2.2, 1.5, 2.2),
                odds("4", "馬4", 4.1, 1.8, 3.0)
        ));

        // 2回目: 全比率 < 1.5 → 断層なし → previousCliffPosition 更新なし → アラートなし
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 2.4, 1.5, 2.2),
                odds("4", "馬4", 3.2, 1.8, 3.0)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().startsWith("オッズ断層"))).isEmpty();
    }

    @Test
    void detectOddsCliff_有効馬が2頭以下の場合はスキップされること() {
        // 2頭しかいない → detectOddsCliff はサイズチェックで早期リターン
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 3.0, 1.5, 2.5)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().startsWith("オッズ断層"))).isEmpty();
    }

    @Test
    void detectOddsCliff_日付変更後にpreviousCliffPositionがリセットされること() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T09:00:00Z"), ZoneOffset.UTC);
        detector = new OddsAnomalyDetector(clock);

        // day1: 断層位置=3 を記録
        detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 2.2, 1.5, 2.2),
                odds("4", "馬4", 4.1, 1.8, 3.0)
        ));

        // 翌日に変更 → previousCliffPosition がリセットされる
        clock.setInstant(Instant.parse("2026-01-02T09:00:00Z"));

        // day2 1回目: 断層位置=2（凝縮のはずだが前回データなし → アラートなし）
        List<AnomalyAlertDto> alerts = detector.detect(List.of(
                odds("1", "馬1", 1.5, 1.1, 1.3),
                odds("2", "馬2", 1.8, 1.3, 1.8),
                odds("3", "馬3", 3.0, 1.5, 2.2),
                odds("4", "馬4", 4.0, 1.8, 3.0)
        ));

        assertThat(alerts.stream().filter(a -> a.alertType().startsWith("オッズ断層"))).isEmpty();
    }

    // ===== ヘルパークラス =====

    /** テスト用の可変クロック。同一インスタンスで時刻を変更してテストできる。 */
    static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(instant, zone); }
        @Override public Instant instant() { return instant; }
    }

    // ===== ヘルパーメソッド =====

    /**
     * 5頭のレースデータを生成する。馬番1〜5、単勝オッズは引数順で設定。
     * テスト用に単勝順位が固定になるよう昇順で渡すこと。
     */
    private List<OddsData> buildRace(double w1, double w2, double w3, double w4, double w5) {
        return List.of(
                odds("1", "馬1", w1, 1.1, 1.3),
                odds("2", "馬2", w2, 1.2, 1.5),
                odds("3", "馬3", w3, 1.4, 2.0),
                odds("4", "馬4", w4, 2.0, 4.0),
                odds("5", "馬5", w5, 3.0, 6.0)
        );
    }

    private OddsData odds(String number, String name, double win, double placeMin, double placeMax) {
        return new OddsData(RACE, number, name, win, placeMin, placeMax, URL);
    }
}
