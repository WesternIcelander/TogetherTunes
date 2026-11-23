// One window with sound per person (docs/ui.md#alone-and-in-rooms "One window with sound per
// person"): the DOM-free rules for which window loads the media and what the overlay over the
// video area says. listen.ts (the player) and room.ts (the room socket) apply them.
//
// A window is in one of three states:
// - "here": this window plays (loads the media, owns the system media controls);
// - "elsewhere": another window of the same person plays; this one loads no media at all and the
//   video area shows the item's card with "Move player here", whose click takes the player over;
// - "unknown": the room socket hasn't said yet (between the join's state and its `sound` message);
//   nothing is loaded meanwhile, so a window that turns out not to have the sound makes no media
//   request. Anonymous visitors and windows without a window id never take part: always "here".

/** Where the person's sound plays, as this window knows it. */
export type SoundHolder = "unknown" | "here" | "elsewhere";

export const MOVE_PLAYER_TEXT = "Move player here";
export const ENABLE_SOUND_TEXT = "Click to enable sound";
export const RESTORE_SOUND_TEXT = "Tap to restore sound";

/** Before the room socket has said anything: wait for its `sound` message, unless there is no window id. */
export function initialSoundHolder(windowId: string | undefined): SoundHolder {
  return windowId === undefined ? "here" : "unknown";
}

/**
 * The socket's identity (sent before every join's state). An anonymous socket gets no `sound`
 * messages and always plays; a user or guest keeps what it knew (its `sound` message follows).
 */
export function soundHolderOnIdentity(holder: SoundHolder, anonymous: boolean): SoundHolder {
  return anonymous ? "here" : holder;
}

/** A `sound {holder}` message (`holder` false also for `sound.released`). */
export function soundHolderOnMessage(holder: boolean): SoundHolder {
  return holder ? "here" : "elsewhere";
}

/** Whether the player may load media (set sources, preload, watch health) in this state. */
export function loadsMedia(holder: SoundHolder): boolean {
  return holder === "here";
}

/** What the player knows when it draws the overlay. */
export interface OverlayInput {
  holder: SoundHolder;
  /** The room has a current entry that is ready to play (whatever the timeline's state). */
  hasEntry: boolean;
  /** An element plays the current entry and the timeline plays. */
  playing: boolean;
  /** The browser blocks sound (autoplay) or the audio context doesn't run. */
  locked: boolean;
  /** The Web Audio path went silent: "Tap to restore sound". */
  needsRestore: boolean;
}

/** Whether the overlay shows, and its button's text. */
export interface OverlayPlan {
  shown: boolean;
  text: string;
}

/**
 * The overlay over the video area. Another window plays: "Move player here" whenever there is
 * something to play (also when the browser blocks sound: its click unlocks too). This window plays:
 * "Click to enable sound" / "Tap to restore sound" while the entry plays and sound is locked.
 * Unknown: nothing.
 */
export function overlayPlan(i: OverlayInput): OverlayPlan {
  if (i.holder === "elsewhere") return { shown: i.hasEntry, text: MOVE_PLAYER_TEXT };
  if (i.holder === "unknown") return { shown: false, text: ENABLE_SOUND_TEXT };
  return { shown: i.playing && (i.locked || i.needsRestore), text: i.needsRestore ? RESTORE_SOUND_TEXT : ENABLE_SOUND_TEXT };
}

/** What the overlay's click does, in this order, all inside the gesture. */
export interface ClickPlan {
  /** Send `sound.claim` and take the player over (this window becomes "here" at once). */
  claim: boolean;
  /** Then: rebuild without Web Audio ("Tap to restore sound"), or the usual unlock (load, seek, play). */
  then: "restore" | "unlock";
}

export function overlayClickPlan(holder: SoundHolder, needsRestore: boolean): ClickPlan {
  return { claim: holder === "elsewhere", then: needsRestore ? "restore" : "unlock" };
}
