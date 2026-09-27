---
title: Trasferire il sito da un altro hosting
category: sites
description: Importazione del sito da un link a un archivio o via FTP dal vecchio hosting.
---
## Che cos'è

La scheda «Importa» nella pagina del sito trasferisce il sito senza scaricarlo sul tuo computer: il pannello prende i file dal vecchio hosting e li pubblica. I file attuali del sito vengono sostituiti interamente, ma solo dopo che i nuovi sono stati ricevuti e controllati: in caso di errore il sito resta com'era.

## Da un link all'archivio

Va bene se il vecchio hosting sa creare un backup o se l'archivio è nel cloud. Incolla un link **diretto** a un file `.zip`, `.tar` o `.tar.gz` e premi «Avvia importazione». Se nell'archivio c'è un'unica cartella comune (per esempio `public_html/`), viene tolta automaticamente.

## Via FTP

1. Chiedi al vecchio hosting l'indirizzo del server FTP, l'utente e la password.
2. Indica la cartella del sito, quella con `index.html` o `index.php` (spesso `/public_html`, `/www` o `/htdocs`).
3. Lascia attiva la cifratura (FTPS); disattivala solo se il vecchio hosting non la supporta.

La password si usa solo durante il trasferimento e non viene salvata. Dopo il trasferimento conviene cambiarla sul vecchio hosting.

## Limiti

- Dimensione massima pari alla quota disco, fino a 20 000 file, fino a 15 minuti per importazione.
- I link simbolici non vengono trasferiti.
- Si può importare solo da server pubblici; per un sito è attiva una sola importazione alla volta.
- I database non vengono trasferiti: esporta un dump sul vecchio hosting e caricalo nella sezione [Database](/databases).
