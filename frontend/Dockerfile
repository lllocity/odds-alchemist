# Stage 1: ビルド
FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
ARG NEXT_PUBLIC_SPREADSHEET_ID
ENV NEXT_PUBLIC_SPREADSHEET_ID=$NEXT_PUBLIC_SPREADSHEET_ID
RUN npm run build

# Stage 2: 実行（standaloneのみコピー）
FROM node:22-alpine
WORKDIR /app
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public
ENV NODE_ENV=production
EXPOSE 3000
CMD ["node", "server.js"]
