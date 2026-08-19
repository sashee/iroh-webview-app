let
  pinnedNixpkgs = builtins.fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/bcd464ccd2a1a7cd09aa2f8d4ffba83b761b1d0e.tar.gz";
    sha256 = "1424cs8r44zfj6jrl02spym937164qg0cs7rryvdzb6jggrk2xkp";
  };
in
import pinnedNixpkgs {
  config = {
    allowUnfree = true;
    android_sdk.accept_license = true;
  };
}
