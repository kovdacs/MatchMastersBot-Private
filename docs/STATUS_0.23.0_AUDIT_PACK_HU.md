# Állapot — 0.23.0-audit-pack (HU)

**Dátum:** 2026-09-28 (Europe/Vienna)

## Röviden

- PASS/HOLD küszöbök **változatlanok** (0.98 / 0.95 / unk≤1).
- Audit: teljes START→…→gesztus→ellenőrzés kapu dokumentálva.
- Új: `FrameSequenceGate` (gesztus után csak ÚJ képkocka), `GestureFailSafe`, buborék
  Vision HOLD részletek (unk, grid, board, boardDet, frameSeq, age, capture).
- Felhasználói fázisok: INDÍTÁS, TARTÁS, GESZTUS, ELLENŐRZÉS, SZÜNET, STOP, HIBA.
- CI: GitHub Actions (JDK a boxon tilos).

## Bizonyíték-szintek

Lásd `docs/EVIDENCE_TIERS.md` (A–E). **Ne keverjük.**

## Élő lépés?

**Nem állítható**, hogy a rendszer jelenleg valódi Match Masters live képből valid Move-ot
hoz létre **és** `dispatchGesture()`-rel végrehajtja. A megfigyelt élő jelek Vision HOLD
(unknownCount / grid) irányába mutatnak; E szintű telefon-bizonyíték nincs ebben a csomagban.

## Következő Vision feladat

Center-weighted színmintavétel + soft-GT confusion mátrix; gomba/+3 megjelenés után
SpecialType terv (`docs/MUSHROOM_SPECIAL_PLAN.md`).
