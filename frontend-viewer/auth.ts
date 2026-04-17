import NextAuth from 'next-auth';
import Google from 'next-auth/providers/google';

export const { handlers, auth, signIn, signOut } = NextAuth({
  pages: {
    signIn: '/login',
    error: '/login',
  },
  providers: [
    Google({
      clientId: process.env.AUTH_GOOGLE_ID!,
      clientSecret: process.env.AUTH_GOOGLE_SECRET!,
      authorization: {
        params: {
          prompt: 'select_account',
        },
      },
    }),
  ],
  callbacks: {
    signIn({ profile }) {
      const allowedEmail = process.env.ALLOWED_EMAIL;
      console.log('[signIn] profile.email:', profile?.email);
      console.log('[signIn] ALLOWED_EMAIL:', allowedEmail);
      if (!allowedEmail) {
        console.warn('ALLOWED_EMAIL が設定されていません');
        return true; // デバッグ: 一時的に全許可
      }
      return true; // デバッグ: 一時的に全許可
    },
  },
});
