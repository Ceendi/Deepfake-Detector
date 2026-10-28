FROM postgres:18.6-alpine3.24@sha256:10a5c69a83035ab5239f5620d708520e8b28f5aa1f2b20143e6024532edadfb5

# Takes Alpine security fixes released after the base image was built.
RUN apk upgrade --no-cache

# The upstream gosu binary embeds an outdated Go standard library. The official
# entrypoint only needs its user-switch-and-exec behavior, provided by su-exec.
RUN apk add --no-cache su-exec \
    && apk upgrade --no-cache su-exec \
    && su_exec_version="$(apk info -v --installed su-exec)" \
    && case "$(apk version -t "${su_exec_version#su-exec-}" 0.3-r0)" in '='|'>') ;; *) exit 1 ;; esac \
    && rm /usr/local/bin/gosu \
    && sed -i 's/exec gosu /exec su-exec /g' /usr/local/bin/docker-entrypoint.sh \
    && mkdir -p /var/lib/postgresql/data \
    && chown postgres:postgres /var/lib/postgresql/data

USER postgres
