# Third-party notices and provenance

## HeliBoard
- Upstream: https://github.com/HeliBorg/HeliBoard
- License: GPL-3.0 (preserve exact upstream license and notices).
- Distributed derivatives must comply with GPL-3.0 source and notice obligations.

## whisper.cpp
- Upstream: https://github.com/ggml-org/whisper.cpp
- License: MIT; copyright and permission notice retained in third_party/whisper.cpp/LICENSE.
- Vendored as a git submodule pinned to release tag v1.9.5 (commit d1be6fde11ac6e0407606b4e42fe72d34add8037), built from source in the `:asr` module, CPU only.
- Any bump of this pin needs a review of the upstream diff, including ggml and its transitive code.

## Models and datasets
Model weights and evaluation corpora have independent terms. Record provenance, license, version and SHA-256 for every shipped artifact. Do not assume software licenses cover model weights or datasets.

This is an initial inventory, not legal advice or a completed software bill of materials.

## Voice model
- File: ggml-base.en-q5_1.bin (OpenAI Whisper base.en, quantized by whisper.cpp), downloaded at build time from https://huggingface.co/ggerganov/whisper.cpp
- Weights are published under the MIT license by OpenAI (Whisper); confirm the current terms before release.
- SHA-256 pin: aster-project/asr/MODEL_SHA256 = 4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f, recorded from the first CI download of ggml-base.en-q5_1.bin (trust on first use: cross-check it against an independent source before release).
