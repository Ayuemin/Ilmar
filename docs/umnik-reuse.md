# Components reused from Umnik

Owner-authorized adaptation, 2026-10-20. Source: https://github.com/Ayuemin/Umnik

- `ui/YmnikApp.kt`: `ComposerActionTile`, `ComposerToggleTile`,
  `ComposerSectionHeader`, `CompactComposerTool` moved into Ilmar's
  `chat/ComposerMenuComponents.kt`. Purpose: reuse the existing actions/compact
  toggles/expandable-tools interface without importing the large chat screen.
  Local shapes/theme were substituted; no Umnik runtime dependencies were added.
- `network/OpenRouterCatalogClient.kt`: the GET `/models` catalogue pattern (including OpenRouter `output_modalities=all`) was
  adapted to Ilmar's provider connections. Purpose: retrieve real model IDs,
  without importing Umnik's multimodal parser, Gson model graph or diagnostics.
- The action-sheet composition and file-picker/dictation wiring follow Umnik's
  chat flow. Only UTF-8 text attachments are enabled in Ilmar (64 KiB maximum),
  appended to the editable draft and never sent automatically. Camera and image
  generation remain visibly unavailable. Agent/search/reasoning are unavailable
  until their execution capabilities exist; switches cannot grant permissions.

Ilmar-specific cache/favorites and provider grouping live separately in
`ProviderRegistry`, `ModelCatalogClient`, `ModelCatalogViewModel` and
`ui/ModelCatalogBrowser`. No changes are made to the Umnik repository.
