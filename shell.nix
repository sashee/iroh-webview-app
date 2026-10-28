let
  pkgs = import ./nixpkgs.nix;
  minSdk = 34;
in

let
  android = import ./nix/android.nix { inherit pkgs; };

  nativeLibs = import ./nix/native-libs.nix { inherit pkgs minSdk; };
in
pkgs.mkShell {
  packages = [
    android.gradle
    android.jdk
    android.sdk
    pkgs.cargo
    pkgs.rustc
  ];

  shellHook = ''
    export JAVA_HOME=${android.jdk}
    export ANDROID_SDK_ROOT=${android.sdk}/libexec/android-sdk
    export ANDROID_HOME=$ANDROID_SDK_ROOT
    export SIGNING_STORE_FILE=$PWD/signing/app.jks
    export SIGNING_STORE_PASSWORD=changeme
    export SIGNING_KEY_ALIAS=app
    export SIGNING_KEY_PASSWORD=changeme
    export ROBOLECTRIC_DEPS_PROPERTIES=${android.robolectricDepsProperties}
    # Gradle needs the libraries to exist even when only compiling Kotlin, and
    # building them here keeps `gradle` in this shell usable for regenerating
    # the lockfiles.
    export JNI_LIBS_DIR=${nativeLibs}
  '';
}
