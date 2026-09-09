# Upstream reconciliation

Upstream: `https://github.com/element-hq/element-x-android.git`, branch `develop`.
Integration cutoff: `c623105da65bedc92b5783cb690add22fe0ba6e2`.
Previous published fork: `b16af6af6024fb2d4abb30ddfd1c458d9222d59f`.
Historical fork base: `d3f35cb90cc14333561a39bffbb7a02fc37a76b6`.

The integration is a history-preserving merge, not replay of a detached helper.
The historical maintenance experiment remains a separate local archive and is
not an ancestor or evidence for this product update.

## Retained and adapted seams

- EX-001: TextComposer -> ReasoningEndButton -> ReasoningSelector and
  reasoningEffortForDrag. Retain the existing 24dp activation and angular cutoffs,
  direction mapping and four efforts. Those numeric choices are legacy tuning,
  not new product requirements. Extract the actual popup content for previews,
  native Compose screenshots and label-bound checks. Use short section labels;
  retain full accessibility action labels. Keep the callback current during a drag.
- EX-002: MessageComposerView -> typed SendMessageWithReasoning event -> real
  MessageComposerPresenter -> sendAfterSettingReasoning on the active Timeline.
  Ordinary SendMessage remains a singleton. Command submission is awaited before
  text/reply submission. Preserve reply event and mention arguments. Document that
  Timeline success is not backend apply acknowledgement or atomic concurrency.
- EX-003: retain upstream's editing-first end-button branch, empty-edit send
  eligibility, Markdown IME callback, thread placeholder/state, and mention member
  refresh. Editing and voice routes have no reasoning gesture. Presenter tests
  cover selected normal/reply/thread/focused sends, suspended/failed commands,
  ordinary sends after selection, edit/caption immunity and slash-command routing.
- EX-004: preserve existing application ID and tracked debug signing identity.
  The debug artifact is not a production-signed release. Minimum fork base code
  is 20260902, with upstream ABI digits retained; version name adds `-reasoning.1`.
  Advance the base code for each distributed replacement, even on the same upstream.

All downstream endpoint diff files, including new tests/configuration and this
intent layer, are mapped by generated `inventory.json` in the evidence bundle.
The gate rejects omitted paths. Source/spec commits and hashes are sealed after
source review, not self-referentially inserted into this committed document.

## Explicit residual risks and gates

- Existing draft clearing occurs before send failure is known; no new retry/draft
  restoration semantics are introduced. Command failure prevents body submission,
  but does not establish a satisfactory recovery UX.
- Existing attachment-caption path is retained, not newly endorsed as protocol
  semantics. No reasoning is attached to uploaded media or voice messages.
- Two visible events are non-atomic. Concurrent sends may interleave; a server may
  accept a command without the bot applying it. Room/thread/session and supported
  effort behavior require an authorized backend canary.
- The circular display is a directional legend, not a new hit-test origin. Gesture
  tuning and RTL/large-font/device ergonomics remain explicit review considerations.
- Preview/Roborazzi images render actual Compose code but are not device screenshots
  or proof of login, end-to-end messaging, installation or in-place update.
- Only an isolated integration branch may be published for review. Promoting develop,
  a new immutable release, live messages or user-device testing require approval.
