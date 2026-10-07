let
  pkgs = import ./nixpkgs.nix;
  # An allowlist, not `gitignoreSource`. Two reasons, both about the build
  # depending on exactly what it says it depends on: a `.gitignore` filter also
  # sweeps in *untracked* files, so a stray note left in the working tree
  # changes the derivation and the same commit stops producing the same APK; and
  # it drags in the docs, so editing README.md rebuilds an Android app.
  #
  # `rust/` is deliberately absent: the Rust half reaches this build as the
  # `nativeLibs` and `rust` derivations, each with its own source filter.
  src = pkgs.lib.sourceByRegex ./. [
    "app(/.*)?"
    "gradle(/.*)?"
    "signing(/.*)?"
    "settings\\.gradle\\.kts"
    "build\\.gradle\\.kts"
    "gradle\\.properties"
  ];

  # Kept in one place: the NDK's clang wrappers are named for the API level, so
  # the Rust half has to link against the same minSdk app/build.gradle.kts
  # declares. The two drifting apart produces a library that loads and then
  # fails on a missing symbol.
  minSdk = 34;
  version = "0.1.0";
in

let
  # Pinned to a commit, not to `refs/heads/main`: a branch tarball is a moving
  # target, so the content hash starts failing the moment upstream pushes and
  # the build breaks for a reason that has nothing to do with this repo. (It
  # already happened once here, with fenix.)
  buildGradleApplicationSrc = builtins.fetchTarball {
    url = "https://github.com/raphiz/buildGradleApplication/archive/ee11e6ca94048c7c09681e5126f8c3556717d93d.tar.gz";
    sha256 = "0rkw2i0bk3dgmkgsqrcxsg0092shcf0s8q7dvxvw3niklxri6lrp";
  };
  fetchArtifact = pkgs.callPackage "${buildGradleApplicationSrc}/fetchArtefact/default.nix" { };
  mkM2Repository = pkgs.callPackage "${buildGradleApplicationSrc}/buildGradleApplication/mkM2Repository.nix" {
    inherit fetchArtifact;
  };

  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    abiVersions = [ "arm64-v8a" "x86_64" ];
    includeEmulator = false;
    includeNDK = false;
  };
  androidSdk = android.androidsdk;
  buildTools = "${androidSdk}/libexec/android-sdk/build-tools/34.0.0";

  # Robolectric resolves its Android runtime jar outside normal Gradle
  # dependency resolution, so it and the generated robolectric-deps.properties
  # mapping must be provided explicitly rather than through
  # verification-metadata.xml or the offline Maven repo. One jar, because
  # minSdk and targetSdk are both 34.
  robolectricSdk34Jar = pkgs.fetchurl {
    url = "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/14-robolectric-10818077-i4/android-all-instrumented-14-robolectric-10818077-i4.jar";
    hash = "sha256-o2O7AQo+geXEW5NwILOV9NsPsA1bxqpEx7WvxNFcnIk=";
  };
  robolectricDepsProperties = pkgs.writeText "robolectric-deps.properties" ''
    org.robolectric\:android-all-instrumented\:14-robolectric-10818077-i4=${robolectricSdk34Jar}
  '';

  offlineRepository = mkM2Repository {
    pname = "iroh-webview-app";
    inherit version src;
    repositories = [
      "https://dl.google.com/dl/android/maven2"
      "https://repo1.maven.org/maven2"
      "https://plugins.gradle.org/m2"
    ];
  };

  # The Rust proxy's own test suite, run on the host. A separate derivation from
  # the cdylibs because `buildRustPackage` has nothing to run tests on once it
  # is cross-compiling -- and because these are the tests that matter most, so
  # they should not be reachable only through an APK build.
  #
  # They work in the sandbox, which has no network: the transport tests bind
  # loopback only and use `presets::Minimal`, so no relay and no DNS is reached
  # for. See rust/tests/support/mod.rs.
  rust = pkgs.callPackage ./rust/package.nix { };

  # The cdylib for every ABI the APK ships, laid out for Gradle's jniLibs.
  nativeLibs = import ./nix/native-libs.nix { inherit pkgs minSdk; };

  # The script injected into pages for passkeys, tested in Node against a fake
  # bridge. Robolectric's WebView runs no JavaScript, so without this the
  # script would first execute on the phone.
  passkeyScript = pkgs.runCommand "passkey-script-tests" { nativeBuildInputs = [ pkgs.nodejs ]; } ''
    PASSKEYS_JS=${./app/src/main/assets/passkeys.js} node --test ${./app/src/test/js/passkeys.test.mjs}
    touch $out
  '';
in

# `nix-build` produces the signed APK, having run all three test suites on the
# way: Rust, the passkey script's, and Robolectric. `nix-build -A rust`,
# `-A passkeyScript` and `-A nativeLibs` stop at the parts.
pkgs.stdenv.mkDerivation {
  pname = "iroh-webview-app";
  inherit version src;
  preferLocalBuild = true;
  allowSubstitutes = false;

  nativeBuildInputs = [
    pkgs.gradle
    pkgs.jdk17
    androidSdk
  ];

  buildPhase = ''
    export JAVA_HOME=${pkgs.jdk17}
    export ANDROID_SDK_ROOT=${androidSdk}/libexec/android-sdk
    export ANDROID_HOME=$ANDROID_SDK_ROOT
    export SIGNING_STORE_FILE=$PWD/signing/app.jks
    export SIGNING_STORE_PASSWORD=changeme
    export SIGNING_KEY_ALIAS=app
    export SIGNING_KEY_PASSWORD=changeme
    export MAVEN_SOURCE_REPOSITORY=${offlineRepository.m2Repository}
    export ROBOLECTRIC_DEPS_PROPERTIES=${robolectricDepsProperties}
    export JNI_LIBS_DIR=${nativeLibs}
    # Named only to make the Rust suite a build dependency of the APK: a
    # library whose own tests fail should not reach a phone. Nothing reads it.
    export RUST_TESTS_PASSED=${rust}
    export PASSKEY_SCRIPT_TESTS_PASSED=${passkeyScript}
    mkdir -p .gradle-home
    export GRADLE_USER_HOME=$PWD/.gradle-home
    gradle --offline --no-daemon --init-script ${./nix/offline-init.gradle.kts} -Dorg.gradle.project.android.aapt2FromMavenOverride=${buildTools}/aapt2 testDebugUnitTest assembleRelease
  '';

  installPhase = ''
    mkdir -p $out
    cp app/build/outputs/apk/release/app-release.apk $out/
  '';

  passthru = { inherit rust nativeLibs passkeyScript; };
}
