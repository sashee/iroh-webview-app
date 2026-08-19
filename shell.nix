let
  pkgs = import ./nixpkgs.nix;
  minSdk = 34;
in

let
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    abiVersions = [ "arm64-v8a" "x86_64" ];
    includeEmulator = false;
    includeNDK = false;
  };
  androidSdk = android.androidsdk;

  # Robolectric resolves its Android runtime jar outside normal Gradle
  # dependency resolution, so it and the generated robolectric-deps.properties
  # mapping must be provided explicitly. One jar, because minSdk and targetSdk
  # are both 34.
  robolectricSdk34Jar = pkgs.fetchurl {
    url = "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/14-robolectric-10818077-i4/android-all-instrumented-14-robolectric-10818077-i4.jar";
    hash = "sha256-o2O7AQo+geXEW5NwILOV9NsPsA1bxqpEx7WvxNFcnIk=";
  };
  robolectricDepsProperties = pkgs.writeText "robolectric-deps.properties" ''
    org.robolectric\:android-all-instrumented\:14-robolectric-10818077-i4=${robolectricSdk34Jar}
  '';

  nativeLibs = import ./nix/native-libs.nix { inherit pkgs minSdk; };
in
pkgs.mkShell {
  packages = [
    pkgs.gradle
    pkgs.jdk17
    androidSdk
    pkgs.cargo
    pkgs.rustc
  ];

  shellHook = ''
    export JAVA_HOME=${pkgs.jdk17}
    export ANDROID_SDK_ROOT=${androidSdk}/libexec/android-sdk
    export ANDROID_HOME=$ANDROID_SDK_ROOT
    export SIGNING_STORE_FILE=$PWD/signing/app.jks
    export SIGNING_STORE_PASSWORD=changeme
    export SIGNING_KEY_ALIAS=app
    export SIGNING_KEY_PASSWORD=changeme
    export ROBOLECTRIC_DEPS_PROPERTIES=${robolectricDepsProperties}
    # Gradle needs the libraries to exist even when only compiling Kotlin, and
    # building them here keeps `gradle` in this shell usable for regenerating
    # the lockfiles.
    export JNI_LIBS_DIR=${nativeLibs}
  '';
}
