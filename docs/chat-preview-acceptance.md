# Chat preview acceptance

## Verified baseline

Upstream `bba38ab34026414a99e8b63e9d914253db1c4b26`: Actions run 39400959266
completed successfully and published `ilmar-debug-apk`. This confirms compilation
and artifact availability, not phone or real-provider behavior.

The authoritative product roadmap remains in Obsidian (`Ilmar/ROADMAP.md`). This
file is a release test procedure, not a second roadmap.

## Implemented changes

- Global registry of OpenAI-compatible HTTPS connections and manually registered
  models. Connection credentials are stored separately in Android Keystore-backed
  AES-GCM ciphertext. Blank key input preserves the current key.
- Stable per-chat model references, searchable model bottom sheet, explicit
  unavailable states and global default. No silent provider fallback.
- Legacy settings migration preserves the SQLite history and original encrypted
  key. Matching legacy chat overrides migrate to IDs; unknown overrides remain
  unavailable until explicitly reselected. Metadata snapshot version is 1.
- Activity-scoped generation survives settings navigation and rotation; only
  complete SSE answers are committed. Stop cancels the actual HTTP call. Failure,
  stop or process interruption leaves the user message available for explicit
  retry without duplicating it. Only one generation runs at a time.
- Graphite UI, bounded sheets, scrolling composer and disabled unsupported modes.
- Bounded diagnostics store fixed codes, time and local chat IDs only. No prompt,
  answer, key, endpoint, exception body, or response body is recorded.

## Automated checks

Actions compiles Kotlin, runs unit/Robolectric tests, runs Android lint and builds
an APK on PRs as well as main. Reports and debug APK are separate artifacts. No
release/signing credentials or API keys are required. Confirm final run outcome;
inspect reports before treating this preview as accepted.

Tests cover registry migration/defaults, key separation using a fake secret vault,
per-chat selection, stable IDs after editing, URL validation, SSE completion and
truncation, cancellation/retry without duplicate messages, reply routing, explicit
HTTP failures and process-interruption restoration. The fake vault does not test
Android Keystore hardware behavior.

## Phone and real-provider gate — NOT VERIFIED

1. Install over the existing app without uninstalling. Confirm old chats, search,
   favorites, compact setting and key still work. Debug signing identity must
   match the installed app; never uninstall as an automatic workaround.
2. Confirm Settings → Diagnostics displays the expected version and SHA for the
   specific Actions run used to download the APK.
3. Add two real connections and multiple model IDs. Select different models in
   two chats. Restart; confirm selections and keys remain independent. Remove
   one key and verify that chat does not fall back to another provider.
4. Send a real streaming request. Open settings and rotate the device during it;
   return and verify one complete reply in the original chat. Switch chats during
   a request and confirm it never appears in the new chat.
5. Stop while the server sends no data: the network request must cancel promptly.
   Retry and confirm the user message appears only once. Disable network during
   streaming; incomplete assistant output must not become a completed message.
6. Check 401, 429, timeout and malformed/truncated SSE with a controlled endpoint;
   there must be no automatic repeat or hidden paid request.
7. Kill the process mid-response and relaunch. Confirm the user message remains,
   interruption is shown, and no request runs until the user retries. Clear or
   delete a chat during generation; a late reply must not recreate its messages.
8. Check small screen, font scale 1.5–2, long messages, keyboard, gesture and
   three-button navigation, model search, bottom sheets and history search.
   Scroll up during a response; streaming must not pull the reader down.
9. Inspect diagnostics: no secret, endpoint, prompt or answer appears. Clear it
   only through its confirmation dialog.

No phone screenshot or real-provider acceptance is claimed by automated tests.

## Deferred

GitHub execution, plugin runtime, SSH/Shell/browser, capabilities/reasoning/search,
Context Compact, background task scheduling, encrypted backups and production APK
signing remain separate work. History schema stays at SQLite version 1; this
change does not recreate or migrate that database. Process termination interrupts
requests; the app does not attempt to bypass Android force-stop behavior.

## Follow-up: base API address (2026-10-20)

The phone screenshot showed HTTP 404. The supplied address was
`https://openrouter.ai/api/v1`; the previous transport posted directly to that
base. The transport now resolves it to `/api/v1/chat/completions` on the same
host. `/v1` and bare-host API bases are also supported. Full and custom routes
remain unchanged, including their query parameters. This happens once before
sending; a 404 never triggers probing, retries, or a model change.

Settings show the final request address. Existing saved connections receive this
behavior without rewriting the registry or touching credentials/history. HTTP
401/403/404/429 have actionable messages, and errors link to global settings.

Before acceptance, retry the existing message on the phone. If 404 remains after
the route is correct, verify the model ID against the provider's actual catalog;
this change does not assert that the screenshot's model ID exists.

## Composer model placement

By user request, the current model selector lives inside the composer toolbar,
between Add (+) and the microphone. The chat header contains the chat title.
Verify that tapping the selector opens the model bottom sheet, that the current
chat selection persists, and that long names truncate without displacing the
microphone/send buttons on a small screen or with enlarged fonts.

The composer displays the model ID without its organization prefix (for example
`deepseek-v4.1-flash`), with the configured provider name on a smaller second
line. Full IDs remain unchanged in storage and requests. For legacy default
connection names, OpenRouter/OpenAI are identified from their exact known host;
custom connection names are respected.

## Provider catalog and Umnik menu

- Connections contain only provider name, API URL and key. Add a provider without
  selecting any model; it must appear in Settings → Models and the chat picker.
- Expand a provider: first use fetches GET /models from that same provider using
  its key. Later opens reuse the saved list; Refresh requests it explicitly.
- Verify full ID copy, favorites per provider, favorite-only filtering, search,
  chat-local selection and global default. Restart and check favorite/selection
  persistence. Offline/401/malformed responses must retain the saved catalog.
- A model missing after refresh stays visible but unavailable; its stable ID and
  favorite remain saved. The app must not silently substitute another model.
- Drawer: Projects is above history with an explicit future-feature explanation;
  New chat and Settings are on one fixed bottom row. Check long histories.
- Menu +: Umnik action tiles, compact mode toggles, expandable tools. Text file
  insertion and system dictation work; unavailable camera/image/agent/tools are
  disabled. Text import stays in the draft, is limited to UTF-8/64 KiB and never
  makes an automatic API request. Try denied access, binary and oversized files.
- Check a small screen, enlarged font and keyboard with both model and action
  sheets open. Successful CI does not constitute phone acceptance.

## Tool-selection layout follow-up

Check that expanding Tools shows Select all with Enable to its right on one row,
followed by the tool checkbox list. The duplicate dictation/Shell shortcut row
must be absent. Dictation through the composer microphone and the Shell catalog
entry remain available in their previous states. Verify small screens and large
font sizes; unavailable tools still cannot be enabled.

## Markdown, usage, capabilities and agent protection

1. Upgrade without clearing app data. Verify old chats and keys. SQLite version 2
   adds request_usage; no existing message table is recreated.
2. Ask for headings, bold/italic, lists, links, a quote, a Markdown table and a
   fenced code block. Verify copy returns code without Markdown fences. Long
   code/tables must scroll horizontally. Plain user messages remain plain text.
3. Refresh the provider's models to fetch capabilities. Check Reasoning/Tools and
   context/output limits in catalog rows. Reasoning is enabled only for supported
   protocol/metadata combinations. Toggle it per chat and select only advertised
   effort values; unknown models must not send invented parameters.
4. After completed streaming replies, inspect Chat → Statistics. Input/output,
   total, cached, reasoning tokens and cost come from provider usage. Missing
   values say no data. Restart and verify persisted totals. Cancel/retry/offline
   failures must not fabricate a zero cost or duplicate successful usage.
5. Agent mode requires advertised tools support, an installed executor, a global
   permission and per-chat selection. Production has no SSH/Shell/browser
   executors yet. Automated tests use a fake registered tool to prove normal tool
   rounds, revoked-permission rejection, repeated-cycle stopping and step limits.
6. Confirm malformed/error SSE never saves a partial assistant message as a
   successful completion. A completed earlier agent round can still have a
   reported charge even when a later tool/round fails; statistics retain it.
7. Recheck stop, clear/delete during generation, process termination and retry.
   Unknown interrupted charges remain unknown. No automatic paid retry occurs.

Full CommonMark compliance, syntax highlighting, external tool installation and
production device acceptance are not claimed by the unit test suite.
