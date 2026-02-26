'use client';

import { useState } from 'react';

export default function Home() {
  const [url, setUrl] = useState('');
  const [status, setStatus] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const handleStartMonitoring = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!url) return;

    setIsLoading(true);
    setStatus('バックエンドへリクエストを送信中...');

    try {
      // ※ここはStep 12で実際のバックエンドAPIを呼び出すように書き換えます
      // 今回はUIの動作確認のためのダミー遅延処理です
      await new Promise((resolve) => setTimeout(resolve, 1500));
      setStatus(`「${url}」の監視を開始しました。`);
    } catch (error) {
      setStatus('エラーが発生しました。バックエンドの接続を確認してください。');
    } finally {
      setIsLoading(false);
      setUrl('');
    }
  };

  return (
    <main className="min-h-screen bg-gray-50 flex flex-col items-center justify-center p-6">
      <div className="max-w-md w-full bg-white rounded-xl shadow-md p-8">
        <h1 className="text-2xl font-bold text-gray-800 text-center mb-6">
          Odds Alchemist
        </h1>
        
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
              placeholder="https://race.netkeiba.com/race/shutuba.html?race_id=..."
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
          <div className="mt-6 p-4 rounded-md bg-blue-50 text-blue-800 text-sm">
            {status}
          </div>
        )}
      </div>
    </main>
  );
}