FROM node:22-alpine
WORKDIR /app
COPY --chown=node:node server/notion.mjs server/server.mjs ./
USER node
ENV NODE_ENV=production
EXPOSE 10000
CMD ["node", "server.mjs"]
