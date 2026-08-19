# The proxy cdylib, cross-compiled for every ABI the APK ships, laid out the way
# Gradle's `jniLibs.srcDirs` expects:
#
#   $out/arm64-v8a/libiroh_webview_proxy.so
#   $out/x86_64/libiroh_webview_proxy.so
#
# `cargo-ndk` is not used: all it does is set the linker and compiler variables
# below, and doing it here keeps the toolchain visible instead of behind a tool.
{ pkgs, minSdk }:

let
  fenix = (import ./fenix.nix { inherit (pkgs) system; });

  toolchain = fenix.combine [
    fenix.stable.cargo
    fenix.stable.rustc
    fenix.targets.aarch64-linux-android.stable.rust-std
    fenix.targets.x86_64-linux-android.stable.rust-std
  ];

  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    abiVersions = [ "arm64-v8a" "x86_64" ];
    includeEmulator = false;
    includeNDK = true;
  };

  src = pkgs.lib.sourceByRegex ../rust [
    "Cargo\\.(toml|lock)"
    "src(/.*)?"
  ];

  # Offline dependency source. `importCargoLock` fetches each crate as its own
  # fixed-output derivation from the lock file, so the build itself needs no
  # network -- the same property the Gradle half gets from mkM2Repository.
  vendor = pkgs.rustPlatform.importCargoLock {
    lockFile = ../rust/Cargo.lock;
  };

  # ABI directory name -> rust target triple. The names on the left are
  # Android's and are what the APK layout requires; the ones on the right are
  # rustc's.
  abis = {
    "arm64-v8a" = "aarch64-linux-android";
    "x86_64" = "x86_64-linux-android";
  };
in
pkgs.stdenv.mkDerivation {
  pname = "iroh-webview-proxy-jni";
  version = "0.1.0";
  inherit src;

  nativeBuildInputs = [ toolchain android.androidsdk ];

  # Cross-compiled output; nothing here is worth a cache round trip compared to
  # the source it is built from.
  preferLocalBuild = true;

  buildPhase = ''
    runHook preBuild

    export CARGO_HOME=$PWD/.cargo-home
    mkdir -p $CARGO_HOME
    cat > $CARGO_HOME/config.toml <<'EOF'
    [source.crates-io]
    replace-with = "vendored-sources"

    [source.vendored-sources]
    directory = "@vendor@"
    EOF
    substituteInPlace $CARGO_HOME/config.toml --replace '@vendor@' '${vendor}'

    # The NDK's clang wrappers are named for the API level they target, so the
    # minSdk the Gradle build declares is the one linked against here too.
    ndk=$(echo ${android.androidsdk}/libexec/android-sdk/ndk*/*/toolchains/llvm/prebuilt/*/bin)
    if [ ! -d "$ndk" ]; then
      ndk=$(echo ${android.androidsdk}/libexec/android-sdk/ndk-bundle/toolchains/llvm/prebuilt/*/bin)
    fi
    echo "using NDK toolchain at $ndk"

    export AR_aarch64_linux_android=$ndk/llvm-ar
    export AR_x86_64_linux_android=$ndk/llvm-ar
    export CC_aarch64_linux_android=$ndk/aarch64-linux-android${toString minSdk}-clang
    export CC_x86_64_linux_android=$ndk/x86_64-linux-android${toString minSdk}-clang
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$CC_aarch64_linux_android
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=$CC_x86_64_linux_android

    ${pkgs.lib.concatStringsSep "\n" (pkgs.lib.mapAttrsToList (abi: target: ''
      echo "building ${target} for ${abi}"
      cargo build --release --offline --locked --target ${target}
    '') abis)}

    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall

    ndk=$(echo ${android.androidsdk}/libexec/android-sdk/ndk*/*/toolchains/llvm/prebuilt/*/bin)
    if [ ! -d "$ndk" ]; then
      ndk=$(echo ${android.androidsdk}/libexec/android-sdk/ndk-bundle/toolchains/llvm/prebuilt/*/bin)
    fi

    ${pkgs.lib.concatStringsSep "\n" (pkgs.lib.mapAttrsToList (abi: target: ''
      mkdir -p $out/${abi}
      cp target/${target}/release/libiroh_webview_proxy.so $out/${abi}/
      # Stripped with the NDK's own llvm-strip, not the host's: these are ELF
      # objects for another architecture, and it takes ~19MB per ABI down to a
      # few. The JNI entry points are exported symbols and survive.
      $ndk/llvm-strip --strip-unneeded $out/${abi}/libiroh_webview_proxy.so
    '') abis)}

    runHook postInstall
  '';

  # Cross-compiled ELF for another architecture; the usual host fixups do not
  # apply and stripping with the host toolchain would corrupt them.
  dontStrip = true;
  dontPatchELF = true;
  dontFixup = true;
}
