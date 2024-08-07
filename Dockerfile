# syntax=docker/dockerfile:1
ARG BUILD_IMAGE=gradle:7.4-jdk17-alpine
ARG RUN_IMAGE=eclipse-temurin:11-alpine@sha256:87219038abbc0e3a14868694b3567a39a4a6779de66ab0a23ff2c3812a8f1181
ARG CUSTOM_CRT_URL=http://pki.jlab.org/JLabCA.crt

################## Stage 0
FROM ${BUILD_IMAGE} as builder
ARG CUSTOM_CRT_URL
USER root
WORKDIR /
RUN if [ -z "${CUSTOM_CRT_URL}" ] ; then echo "No custom cert needed"; else \
       wget -O /usr/local/share/ca-certificates/customcert.crt $CUSTOM_CRT_URL \
       && update-ca-certificates \
       && keytool -import -alias custom -file /usr/local/share/ca-certificates/customcert.crt -cacerts -storepass changeit -noprompt \
       && export OPTIONAL_CERT_ARG=--cert=/etc/ssl/certs/ca-certificates.crt \
    ; fi
COPY . /app
RUN cd /app && gradle build -x test --no-watch-fs $OPTIONAL_CERT_ARG

################## Stage 1
FROM ${RUN_IMAGE} as runner
ARG CUSTOM_CRT_URL
ARG RUN_USER=guest
ARG APP_HOME=/opt/jaws-effective-processor
USER root
COPY --from=builder /app/docker-entrypoint.sh /docker-entrypoint.sh
COPY --from=builder /app/build/install /opt
RUN if [ -z "${CUSTOM_CRT_URL}" ] ; then echo "No custom cert needed"; else \
       mkdir -p /usr/local/share/ca-certificates \
       && wget -O /usr/local/share/ca-certificates/customcert.crt $CUSTOM_CRT_URL \
       && cat /usr/local/share/ca-certificates/customcert.crt >> /etc/ssl/certs/ca-certificates.crt \
       && keytool -import -alias custom -file /usr/local/share/ca-certificates/customcert.crt -cacerts -storepass changeit -noprompt \
    ; fi \
    && apk add --no-cache --update curl libstdc++ \
    && chown -R ${RUN_USER}:0 ${APP_HOME} \
    && chmod -R g+rw ${APP_HOME}
USER ${RUN_USER}
ENTRYPOINT ["/docker-entrypoint.sh"]
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --start-interval=5s --retries=5 CMD test $(ps | grep jaws | wc -l) -gt 1
