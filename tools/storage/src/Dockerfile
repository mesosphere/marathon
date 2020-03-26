FROM mesosphere/openjdk-alpine-ammonite:2.0.4-2.13

COPY lib/ /lib/
COPY bin/ /bin/
COPY marathon/ /marathon/

RUN amm-2.13 --predef lib/predef.sc --predef-code 'println("it worked"); sys.exit(0)' | grep "it worked"

ENTRYPOINT ["/bin/storage-tool.sh"]
