'use client';

import { signIn, signOut } from 'next-auth/react';
import { useSearchParams } from 'next/navigation';
import { Suspense } from 'react';

function LoginContent() {
  const searchParams = useSearchParams();
  const error = searchParams.get('error');

  if (error) {
    return (
      <div className="text-center">
        <h1 className="text-xl font-semibold text-gray-800 mb-4">Odds Alchemist</h1>
        <p className="text-sm text-red-600 mb-6">
          このアカウントはアクセスが許可されていません。
        </p>
        <button
          onClick={() => signOut({ callbackUrl: '/login' })}
          className="bg-white border border-gray-300 rounded-lg px-6 py-3 text-sm font-medium text-gray-700 hover:bg-gray-50 shadow-sm"
        >
          サインアウトして別のアカウントで試す
        </button>
      </div>
    );
  }

  return (
    <div className="text-center">
      <h1 className="text-xl font-semibold text-gray-800 mb-8">Odds Alchemist</h1>
      <button
        onClick={() => signIn('google', { callbackUrl: '/' })}
        className="bg-white border border-gray-300 rounded-lg px-6 py-3 text-sm font-medium text-gray-700 hover:bg-gray-50 shadow-sm"
      >
        Google でサインイン
      </button>
    </div>
  );
}

export default function LoginPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50">
      <Suspense>
        <LoginContent />
      </Suspense>
    </div>
  );
}
