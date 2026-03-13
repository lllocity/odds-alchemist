package com.oddsalchemist.backend.service;

import com.oddsalchemist.backend.config.SlackProperties;
import com.oddsalchemist.backend.dto.AnomalyAlertDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SlackNotifyClientTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/test/url";
    private static final String TARGET_URL = "https://example.com/race/1";

    private RestClient restClient;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(restClient.post().uri(anyString()).contentType(any()).body(any()).retrieve().toBodilessEntity())
                .thenReturn(ResponseEntity.ok().build());
        // RETURNS_DEEP_STUBS の when() 自体がインタラクションを記録するためリセット
        clearInvocations(restClient);

        fixedClock = Clock.fixed(Instant.parse("2026-03-14T01:00:00Z"), ZoneId.of("Asia/Tokyo"));
    }

    private SlackNotifyClient client(boolean enabled, String webhookUrl) {
        return new SlackNotifyClient(new SlackProperties(webhookUrl, enabled), restClient, fixedClock);
    }

    private AnomalyAlertDto alert(String horseNumber, String alertType) {
        return new AnomalyAlertDto("第1回テストレース", horseNumber, "テスト馬" + horseNumber,
                alertType, 0.05, "2026-03-14T10:00:00");
    }

    @Test
    void notify_enabledがfalseの場合は送信しないこと() {
        SlackNotifyClient c = client(false, WEBHOOK_URL);
        c.notify(List.of(alert("5", "支持率急増")), TARGET_URL);
        verify(restClient, never()).post();
    }

    @Test
    void notify_アラートが0件の場合は送信しないこと() {
        SlackNotifyClient c = client(true, WEBHOOK_URL);
        c.notify(List.of(), TARGET_URL);
        verify(restClient, never()).post();
    }

    @Test
    void notify_未通知アラートがSlackに送信されること() {
        SlackNotifyClient c = client(true, WEBHOOK_URL);
        c.notify(List.of(alert("5", "支持率急増")), TARGET_URL);
        verify(restClient, times(1)).post();
    }

    @Test
    void notify_送信済みアラートは重複送信されないこと() {
        SlackNotifyClient c = client(true, WEBHOOK_URL);
        AnomalyAlertDto a = alert("5", "支持率急増");
        c.notify(List.of(a), TARGET_URL);
        c.notify(List.of(a), TARGET_URL);
        // 2回目は送信済みキャッシュにあるため POST しない
        verify(restClient, times(1)).post();
    }

    @Test
    void notify_複数アラートが1通にまとめられること() {
        SlackNotifyClient c = client(true, WEBHOOK_URL);
        c.notify(List.of(
                alert("5", "支持率急増"),
                alert("8", "順位乖離"),
                alert("3", "トレンド逸脱")
        ), TARGET_URL);
        // 3件でも POST は1回だけ
        verify(restClient, times(1)).post();
    }

    @Test
    void notify_日付変更時に送信済みキャッシュがリセットされること() {
        // 可変 Clock でテスト内で日付を進める
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-03-14T01:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("Asia/Tokyo"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        SlackNotifyClient c = new SlackNotifyClient(new SlackProperties(WEBHOOK_URL, true), restClient, mutableClock);
        AnomalyAlertDto a = alert("5", "支持率急増"); // detectedAt = "2026-03-14T10:00:00"

        // 1日目: 送信
        c.notify(List.of(a), TARGET_URL);
        verify(restClient, times(1)).post();

        // 同日: 送信済みキャッシュにあるため再送しない
        c.notify(List.of(a), TARGET_URL);
        verify(restClient, times(1)).post();

        // 翌日に進める → sentKeys がリセットされる
        now.set(Instant.parse("2026-03-15T01:00:00Z"));
        c.notify(List.of(a), TARGET_URL);
        verify(restClient, times(2)).post();
    }

    @Test
    void notify_通信失敗時でもメソッドが例外を外に投げないこと() {
        // RETURNS_DEEP_STUBS チェーンに thenThrow を設定しても到達しない場合があるため、
        // post() レベルで確実に例外を発生させる
        doThrow(new RuntimeException("接続エラー")).when(restClient).post();
        SlackNotifyClient c = client(true, WEBHOOK_URL);
        assertThatCode(() -> c.notify(List.of(alert("5", "支持率急増")), TARGET_URL))
                .doesNotThrowAnyException();
    }

    @Test
    void notify_通信失敗時は送信済みキャッシュに追加しないこと() {
        // post() レベルで例外を発生させる（clearInvocations後なので doThrow は invocation を記録しない）
        doThrow(new RuntimeException("接続エラー")).when(restClient).post();
        SlackNotifyClient c = client(true, WEBHOOK_URL);
        c.notify(List.of(alert("5", "支持率急増")), TARGET_URL); // 失敗 → sentKeys に追加されない
        c.notify(List.of(alert("5", "支持率急増")), TARGET_URL); // 再試行（sentKeys が空のため）

        // sentKeys に追加されていないため 2 回とも post() が呼ばれた
        // もし失敗時に sentKeys が更新されると 2 回目がスキップされ times(1) になる
        verify(restClient, times(2)).post();
    }

    @Test
    void notify_webhookUrlが未設定の場合は送信しないこと() {
        SlackNotifyClient c = client(true, "");
        c.notify(List.of(alert("5", "支持率急増")), TARGET_URL);
        verify(restClient, never()).post();
    }
}
