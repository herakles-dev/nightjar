# Builds the Jazzer fuzz targets in fuzz/ against the app's Android-free parsers.
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:6cd618d0e67c3e58a9071f4cfbaf9ded48f6b8fc984664a571ef994464f42486

# Standalone Kotlin compiler (the fuzz targets and the sources they exercise are compiled
# directly, outside Gradle/Android). Checksum-pinned.
ARG KOTLIN_VERSION=2.2.21
ARG KOTLIN_SHA256=a623871f1cd9c938946948b70ef9170879f0758043885bbd30c32f024e511714
RUN curl -fsSL -o /tmp/kotlin.zip \
      "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip" \
    && echo "${KOTLIN_SHA256}  /tmp/kotlin.zip" | sha256sum -c - \
    && unzip -q /tmp/kotlin.zip -d /opt \
    && rm /tmp/kotlin.zip

COPY . $SRC/nightjar
COPY .clusterfuzzlite/build.sh $SRC/build.sh
WORKDIR $SRC/nightjar
