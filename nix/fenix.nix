# A Rust toolchain carrying the Android std libraries.
#
# nixpkgs' own rustc ships only the host's `rust-std`, and cross-compiling Rust
# through `pkgsCross` fights with the NDK over `ring`. fenix hands over the
# prebuilt `rust-std` for each Android target instead, which is the same thing
# rustup would install and the path `cargo-ndk` assumes.
#
# Pinned to a commit rather than to `refs/heads/main`: a branch tarball is a
# moving target, so the content hash starts failing the moment upstream pushes,
# and the build breaks for a reason that has nothing to do with this repo.
import (builtins.fetchTarball {
  url = "https://github.com/nix-community/fenix/archive/dec0a3fee20fbe567c75362e1a527387b3d4a2c3.tar.gz";
  sha256 = "0mlzjir7h076m3s14bq5by385zsy1ayi6y59kj2zzdsf61gvj4nk";
})
