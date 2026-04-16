import type { NextAuthConfig } from 'next-auth';

/** Edge Runtime（proxy）でも使える設定。Google プロバイダーは含めない */
export const authConfig: NextAuthConfig = {
  pages: {
    signIn: '/login',
    error: '/login',
  },
  callbacks: {
    authorized({ auth }) {
      const allowedEmail = process.env.ALLOWED_EMAIL;
      return !!auth?.user?.email && auth.user.email === allowedEmail;
    },
  },
  providers: [],
};
