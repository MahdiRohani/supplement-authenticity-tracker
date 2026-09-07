FROM node:20-bookworm-slim

WORKDIR /app

COPY package.json package-lock.json ./
COPY prisma ./prisma
RUN npm ci && npx prisma generate

COPY . .
RUN npm run build

EXPOSE 3000
CMD ["sh", "-c", "npx prisma db push && node dist/main.js"]
