# Third-party notices

## delta (supershadoe/delta)

https://github.com/supershadoe/delta — the Android hotspot controller in this
project is written after delta's approach (Shizuku-wrapped system binders
with the `com.android.shell` caller package, Shizuku state handling).
See `docs/DECISIONS.md` (D2).

```
Copyright 2024, 2025 supershadoe

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Libraries

| Library | Used by | License |
|---------|---------|---------|
| [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) | android | MIT |
| [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) | android | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | android | Apache-2.0 |
| [Jetpack Compose, Material 3, AndroidX Activity/Lifecycle](https://developer.android.com/jetpack) | android | Apache-2.0 |
| [Material Symbols](https://github.com/google/material-design-icons) (icons) | android, macos | Apache-2.0 |
| [tinygo-org/bluetooth](https://github.com/tinygo-org/bluetooth) | macos | BSD-3-Clause |
| [fxamacker/cbor](https://github.com/fxamacker/cbor) | macos | MIT |
| [keybase/go-keychain](https://github.com/keybase/go-keychain) | macos | MIT |

Full license texts ship with each library.
