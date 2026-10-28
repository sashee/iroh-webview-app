let
  pinnedNixpkgs = builtins.fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/3ec636362e1816bc2f0888fa0fa2c69f1bc9e504.tar.gz";
    sha256 = "177diqkiwphjyqpk2n6nisayx840x28ksvq99kkyi2xsn37fp4md";
  };
in
import pinnedNixpkgs {
  config = {
    allowUnfree = true;
    android_sdk.accept_license = true;
  };
}
