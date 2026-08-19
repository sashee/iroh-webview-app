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
  url = "https://github.com/nix-community/fenix/archive/d57340fe40c2ee12f86c0e087d2239d682b54eb0.tar.gz";
  sha256 = "1yrii22ws5f0giaz4y6d705k2f4qziiviv0wnsfpf5jaw87srdnz";
})
