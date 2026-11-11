# Offline situation music

The instrumental scores in `OriginalMusic.kt` were authored for this change on
2026-11-11. The melody note sequences, chord progressions, bass patterns,
arpeggios, instrument envelopes and arrangements are defined directly in source.
There are no imported recordings, samples, third-party songs or external music
links. PCM is synthesized locally from these scores. No user data, Apple content,
credentials or audio is sent anywhere.

Each score has sixteen 4/4 bars and a final tonic with a two-second tail:

| Native journey | Score | Tempo | Approximate length |
| --- | --- | --- | --- |
| Morning / quick breathing | 아침의 첫걸음 | 104 BPM | 38.9 seconds |
| Lunch nap | 한낮의 쉼표 | 72 BPM | 55.3 seconds |
| Preparation | 다시 한 걸음 | 96 BPM | 42 seconds |
| Evening | 하루를 접으며 | 80 BPM | 50 seconds |

Each has piano-like, bell-like and plucked synthesized arrangements. These names
describe synthesis timbre, not recordings of acoustic instruments. The selection
is a fixed mapping from the native journey kind and the user's arrangement
choice. It is not AI recommendation and does not read listening history. The
persisted `MusicChoice.RECENT` enum remains compatible with old snapshots; its
displayed choice is now the bell arrangement, with no claim of recent listening.

The original music entry point exists only in the preparation journey:
F3-06 selects an arrangement and "이 음악 듣기" starts it in F3-07.
The existing back / "다른 곡 고르기" controls stop playback before changing
selection. "준비됐어요" and the existing activity stop control also release
playback. Morning, lunch and evening have score mappings but no original music
start control. They do not start these scores automatically, and no new entry
point was added. Existing rest-sound controls retain their separate ambient
sound behavior.

Playback is finite, without looping or automatic replay. Preparation requests
remain `REQUESTED` until an Android AudioTrack playback-head notification reports
positive progress. Completion, focus denial/loss (including duck requests),
route interruption, background, destruction, safety restrictions and user
stop/change dispose the owned output and abandon its focus. Session/revision
and disposable-output generation checks reject delayed callbacks. Restart
recovery interrupts the journey and never recreates playback.

The waveform tests establish deterministic, bounded, tonal PCM. They do not
establish speaker audibility, perceived musical quality, vehicle audio routing
or actual AudioTrack callback delivery. Those require the later single-owner
native QA stage. No device or emulator is used by this executor.
