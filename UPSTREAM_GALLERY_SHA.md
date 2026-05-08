# Upstream Gallery Pin

This repository forks **google-ai-edge/gallery** (Apache License 2.0) and extends
its Audio Scribe path into the Bidet AI brain-dump pipeline (chunked rolling
capture, Whisper-tiny ASR, Gemma 4 E4B four-tab restructuring).

| Field            | Value                                                      |
| ---------------- | ---------------------------------------------------------- |
| Upstream repo    | https://github.com/google-ai-edge/gallery                  |
| Pinned commit    | `ad7aad854ba34bf205922ddc12cca6cc7fb4f0ae`                 |
| Commit date      | 2026-05-07                                                 |
| Forked on        | 2026-05-07                                                 |
| Forked by        | bidet-ai contributors                                      |
| Upstream license | Apache 2.0 (see `LICENSE`, attribution in `NOTICE`)        |

## Update protocol

To resync against upstream:

```bash
git remote add upstream https://github.com/google-ai-edge/gallery.git
git fetch upstream
git diff <pinned-sha> upstream/main -- Android/src/
```

When updating the pin, bump the SHA above and re-run the modification-line
audit on every file under `Android/src/app/src/main/java/com/google/ai/edge/gallery/`
that we have changed (each carries a
`Modifications Copyright 2026 bidet-ai contributors. Changed: ...` line per
Apache 2.0 §4(b)).
