#!/bin/bash -eu
# Compiles the fuzz targets plus the Android-free sources they call, then emits one
# Jazzer launcher per *Fuzzer.kt. Add a target = drop a new fuzz/<Name>Fuzzer.kt in
# package dev.herakles.nightjar.fuzz (and extend SOURCES if it needs more files).

KOTLINC=/opt/kotlinc/bin/kotlinc
KOTLIN_STDLIB=/opt/kotlinc/lib/kotlin-stdlib.jar
M=app/src/main/java/dev/herakles/nightjar

SOURCES="$M/CovertModule.kt $M/Fft.kt $M/WavFile.kt $M/NightjarAcoustics.kt $M/AcousticCarrier.kt $M/incoming/FileSniffer.kt"

mkdir -p classes
$KOTLINC -no-stdlib -cp "$KOTLIN_STDLIB:$JAZZER_API_PATH" $SOURCES fuzz/*.kt -d classes
(cd classes && jar cf "$OUT/nightjar-fuzz.jar" .)
cp "$KOTLIN_STDLIB" "$OUT/"

RUNTIME_CLASSPATH='$this_dir/nightjar-fuzz.jar:$this_dir/kotlin-stdlib.jar:$this_dir'

for fuzzer in fuzz/*Fuzzer.kt; do
  fuzzer_basename=$(basename -s .kt "$fuzzer")
  cat > "$OUT/$fuzzer_basename" <<EOF
#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
if [[ "\$@" =~ (^| )-runs=[0-9]+($| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
LD_LIBRARY_PATH="$JVM_LD_LIBRARY_PATH":\$this_dir \\
ASAN_OPTIONS=\$ASAN_OPTIONS:symbolize=1:external_symbolizer_path=\$this_dir/llvm-symbolizer:detect_leaks=0 \\
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
--cp=$RUNTIME_CLASSPATH \\
--target_class=dev.herakles.nightjar.fuzz.$fuzzer_basename \\
--jvm_args="\$mem_settings" \\
\$@
EOF
  chmod +x "$OUT/$fuzzer_basename"
done
