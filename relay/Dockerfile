# 2.6.4 — image Cloud Run (free tier 0đ)
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod main.go ./
RUN go build -o /bibot-relay .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=build /bibot-relay /bibot-relay
ENV PORT=8080
EXPOSE 8080
CMD ["/bibot-relay"]
