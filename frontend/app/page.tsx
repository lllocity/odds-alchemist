'use client';

import { useState, useEffect, useCallback } from 'react';
import AlertList from '@/app/components/AlertList';
import OddsTrendChart from '@/app/components/OddsTrendChart';
import { AnomalyAlert } from '@/app/types/oddsAlert';
import { TargetUrlInfo } from '@/app/types/targetUrl';

/** アラートをポーリングする間隔（ミリ秒）: スクレイピング最短間隔1分に対し10秒で追従 */
const POLLING_INTERVAL_MS = 10_000;

export default function Home() {
  const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080';

  // ===== 即時取得フォームの状態 =====
  const [url, setUrl] = useState('');
  const [status, setStatus] = useState<{ type: 'info' | 'success' | 'error'; message: string } | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  // ===== アラート一覧の状態 =====
  const [alerts, setAlerts] = useState<AnomalyAlert[]>([]);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  // ===== スケジュール監視URL管理の状態 =====
  const [targetUrls, setTargetUrls] = useState<TargetUrlInfo[]>([]);
  const [targetUrlInput, setTargetUrlInput] = useState('');
  const [isRegisteringUrl, setIsRegisteringUrl] = useState(false);
  const [urlActionStatus, setUrlActionStatus] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  /** アラート一覧をバックエンドから取得する */
  const fetchAlerts = useCallback(async () => {
    try {
      const response = await fetch(`${apiBaseUrl}/api/odds/alerts`);
      if (!response.ok) {
        console.warn(`アラート取得に失敗しました: ${response.status}`);
        return;
      }
      const data: AnomalyAlert[] = await response.json();
      setAlerts([...data].reverse());
      setLastUpdated(new Date());
    } catch (error) {
      console.warn('アラート取得中にエラーが発生しました', error);
    }
  }, [apiBaseUrl]);

  /** 登録済みスケジュール監視URLを実行時刻情報付きで取得する */
  const fetchTargetUrls = useCallback(async () => {
    try {
      const response = await fetch(`${apiBaseUrl}/api/odds/targets`);
      if (!response.ok) {
        console.warn(`監視対象URL取得に失敗: ${response.status}`);
        return;
      }
      const data: TargetUrlInfo[] = await response.json();
      setTargetUrls(data);
    } catch (error) {
      console.warn('監視対象URL取得中にエラー', error);
    }
  }, [apiBaseUrl]);

  /** 初回マウント時に即時取得し、以降は一定間隔でポーリング */
  useEffect(() => {
    fetchAlerts();
    const timer = setInterval(fetchAlerts, POLLING_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [fetchAlerts]);

  /** 初回マウント時に登録済みURLを取得し、以降はポーリングで実行時刻を更新 */
  useEffect(() => {
    fetchTargetUrls();
    const timer = setInterval(fetchTargetUrls, POLLING_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [fetchTargetUrls]);

  /** 即時取得（手動スクレイピング） */
  const handleStartMonitoring = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!url) return;

    setIsLoading(true);
    setStatus({ type: 'info', message: 'バックエンドへリクエストを送信中...' });

    try {
      const response = await fetch(`${apiBaseUrl}/api/odds/fetch`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url }),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || '通信エラーが発生しました');
      }

      setStatus({ type: 'success', message: `成功: ${data.message}` });
      setUrl('');
      fetchAlerts();
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '予期せぬエラーが発生しました';
      setStatus({ type: 'error', message: `エラー: ${errorMessage}` });
    } finally {
      setIsLoading(false);
    }
  };

  /** スケジュール監視URLを登録する */
  const handleRegisterUrl = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!targetUrlInput) return;

    setIsRegisteringUrl(true);
    setUrlActionStatus(null);

    try {
      const response = await fetch(`${apiBaseUrl}/api/odds/targets`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: targetUrlInput }),
      });
      const data = await response.json();
      if (!response.ok) {
        throw new Error(data.message || '登録エラー');
      }
      setTargetUrlInput('');
      setUrlActionStatus({ type: 'success', message: 'URLを登録しました' });
      await fetchTargetUrls();
    } catch (error) {
      const msg = error instanceof Error ? error.message : '予期せぬエラー';
      setUrlActionStatus({ type: 'error', message: msg });
    } finally {
      setIsRegisteringUrl(false);
    }
  };

  /** スケジュール監視URLを削除する */
  const handleRemoveUrl = async (targetUrl: string) => {
    setUrlActionStatus(null);
    try {
      const response = await fetch(`${apiBaseUrl}/api/odds/targets`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: targetUrl }),
      });
      const data = await response.json();
      if (!response.ok) {
        setUrlActionStatus({ type: 'error', message: data.message || 'URL削除エラー' });
        return;
      }
      setUrlActionStatus({ type: 'success', message: 'URLを削除しました' });
      await fetchTargetUrls();
    } catch (error) {
      const msg = error instanceof Error ? error.message : '予期せぬエラー';
      setUrlActionStatus({ type: 'error', message: msg });
    }
  };

  return (
    <main className="min-h-screen bg-gray-50 p-6 pt-12">
      <div className="max-w-6xl mx-auto">

        <h1 className="text-2xl font-bold text-gray-800 text-center mb-6">
          Odds Alchemist
        </h1>

        <div className="grid grid-cols-2 gap-6 items-start">

          {/* 左カラム: 操作系パネル */}
          <div className="space-y-6">

            {/* スケジュール監視対象URL管理パネル */}
            <div className="bg-white rounded-xl shadow-md p-6">
              <h2 className="text-base font-semibold text-gray-800 mb-4">
                スケジュール監視対象URL
              </h2>

              <form onSubmit={handleRegisterUrl} className="flex gap-2 mb-3">
                <input
                  type="url"
                  value={targetUrlInput}
                  onChange={(e) => setTargetUrlInput(e.target.value)}
                  placeholder="https://sports.yahoo.co.jp/keiba/race/odds/tfw/..."
                  className="flex-1 px-3 py-2 text-sm border border-gray-300 text-gray-900 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
                  required
                />
                <button
                  type="submit"
                  disabled={isRegisteringUrl || !targetUrlInput}
                  className={`shrink-0 px-4 py-2 rounded-md text-white text-sm font-medium transition-colors
                    ${isRegisteringUrl || !targetUrlInput
                      ? 'bg-gray-400 cursor-not-allowed'
                      : 'bg-green-600 hover:bg-green-700'
                    }`}
                >
                  {isRegisteringUrl ? '登録中...' : '登録'}
                </button>
              </form>

              {urlActionStatus && (
                <div className={`mb-3 p-2 rounded text-xs ${
                  urlActionStatus.type === 'error' ? 'bg-red-50 text-red-800' : 'bg-green-50 text-green-800'
                }`}>
                  {urlActionStatus.message}
                </div>
              )}

              {targetUrls.length === 0 ? (
                <p className="text-sm text-gray-400 text-center py-3">
                  登録済みのURLはありません
                </p>
              ) : (
                <ul className="space-y-2">
                  {targetUrls.map((info) => (
                    <li
                      key={info.url}
                      className="flex items-start gap-2 text-xs text-gray-600 bg-gray-50 rounded px-3 py-2"
                    >
                      <div className="flex-1 min-w-0">
                        <span className="block truncate text-gray-800">{info.url}</span>
                        <div className="mt-1 flex gap-4 text-gray-400">
                          <span>最終実行: {info.lastExecutionTime ?? '未実行'}</span>
                          <span>次回予定: {info.nextScheduledTime ?? '未設定'}</span>
                        </div>
                      </div>
                      <button
                        onClick={() => handleRemoveUrl(info.url)}
                        className="shrink-0 text-red-500 hover:text-red-700 font-medium mt-0.5"
                      >
                        削除
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {/* 即時取得パネル（折りたたみ） */}
            <details className="bg-white rounded-xl shadow-md">
              <summary className="px-6 py-4 cursor-pointer text-sm font-medium text-gray-500 select-none list-none flex items-center gap-1">
                <span className="text-gray-400">▶</span>
                即時取得（手動スクレイピング）
              </summary>
              <div className="px-8 pb-8 pt-2 space-y-4">
                <form onSubmit={handleStartMonitoring} className="space-y-4">
                  <div>
                    <label htmlFor="race-url" className="block text-sm font-medium text-gray-700 mb-1">
                      対象レースのURL
                    </label>
                    <input
                      type="url"
                      id="race-url"
                      value={url}
                      onChange={(e) => setUrl(e.target.value)}
                      placeholder="https://sports.yahoo.co.jp/keiba/race/odds/tfw/..."
                      className="w-full px-4 py-2 border border-gray-300 text-gray-900 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-colors"
                      required
                    />
                  </div>
                  <button
                    type="submit"
                    disabled={isLoading || !url}
                    className={`w-full py-2 px-4 rounded-md text-white font-medium transition-colors
                      ${isLoading || !url
                        ? 'bg-gray-400 cursor-not-allowed'
                        : 'bg-blue-600 hover:bg-blue-700 active:bg-blue-800'
                      }`}
                  >
                    {isLoading ? '処理中...' : '取得する'}
                  </button>
                </form>

                {status && (
                  <div className={`p-4 rounded-md text-sm ${
                    status.type === 'error'
                      ? 'bg-red-50 text-red-800'
                      : status.type === 'success'
                        ? 'bg-green-50 text-green-800'
                        : 'bg-blue-50 text-blue-800'
                  }`}>
                    {status.message}
                  </div>
                )}
              </div>
            </details>

            {/* オッズ推移グラフパネル */}
            <div className="bg-white rounded-xl shadow-md p-6">
              <OddsTrendChart />
            </div>

          </div>

          {/* 右カラム: 検知アラート */}
          <div className="bg-white rounded-xl shadow-md p-6 sticky top-6">
            <AlertList alerts={alerts} lastUpdated={lastUpdated} />
          </div>

        </div>
      </div>
    </main>
  );
}
