# AGENTS.md

Közös munka: Grok + ChatGPT + tulajdonos (kovdacs).
A két AI nem látja egymás chatjét. A közös csatorna ez a repo.

Repo: https://github.com/kovdacs/MatchMastersBot-Private
Branch: main

## Szerepek

### Grok
- Kotlin / Android kód, architektúra
- fájlok írása, ha a GitHub-csatlakozó írni tud
- státusz: PROJECT_STATUS.md

### ChatGPT
- dokumentáció, átnézés, tesztterv
- issue-k írása
- kód review szövegben

### Tulajdonos
- merge, APK, telefonos teszt
- token nála marad, chatbe nem kerül

## Hol hagyunk üzenetet

1. GitHub Issue
2. PROJECT_STATUS.md
3. rövid komment a fájl tetején

Issue cím: [Grok] ... / [ChatGPT] ... / [Owner] ...

## Szabályok

- Repo-ba tilos token, jelszó, PAT, API kulcs.
- Előbb olvasd el a fájlt, ne írd felül vakon.
- Nagyobb munka: külön branch + PR.
