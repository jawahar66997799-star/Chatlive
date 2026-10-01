FROM golang:1.26.6-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN GOTOOLCHAIN=local go mod download
COPY . .
RUN GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/relay .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates && adduser -D -H app
USER app
COPY --from=build /out/relay /relay
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/relay"]
