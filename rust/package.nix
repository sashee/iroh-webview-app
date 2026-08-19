{ lib, rustPlatform }:

# Host-target build of the proxy crate. Its only job in `nix-build` is to run
# the test suite hermetically -- the Android cdylibs are cross-compiled
# separately (see nix/native-libs.nix), because `buildRustPackage` has no host
# to run tests on once it is cross-compiling.
rustPlatform.buildRustPackage {
  pname = "iroh-webview-proxy";
  version = "0.1.0";

  src = lib.sourceByRegex ./. [
    "Cargo\\.(toml|lock)"
    "src(/.*)?"
    "tests(/.*)?"
  ];

  cargoLock.lockFile = ./Cargo.lock;

  meta = {
    description = "Loopback TCP proxy carrying each connection over an iroh endpoint";
    license = with lib.licenses; [ mit asl20 ];
  };
}
