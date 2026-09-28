# Státusz — 0.23.1 Vision Stabilization

**Dátum:** 2026-09-28 (Europe/Vienna)

## Mit csináltunk

- Középpont-súlyozott színfelismerés (7×7 cellák centruma, RGB+HSV+fényesség).
- Medián / robust mintavétel — szélek, overlay, háttér kevésbé torzít.
- Soft-GT confusion matrix a `pvp_board.jpg`-re.
- UNKNOWN okok: `color | shape | special | occlusion | geometry/grid`.
- Kapuk **nem** lazultak (0.98 / 0.95 / unk≤1).

## Mit nem

- Nincs élő telefon (E-tier) bizonyíték → **ne** állítsuk, hogy live gesture megy.
- Mushroom/+3 nincs implementálva — címkézett cropok kellenek (`docs/MUSHROOM_SPECIAL_PLAN.md`).
