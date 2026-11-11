# Synthetic speech fixtures

These are locally generated test inputs, not human recordings or user history.
They are packaged only in the instrumentation APK.

Generated with the installed macOS Yuna Korean voice using `say -o`, then
converted with `afconvert` to 16 kHz, mono, signed little-endian PCM16. The RIFF
container was checked and removed; these files contain PCM bytes only.

| File | Synthetic source text | Bytes | SHA-256 |
|---|---|---:|---|
| `teum_synthetic_ko_first.pcm` | 오늘은 한 가지 일을 차분하게 마무리하겠습니다. | 102842 | `5906f498d937707ac1fe2eae4a651f5d1df83030df1718060a54045191346273` |
| `teum_synthetic_ko_second.pcm` | 내일 회의에서는 질문을 먼저 하겠습니다. | 95396 | `72c18ea7ac8de4599a7414afb2d33e361b05be53f8ed41a8482ed38bfbf9857e` |

`PleosPcmRecognitionTest` uses the production ON_DEVICE adapter and real SDK
service. It requires the Pleos service and Korean on-device model on the chosen
test emulator. Missing service/model is a failed or unverified integration,
never a skipped test or fabricated transcript.

The test preserves preferences and activity records, closes its SDK clients and
ActivityScenario, and writes only its synthetic receipt to the target app cache.
The QA owner must collect that receipt and remove the owned cache file.
