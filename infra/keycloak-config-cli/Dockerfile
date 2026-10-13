FROM maven:3.9-eclipse-temurin-26-alpine@sha256:4c85d516dd8c7e50d49a403d604e565e0dbb09a790186fe3245725d313ae7cb9 AS builder
WORKDIR /source
RUN apk add --no-cache python3
# v6.5.1, pinned to its commit and archive checksum rather than a moving tag.
ADD --checksum=sha256:9bd4eaa2c3b1ddad8b071b9c4cfcd30c81205954a6ed63672c34dc3c4801d0ce https://api.github.com/repos/adorsys/keycloak-config-cli/tarball/90c97fc0f8968286400be7bcb27f8165581003f2 /tmp/source.tar.gz
RUN tar -xzf /tmp/source.tar.gz --strip-components=1 && rm /tmp/source.tar.gz
COPY patch_dependencies.py /tmp/patch_dependencies.py
RUN python3 /tmp/patch_dependencies.py pom.xml \
    && mvn -B -ntp package -Dlicense.skip=true -Dlicense.skipCheckFileHeader=true -Dmaven.test.skip=true -Dlicense.skipCheckLicense \
        -Dcheckstyle.skip -Dmaven.site.skip=true -Dmaven.javadoc.skip=true -Dmaven.gitcommitid.skip=true

FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
COPY --from=builder /source/target/keycloak-config-cli.jar /app/keycloak-config-cli.jar
ENV KEYCLOAK_SSL_VERIFY=true IMPORT_FILES_LOCATIONS=file:/config/*
USER 65534:65534
ENTRYPOINT ["java", "-jar", "/app/keycloak-config-cli.jar"]
