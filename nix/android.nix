# The Android half's toolchain, shared by default.nix (the build) and shell.nix
# (regenerating the lockfiles): the two must resolve exactly the same
# dependencies, or the lockfiles the shell writes don't fit the build.
{ pkgs }:

let
  # The SDK compiled against. app/build.gradle.kts names the same platform and
  # build tools.
  platform = "37.0";
  buildToolsVersion = "37.0.0";

  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ platform ];
    buildToolsVersions = [ buildToolsVersion ];
    abiVersions = [ "arm64-v8a" "x86_64" ];
    includeEmulator = false;
    includeNDK = false;
  };

  # Robolectric resolves its Android runtime jar outside normal Gradle
  # dependency resolution, so it and the robolectric-deps.properties mapping
  # are provided here rather than through verification-metadata.xml and the
  # offline Maven repo. One jar: the tests run on targetSdk alone. The name is
  # Robolectric's own (DefaultSdkProvider): Android version, framework build,
  # and the version of its instrumentation, which moves with Robolectric.
  robolectricJar = "17-robolectric-15733970-i7";
  robolectricSdk = pkgs.fetchurl {
    url = "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/${robolectricJar}/android-all-instrumented-${robolectricJar}.jar";
    hash = "sha256-MKFSQ7qaagFjYYdvrMdUMvTsXbD54n1JhKSmsWe0Mr0=";
  };
in
{
  sdk = android.androidsdk;

  # Gradle 9, which the Android Gradle plugin 9 needs; nixpkgs' `gradle` is
  # still 8.
  gradle = pkgs.gradle_9;
  # 21, not 17: Robolectric runs Android 16 and later (the tests' targetSdk is
  # 37) only on a JDK 21 or later. The app's own bytecode still targets 17.
  jdk = pkgs.jdk21;

  # AGP's own aapt2 comes from Maven as a binary linked for a usual Linux,
  # which doesn't run here; the SDK's does.
  aapt2 = "${android.androidsdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";

  robolectricDepsProperties = pkgs.writeText "robolectric-deps.properties" ''
    org.robolectric\:android-all-instrumented\:${robolectricJar}=${robolectricSdk}
  '';
}
