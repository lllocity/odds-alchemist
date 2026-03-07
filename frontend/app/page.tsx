'use client';

import { useState, useEffect, useCallback } from 'react';
import AlertList from '@/app/components/AlertList';
import { AnomalyAlert } from '@/app/types/oddsAlert';

/** アラートをポーリングする間隔（ミリ秒） */
const POLLING_INTERVAL_MS = 30_000;

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
  const [targetUrls, setTargetUrls] = useState<string[]>([]);
  const [targetUrlInput, setTargetUrlInput] = useState('');
  const [isRegisteringUrl, setIsRegisteringUrl] = useState(false);
  const [registerStatus, setRegisterStatus] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

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

  /** 登録済みスケジュール監視URLを取得する */
  const fetchTargetUrls = useCallback(async () => {
    try {
      const response = await fetch(`${apiBaseUrl}/api/odds/targets`);
      if (!response.ok) {
        console.warn(`監視対象URL取得に失敗: ${response.status}`);
        return;
      }
      const data: string[] = await response.json();
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

  /** 初回マウント時に登録済みURLを取得 */
  useEffect(() => {
    fetchTargetUrls();
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
      // 監視開始後すぐにアラートを更新する
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
    setRegisterStatus(null);

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
      setTargetUrls(data.urls);
      setRegisterStatus({ type: 'success', message: 'URLを登録しました' });
    } catch (error) {
      const msg = error instanceof Error ? error.message : '予期せぬエラー';
      setRegisterStatus({ type: 'error', message: msg });
    } finally {
      setIsRegisteringUrl(false);
    }
  };

  /** スケジュール監視URLを削除する */
  const handleRemoveUrl = async (targetUrl: string) => {
    try {
      const response = await fetch(`${apiBaseUrl}/api/odds/targets`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: targetUrl }),
      });
      const data = await response.json();
      if (!response.ok) {
        console.warn('URL削除エラー:', data.message);
        return;
      }
      setTargetUrls(data.urls);
    } catch (error) {
      console.warn('URL削除中にエラー', error);
    }
  };

  return (
    <main className="min-h-screen bg-gray-50 flex flex-col items-center p-6 pt-12">
      <div className="max-w-xl w-full space-y-6">

        {/* 即時取得パネル */}
        <div className="bg-white rounded-xl shadow-md p-8">
          <h1 className="text-2xl font-bold text-gray-800 text-center mb-6">
            Odds Alchemist
          </h1>

          <form onSubmit={handleStartMonitoring} className="space-y-4">
            <div>
              <label htmlFor="race-url" className="block text-sm font-medium text-gray-700 mb-1">
                対象レースのURL（即時取得）
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
              {isLoading ? '処理中...' : '監視を開始する'}
            </button>
          </form>

          {status && (
            <div className={`mt-6 p-4 rounded-md text-sm ${
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

          {registerStatus && (
            <div className={`mb-3 p-2 rounded text-xs ${
              registerStatus.type === 'error' ? 'bg-red-50 text-red-800' : 'bg-green-50 text-green-800'
            }`}>
              {registerStatus.message}
            </div>
          )}

          {targetUrls.length === 0 ? (
            <p className="text-sm text-gray-400 text-center py-3">
              登録済みのURLはありません
            </p>
          ) : (
            <ul className="space-y-1.5">
              {targetUrls.map((u) => (
                <li
                  key={u}
                  className="flex items-center gap-2 text-xs text-gray-600 bg-gray-50 rounded px-3 py-2"
                >
                  <span className="flex-1 truncate">{u}</span>
                  <button
                    onClick={() => handleRemoveUrl(u)}
                    className="shrink-0 text-red-500 hover:text-red-700 font-medium"
                  >
                    削除
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* アラート一覧パネル */}
        <div className="bg-white rounded-xl shadow-md p-6">
          <AlertList alerts={alerts} lastUpdated={lastUpdated} />
        </div>
      </div>
    </main>
  );
}
