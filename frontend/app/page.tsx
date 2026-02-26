'use client';

import { useState } from 'react';

export default function Home() {
  const [url, setUrl] = useState('');
  const [status, setStatus] = useState<{ type: 'info' | 'success' | 'error'; message: string } | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const handleStartMonitoring = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!url) return;

    setIsLoading(true);
    setStatus({ type: 'info', message: 'バックエンドへリクエストを送信中...' });

    try {
      const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080';
      // Spring BootのAPIへPOSTリクエストを送信
      const response = await fetch(`${apiBaseUrl}/api/odds/fetch`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ url }),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.message || '通信エラーが発生しました');
      }

      setStatus({ type: 'success', message: `成功: ${data.message}` });
      setUrl('');
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '予期せぬエラーが発生しました';
      setStatus({ type: 'error', message: `エラー: ${errorMessage}` });
    } finally {
      setIsLoading(false);
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
    </main>
  );
}