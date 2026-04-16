import type { NextAuthConfig } from 'next-auth';

/** Edge Runtime（proxy）でも使える設定。Google プロバイダーは含めない */
export const authConfig: NextAuthConfig = {
  pages: {
    signIn: '/login',
    error: '/login',
  },
  providers: [],
};
