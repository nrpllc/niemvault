# NIEM Integration Platform — one image, both delivery modes (spec §6).
#
# The same image is what the vendor cloud pushes in managed mode and what an agency loads from a
# tarball in an air-gapped one. There is no on-prem build and no on-prem-only code path, which is
# what §6 requires and the reason this is a single Dockerfile rather than two.
#
# It contains the platform and nothing else. Domain modules — mappings, contracts, fixtures — are
# versioned content on their own release cycle (§7), so they are mounted at run time rather than
# baked in. Baking them would mean a platform patch forced a content release and vice versa, which
# is precisely the coupling §7 exists to prevent.

# --- build ------------------------------------------------------------------
#
# The build runs inside the image so what ships is reproducible from source rather than from
# whatever happened to be in a developer's build directory.
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /src

# The wrapper and version catalogue first, so dependency resolution is cached independently of
# source changes. `gradlew` verifies the distribution checksum, so this step is not trusting the
# network any further than the checked-in hash allows.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic

# Then the sources.
COPY core core
COPY runtime runtime
COPY storage storage
COPY connectors connectors
COPY identity identity
COPY projections projections
COPY modules modules
COPY control-plane control-plane
COPY tools tools

# --no-daemon: a Gradle daemon in a build layer is a process nothing will ever talk to again.
RUN ./gradlew --no-daemon :tools:cli:installDist

# --- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# Runs as a non-root user. Nothing the platform does needs root, and a pipeline that reads agency
# data is not a place to find out otherwise.
RUN groupadd --system --gid 10001 niem \
 && useradd --system --uid 10001 --gid niem --home-dir /var/lib/niem --create-home niem

COPY --from=build --chown=root:root /src/tools/cli/build/install/niem /opt/niem

# Data directories, owned by the runtime user. Mount volumes over these; bronze in particular is
# append-only and is the record of what a source actually sent (§4.4).
RUN mkdir -p /var/lib/niem/bronze /var/lib/niem/quarantine /srv/modules /srv/drop \
 && chown -R niem:niem /var/lib/niem

ENV PATH="/opt/niem/bin:${PATH}"

# The --add-opens embedded Flink needs on Java 21 are baked into the start script by the build
# (niem.java-conventions), so they cannot drift from the flags the tests run with. Anything set here
# is appended to them, not instead of them.
ENV JAVA_OPTS=""

USER niem
WORKDIR /var/lib/niem

# No default command that writes anything. `niem` alone prints the available commands, so an image
# started by mistake describes itself instead of ingesting something.
ENTRYPOINT ["niem"]
CMD ["--help"]

LABEL org.opencontainers.image.title="NIEM Integration Platform" \
      org.opencontainers.image.description="Operator CLI: validate, describe, author, run, replay, inspect" \
      org.opencontainers.image.source="https://github.com/nrpllc/niemvault" \
      org.opencontainers.image.licenses="NONE"
