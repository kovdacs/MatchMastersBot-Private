# Booster automation (designed, not enabled)

`app/src/main/assets/booster_db.json` is the sourced catalog (40 boosters, 4 powered-up skins, 14 perks, plus the legacy Paint Bucket row). `BoosterCatalog` reads it. `BoosterAutomation.plan` decides whether a later build may tap. `BoosterAutomation.ENABLED` is false, and `FloatingBubbleService` does not call the planner. The live path is still `SoloBooster`, which taps only the solo ACTIVATE button and only while the debug toggle is on.

`v1_safe` in the catalog is a research note. It does not verify a booster on this phone. Balloon Blast and Colonel McQuack appear in the owner's recordings; both stay unverified until an icon template matches.

## When a tap would be allowed

The planner returns a tap only when every line below holds. Otherwise it waits for the next frame, or it refuses and latches so the session must not tap again.

1. This session has not already tapped or latched.
2. The equipped id was recognized. A missing id waits for the next frame (`latch = false`) because the icon may be readable then. An id that is not in the catalog latches.
3. The catalog category is `NO_TARGET_TAP`. `TAP_THEN_PIECE`, `TAP_THEN_COLOR`, `DRAG`, `FOLLOW_UP_MATCH`, and `UNKNOWN` latch. Those need a target, a drag, or a follow-up match, and none of those points are calibrated.
4. The id is in the phone-verified set. That set is empty. A recording is not verification.
5. The card is the player's **left** card. The opponent's right card latches and is never a target.
6. The left card reads `ACTIVATE`. `n/7` with n under 7 waits. `FULL` and `7/7` wait for the word ACTIVATE. An unreadable card waits and does not latch.
7. The player has no remaining 4+ extra move. The caller passes that from the owner-confirmed rule: the swap's initial connected group of one color clears at least 4 gems. While one remains, the planner waits. Take those moves before activating.
8. A known precondition holds on a board with no unknown cell:
   - All Aboard (and the same yellow wording) needs a visible yellow piece. Missing yellow waits; the board can still change.
   - Detonator needs a visible special (`TWO_WAY_ARROW`, `LIGHTNING`, or `BOMB`). Missing special waits.
   - A target requirement that starts with `none` has no extra board check. Crazy Clovers is in this group.
   - Any other requirement latches. Doctor Color SE ("enough colors") is in this group. The planner does not guess.
9. The entry is a booster or a powered-up booster, not a perk. Perk buttons sit bottom-left and their tap point is not calibrated, so a perk latches even when its precondition holds. Detonator therefore never taps: without a special it waits, and with a special it refuses.

The tap carries a verification contract: the next stable PASS must show a changed board. `BoosterAutomation.settle` latches on every result. A miss, an unstable frame, or a change that is not a stable PASS means do not tap again. A verified change also means do not tap again. One attempt.

An unknown cell waits. The planner will not treat an unread cell as yellow, as a special, or as proof that no extra move is hiding there.

## What is still missing before this can be turned on

Identity is not read from pixels. The left-card word reader already distinguishes `n/7`, `FULL`, and `ACTIVATE` inside x 30–360, y 860–1010 on a 1080×2400 frame. It does not know which picture is equipped. Until templates exist, `identity` stays null and the planner refuses.

Please send crops from the phone, rotation 0, 1080×2400:

- One full screenshot with the board visible, the **left** card visible, and no activation animation, perk popup, or banner covering the icon. That frame is how the icon rectangle gets measured. Do not crop the opponent's right card as a substitute.
- A tight PNG of the left-card **icon only**: not the ACTIVATE button, not the `n/7` or FULL text, not the charge bar. Filename `booster_icon_<id>.png`, using the id from `booster_db.json` (for example `booster_icon_balloon_blast.png`).
- Powered-up "electrifying" skins are separate ids. The four that share a slot with a base booster each need their own crop: `billie_boom` and `billie_boom_powered_up`, `vinnie_valentine_se` and `vinnie_valentine_se_powered_up`, `ufo_se` and `ufo_se_powered_up`, `foxy_roxy_se` and `foxy_roxy_se_powered_up`.
- Quack Attack and Colonel McQuack are both ducks. Send both icons or they will be confused: `booster_icon_quack_attack.png` and `booster_icon_colonel_mcquack.png`.
- Perk icons are the separate bottom-left buttons, not the player card. There are 14: hammer, shuffle, teleport, remove_cross, mini_cobra, mini_cleo, energy_boost, star_maker, create_lightning, bomb_delivery, detonator, mini_wand, mini_mastermind, checkerboard. If the inactive picture and the ready picture differ, send both as `booster_icon_<id>.png` and `booster_icon_<id>_ready.png`.

The equipped booster is the one that unblocks a later build. The others can arrive as they are used. Do not crop during the activation animation.
