FROM openjdk:8-jdk-alpine

# install curl
RUN apk update && \
  apk add bash ncurses curl ca-certificates && \
  rm -rf /var/cache/apk/*


# install ammonite
RUN curl -L -o /usr/local/bin/amm-2.13 https://github.com/lihaoyi/Ammonite/releases/download/2.0.4/2.13-2.0.4 && \
  chmod +x /usr/local/bin/amm-2.13 && \
  /usr/local/bin/amm-2.13 --predef-code "sys.exit(0)"

ENTRYPOINT ["/usr/local/bin/amm-2.13"]
