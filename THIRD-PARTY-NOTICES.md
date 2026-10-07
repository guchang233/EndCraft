# Third-party source notices

* Minecraft guest and shared-memory layout: chasmlol/SkyCraft, commit `bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32`, MIT. Original license is retained in `mc/LICENSE`. The EndCraft mapping identity/version, standby gate, telemetry, build dependencies and entrypoint are modifications; original package IDs remain for compatibility.
* Better-Endfield ABI headers and bootstrap/Host: Dr-hydra/Better-Endfield, commit `35216279f716b9a7b90bf565ec7e25e8999705b9`, AGPL-3.0. Project license is retained in `LICENSE`. The bootstrap wrapper selects the ordinary XInput loader, adds a game-directory settings sidecar and loader diagnostics; the settings-store wrapper pins this install's user configuration path. The upstream manual-mapping injector is not built or used.
* nlohmann/json 3.12.0: MIT, retained in `native/include/nlohmann/LICENSE.MIT`.
* Gradle wrapper: Apache-2.0; original file headers are retained.
* MinHook used by the Better-Endfield Host: upstream license retained in `third_party/Better-Endfield/native/shared/third_party/minhook/LICENSE.txt`.

`tools/prepare-framework.ps1` reproduces the Host runtime/index diagnostics and PID logging changes into `build/generated`; pinned upstream source files remain unchanged. Metadata probing attaches and detaches its own IL2CPP RPC worker through exported runtime functions; no game memory offsets are guessed.

No game binaries, authentication credentials, proprietary textures or account files are included in the module ZIP. Download/build tooling obtains dependencies separately. Redistributing the AGPL Host requires providing its corresponding source and notices.
