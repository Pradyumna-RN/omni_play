# Apple Music account playback — 0.3.27

Music now contains two experimental account paths. Both require the listener's
active subscription and run inside this app on Android 11+ (ARM64/x86-64).
No Apple developer registration, separate DSP app or desktop server is used by
these adapters. These are unofficial integrations; Apple's provider behavior and
the imported native runtime can change independently of Music.

## AAC account playback

Open **Settings → Playback sources → Sign in · AAC stereo**. Complete sign-in
and verification on Apple's own page, then tap Connect if it does not connect
automatically. The isolated WebView captures only that account's MusicKit session;
it has no Android JavaScript bridge. Music verifies the account storefront before
saving a KeyStore-encrypted session. The separate YouTube login is unaffected.

When you play a matching YouTube recording, Music searches the Apple catalogue
and requests the subscriber's AAC rendition. Media3 obtains its license from
Apple through Android Widevine and plays it in the normal in-app player. AAC is
lossy and is never labelled ALAC, lossless or multichannel. Stereo bypasses DMR.
Apple can reject embedded web sign-in or playback on a particular WebView/account;
that behavior has not been tested with a live subscriber here.

## Native lossless and multichannel

Choose **Set up lossless & multichannel** in the same settings card. In an
authorized runtime-bundled build, Music prepares the compatible runtime
automatically, then shows Apple Account sign-in and verification-code fields.
There is no document-selection step. Account subscription/entitlement is still
required, and successful import does not verify live subscriber playback.

Ordinary public-source builds contain no Apple images and retain the one-time
local import option. The profile requires Apple Music **3.6.0-beta / build 1109**
for ARM64 or x86-64. Current installers have a different ABI and are rejected.
Every image is checked against its pinned SHA-256 before loading; preparation is
cancellable and commits the private directory only after complete verification.
The installer itself is not installed, uploaded or shipped in Music.

The adapter runs in a non-exported, app-private service process so a vendor crash
does not crash the interface. Passwords and codes are not persisted by Music.
Session tokens are KeyStore-encrypted. The imported SDK maintains its own private
session cache under Android's app data protection; it is not the same encrypted
token vault. Disconnect invalidates pending resolutions, stops Apple playback,
terminates the worker and removes the retired SDK session directory.

The account's native dispatch provides the available HLS renditions. Music
inspects their MP4 initialization data, preferring verified E-AC-3 JOC, then actual
lossless multichannel, then the highest available lossless sample rate/bit depth.
It retains an independently authenticated AAC fallback. A catalogue badge or file
name alone never establishes the delivered codec or speaker count.

Media3 binds each segment to its own key URI and MP4 sample encryption metadata.
The imported runtime processes subscriber-authorized samples in memory, with clear
subsample regions and partial blocks preserved. The special bootstrap description
is identified from multi-description MP4 metadata, rather than treating ordinary
track samples as bootstrap data. Unsupported encryption patterns fail instead of
feeding damaged audio into the decoder. Encoded clear audio is not written to a
disk cache. Requests to media hosts contain no Apple account authorization.

FFmpeg now includes ALAC and emits float PCM for it, preserving 24-bit input detail.
Stereo plays unchanged. Discrete surround enters the existing DMR; E-AC-3 JOC uses
the standard surround-bed decoder. Synthetic upmix and binaural processing are
disabled; the original decoded speaker layout feeds DMR.
Original/Processed all render stereo. There is no claim of Dolby-certified
rendering, authored stem recovery, physical multichannel output or system-wide
bit-perfect playback.

## Validation boundary

Host tests cover real JNI JSON/thread attachment, unauthenticated-operation
rejection, private session storage, verification/cancellation, source matching,
real MP4 codec probing, encrypted ALAC sample restoration, range seeking,
per-segment key binding, no credentials on CDN requests and settings callbacks.
Generated 24-bit ALAC fixtures decode sample-exactly at 192 kHz stereo and 96 kHz
5.1 through the actual FFmpeg Java/JNI decoder, including a flush/seek.

The user-supplied compatible archive passed static hash/ELF inspection and the
actual importer for both architectures. Automatic bundled preparation is tested
without executing Apple code. No phone or live subscriber session was available. **Native library loading, Apple sign-in, full commercial-track
playback, entitlement availability and phone performance remain unverified.**
The UI integration is implemented; those tests do not establish that a particular
phone/account will receive lossless or JOC. Test that on the subscribed device
and read the player's actual codec/rate/channel report. No password, token or
music upload is needed in chat.

Build the public adapter with `tools/apple/build_native.py` for both Android
ABIs and pass `-PrtensorAppleJniDir=<output>/jni`. Host JNI tests also accept
`-PrtensorAppleHostLibraryDir=<output>/host`. `:app:verifyAppleNativeBundle`
checks source/adapter hashes and rejects unexpected vendor images. For an
authorized bundled build, additionally pass
`-PrtensorAppleRuntimeAssetsDir=/absolute/private/assets`, containing
`apple-runtime/<ABI>/<18 pinned library names>.bin` and the unmodified original
`apple-runtime/acknowledgements.html`. The directory must be outside the source
repository. The APK guard checks every asset hash and the original acknowledgements;
public source archives exclude all vendor images. The distributor is responsible
for the redistribution authorization. No contract verification is claimed here.

In 0.3.25, dependency loading starts from the adapter’s playback entry points.
Unrelated SDK test utilities are not preloaded. A missing required dependency
still rejects the runtime and identifies the library that requested it.

The supplied old runtime does not meet 16 KiB ELF alignment throughout. Its
behavior on 16 KiB-page phones remains unverified; the Android 11 minimum and
two architecture builds are not a guarantee of native runtime compatibility.

The `.bin` suffix is only the compressed APK asset representation. Automatic
setup restores the original `.so` names and verifies the original byte hashes.
It prevents Android from storing these assets as uncompressed install-time JNI
libraries and keeps the APK within the existing OTA size limit.

The build assets can be reproduced from the authorized two-architecture APKM:

```sh
python3 tools/apple/prepare_runtime_assets.py /private/compatible-installer.apkm \
  --output /private/runtime-assets
```

Then pass `-PrtensorAppleRuntimeAssetsDir=/private/runtime-assets`. The preparer
rejects changed library/acknowledgement hashes and an output inside the source
repository. It performs no download, account operation or native execution.
