# NOTICE — third-party components

`app/src/main/assets/timer` is the CVE-2026-98260 exploit binary from
[YuKongA/CVE-2026-98260](https://github.com/YuKongA/CVE-2026-98260) (MIT).

`app/src/main/assets/ksud` is the KernelSU userspace CLI from
[tiann/KernelSU](https://github.com/tiann/KernelSU), **GPL-2.0**. It is
unmodified and embedded here so this app can late-load the matching
`android16-6.12_kernelsu.ko` and provide `su` without a separate manager APK.
The corresponding KernelSU source is available at the upstream repository;
the binary reports version `3.3.1-24-g528bafc5`.

`app/src/main/assets/otacerts.zip` contains the VS Team OTA signing
certificate used by the local-update demo.

The build also links against the Shizuku API (Apache-2.0).
