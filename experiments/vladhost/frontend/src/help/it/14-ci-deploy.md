---
title: Deploy dalla CI con un token API
category: sites
description: Pubblicazione automatica del sito da GitHub Actions, GitLab CI e altri sistemi.
---
## Che cos'è

Un token API è una chiave con cui la build nella CI pubblica il sito da sola dopo ogni push. Il token può solo caricare un archivio sul sito: non permette di accedere al pannello, vedere i file o cambiare la password. Il codice di accesso (2FA) non serve.

## Creare un token

1. Apri [Impostazioni](/settings) → «Token API» e premi «+».
2. Indica un nome (per esempio «GitHub Actions»), scegli il sito e la validità. Un token per un solo sito è più sicuro: se trapela, riguarda solo quel sito.
3. Copia il token: **viene mostrato una sola volta**. Salvalo nei segreti della CI con il nome `VLADHOST_TOKEN`.

Nell'elenco si vede quando e da quale indirizzo il token è stato usato l'ultima volta. Un token inutile o compromesso si revoca con «Revoca»: smette di funzionare subito. Ogni deploy con un token viene registrato nel [Registro attività](/activity).

## La richiesta

Il deploy è una sola richiesta POST con un archivio zip, come il caricamento nel pannello: la cartella del sito viene sostituita interamente dal contenuto dell'archivio.

```
cd dist && zip -qr ../site.zip . && cd ..
curl -fsS -H "Authorization: Bearer $VLADHOST_TOKEN" \
  -F file=@site.zip "https://app.vladinc.ru/api/ci/deploy?site=blog"
```

Il parametro `site` è il nome del sito (`blog`) o il suo indirizzo (`blog.ivan.vladinc.ru`). Per un token di un solo sito si può omettere. La risposta contiene i dati del sito; in caso di errore il codice non è 200 e il testo è nel campo `error.message`.

## GitHub Actions

File `.github/workflows/deploy.yml`:

```
name: deploy
on:
  push:
    branches: [main]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: npm ci && npm run build
      - name: Pubblica su Vladhost
        env:
          VLADHOST_TOKEN: ${{ secrets.VLADHOST_TOKEN }}
        run: |
          cd dist && zip -qr ../site.zip . && cd ..
          curl -fsS -H "Authorization: Bearer $VLADHOST_TOKEN" \
            -F file=@site.zip "https://app.vladinc.ru/api/ci/deploy"
```

Aggiungi il token nel repository: Settings → Secrets and variables → Actions → New repository secret.

## Il comando vladhost deploy

Se nella CI è disponibile il programma `vladhost`, non serve preparare l'archivio: impacchetta la cartella da solo.

```
VLADHOST_TOKEN=vht_… vladhost deploy --site blog ./dist
```

Il token si legge solo dalla variabile `VLADHOST_TOKEN`, così non finisce nel log della build.

## Limiti

Valgono le stesse regole del caricamento nel pannello: l'archivio non può superare la quota disco, link simbolici e file di servizio non vengono pubblicati. Fino a 20 token per account, validità fino a un anno o senza scadenza. Il numero di richieste da uno stesso indirizzo è limitato.
