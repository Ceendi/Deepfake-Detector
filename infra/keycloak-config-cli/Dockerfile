FROM maven:3.9.15-eclipse-temurin-21-alpine@sha256:856fe78872c59d3b584a64d07693d5433cbf44e35b5ba1f2c911147f39f54266 AS builder
WORKDIR /source
RUN apk add --no-cache python3
# v6.5.1, pinned to its commit and archive checksum rather than a moving tag.
ADD --checksum=sha256:9bd4eaa2c3b1ddad8b071b9c4cfcd30c81205954a6ed63672c34dc3c4801d0ce https://api.github.com/repos/adorsys/keycloak-config-cli/tarball/90c97fc0f8968286400be7bcb27f8165581003f2 /tmp/source.tar.gz
RUN tar -xzf /tmp/source.tar.gz --strip-components=1 && rm /tmp/source.tar.gz
COPY patch_dependencies.py /tmp/patch_dependencies.py
RUN python3 /tmp/patch_dependencies.py pom.xml \
    && mvn -B -ntp package -Dlicense.skip=true -Dlicense.skipCheckFileHeader=true -Dmaven.test.skip=true -Dlicense.skipCheckLicense \
        -Dcheckstyle.skip -Dmaven.site.skip=true -Dmaven.javadoc.skip=true -Dmaven.gitcommitid.skip=true

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
COPY --from=builder /source/target/keycloak-config-cli.jar /app/keycloak-config-cli.jar
ENV KEYCLOAK_SSL_VERIFY=true IMPORT_FILES_LOCATIONS=file:/config/*
USER 65534:65534
ENTRYPOINT ["java", "-jar", "/app/keycloak-config-cli.jar"]
