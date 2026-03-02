package com.oddsalchemist.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 監視対象URLをインメモリで管理するスレッドセーフなストア。
 * URLはAPIを通じた動的な登録・削除のみで管理する。
 */
@Service
public class TargetUrlStore {

    private static final Logger logger = LoggerFactory.getLogger(TargetUrlStore.class);

    private final CopyOnWriteArrayList<String> urls = new CopyOnWriteArrayList<>();

    /**
     * 登録済みURLの一覧を返します（変更不可）。
     */
    public List<String> getUrls() {
        return Collections.unmodifiableList(urls);
    }

    /**
     * URLを監視対象に追加します。
     * @return 追加に成功した場合 true、すでに登録済みの場合 false
     */
    public boolean addUrl(String url) {
        if (urls.contains(url)) {
            logger.warn("URLはすでに登録済みです: {}", url);
            return false;
        }
        urls.add(url);
        logger.info("監視対象URLを追加: {}", url);
        return true;
    }

    /**
     * URLを監視対象から削除します。
     * @return 削除に成功した場合 true、対象が存在しない場合 false
     */
    public boolean removeUrl(String url) {
        boolean removed = urls.remove(url);
        if (removed) {
            logger.info("監視対象URLを削除: {}", url);
        }
        return removed;
    }
}
