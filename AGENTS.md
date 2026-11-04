# This repository

The Android app for LearnHarness, as a server shell: a native host (`android/`) whose WebView loads the user's own self-hosted LearnHarness server. The LearnHarness web app and server live in the fork at https://github.com/shouryadixitisverycool/learnharness. Web-side Android behavior (narrow layouts, the export bridge `window.lhAndroidSaveText`, the `window.lh.server` settings row, the Back-button hooks) belongs there, not here.

# No guessing

All design authority belongs to the human. **Never guess a design or implementation decision.** If anything is ambiguous — naming, behavior, data shapes, UX, defaults, scope boundaries, prompt wording — STOP and ask with the ask-user-question tool before proceeding. If you are "a little unsure," you are unsure: ask. A wrong guess is far more expensive than a question. Ask early, ask specific, one question per call.

Concretely: first investigate and plan rigorously (asking the user design questions along the way), get the plan approved by the user, and only then implement. Implementation should be mechanical execution of an approved plan — not interpretation.
