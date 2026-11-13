# HeliBoard autocorrect baseline review

This note records what the existing fork already implements. It is a source review, not a measured quality result.

## Existing implementation

The main suggestion pipeline is `app/src/main/java/helium314/keyboard/latin/suggestions/Suggest.kt`. It delegates candidate generation to `DictionaryFacilitator`, passes the active `NgramContext`, keyboard geometry and suggestion settings, and uses a layered acceptance policy before setting `willAutoCorrect`.

The current decoder constant is:

```kotlin
SHOULD_AUTO_CORRECT_USING_NON_WHITE_LISTED_SUGGESTION = false
```

The acceptance logic already considers:
- explicit whitelist status and whether a typed word is known in a dictionary;
- configured normalized-score threshold via `AutoCorrectionUtils.suggestionExceedsThreshold`;
- n-gram scores for the proposed word versus the typed word;
- dictionary locale, so a word from another active language is not casually substituted;
- digits, mixed capitalization, resumed composing state and whether an initialized main dictionary exists;
- email/URL-like punctuation as part of its non-whitelisted acceptance checks;
- language-specific filters for suggestions containing spaces.

This is a useful conservative starting point. Do not replace it with a generic English-only spellchecker.

## Existing regression tests

`app/src/test/java/helium314/keyboard/latin/SuggestTest.kt` already tests:
- context-sensitive choice between words such as `on` and `in`;
- contraction behavior such as `ill` versus `I'll`;
- locale-sensitive `i` / `I` handling and cross-language candidate rejection;
- French accent handling such as `ne` / `né`;
- shortcut correction thresholds and shortcut opt-out;
- keeping the typed word selectable while autocorrection is pending;
- capitalization modes, mixed-case protection and suggestion ordering.

Keep these tests as compatibility gates when tuning autocorrection.

## What is not yet measured

The existing unit tests use controlled suggestion fixtures. They do not establish real-world correction quality or a false-correction rate on a representative corpus. The new Python touch-noise harness is currently a reference evaluator; its SymSpell mode is not the HeliBoard engine, and its native CLI adapter still needs a real engine bridge.

Before tuning score thresholds or adding a neural reranker:
1. Capture a reproducible baseline from the existing HeliBoard engine.
2. Add licensed, versioned clean-text corpora per supported language.
3. Simulate key-coordinate noise using the actual keyboard geometry and collect no production user text.
4. Report correction rate and false-correction rate separately, with confidence intervals and corpus/version metadata.
5. Add regression cases for proper nouns, URLs, email addresses, numbers, contractions, code/technical tokens and sensitive input fields.
6. Measure p50/p95 suggestion latency on named physical devices.

## Engineering decision

Preserve HeliBoard's dictionary engine, multilingual behavior and existing acceptance policy initially. Optimize only after the baseline is reproducible. A neural reranker is a later experiment, not a prerequisite for the first useful keyboard build.
