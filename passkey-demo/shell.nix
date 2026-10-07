# Everything the passkey demo needs, from the same pinned nixpkgs as the app:
#
#   nix-shell passkey-demo --run 'pytest passkey-demo'                 # unit tests
#   nix-shell passkey-demo --run 'python passkey-demo/check_chromium.py' # against real Chromium
#   nix-shell passkey-demo --run 'python passkey-demo/check_injected_script.py' # the app's script
#   nix-shell passkey-demo --run 'python passkey-demo/passkey_demo.py'   # serve it
#   nix-shell passkey-demo --run 'passkey-demo/over-iroh.sh <state-dir>' # serve it to the phone
#
# The far side is the real one: iroh-uds-listen from sashee/nixos-test, the
# same binary that fronts the monitoring platform. Not nixpkgs' dumbpipe: it
# speaks the same wire format, but on iroh 0.35, which iroh 1.0 cannot reach.
let
  pkgs = import ../nixpkgs.nix;

  nixosTest = builtins.fetchTarball {
    url = "https://github.com/sashee/nixos-test/archive/bb1bf9ffbd7926e46c9eebd5a34d14f2ae015952.tar.gz";
    sha256 = "1sdb6g623aq0bjzw44ziyphm5q99ayp158pqfvq03izf7wd7mf43";
  };
  irohSsh = pkgs.callPackage "${nixosTest}/packages/iroh-ssh/package.nix" { };
in
pkgs.mkShell {
  packages = [
    (pkgs.python3.withPackages (p: [
      p.webauthn
      p.playwright
      p.pytest
    ]))
    pkgs.chromium
    irohSsh
  ];

  # A library path inherited from the host makes the pinned Chromium load a
  # libasound built against a newer glibc than its own, and it dies before
  # printing anything useful.
  shellHook = ''
    unset LD_LIBRARY_PATH
  '';
}
