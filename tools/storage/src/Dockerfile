FROM mesosphere/openjdk-alpine-ammonite:2.0.1-2.12

COPY lib/ /lib/
COPY bin/ /bin/
COPY marathon/ /marathon/

RUN amm-2.12 --predef lib/predef.sc --predef-code 'println("it worked"); sys.exit(0)' | grep "it worked"

ENTRYPOINT ["/bin/storage-tool.sh"]
