---
title: Monitoraggio e pagina di stato
category: sites
description: Controllo della disponibilità del sito, email sulle interruzioni e pagina di stato pubblica.
---
## Che cos'è

La scheda «Monitoraggio» nella pagina del sito attiva il controllo della disponibilità: ogni 2 minuti il pannello apre il sito via https e registra il codice di risposta e il tempo. Una risposta con codice inferiore a 400 (reindirizzamenti inclusi) indica che il sito funziona.

## Interruzioni ed email

Se il sito non risponde per **due controlli di seguito**, inizia un'interruzione e arriva un'email con la causa: errore del server (codice 5xx o 4xx), nessuna risposta in 15 secondi, errore del certificato o di connessione. Quando il sito torna a funzionare arriva una seconda email con la durata dell'interruzione. Le email arrivano se l'indirizzo è confermato, le notifiche sono attive nelle [Impostazioni](/settings) e nel monitoraggio è attiva l'opzione «Email in caso di caduta e ripristino».

## Cosa controllare

Per impostazione predefinita si controlla la pagina principale. Per un sito PHP o Node conviene indicare un percorso dedicato che verifica che l'applicazione sia viva, per esempio `/health`: così il monitoraggio si accorge anche di un database fermo, se la pagina lo interroga.

## Riepilogo

Nella scheda si vedono la disponibilità in 24 ore, 7 e 30 giorni, la barra per giorno (passa sopra un giorno per vedere percentuale e numero di controlli), il tempo medio di risposta e l'elenco delle interruzioni degli ultimi 90 giorni.

## Pagina di stato

Attiva «Pagina di stato pubblica»: all'indirizzo `/status/indirizzo-del-sito` del pannello (per esempio `https://app.vladinc.ru/status/blog.ivan.vladinc.ru`) chiunque potrà vedere lo stato del sito senza accedere. Contiene solo disponibilità, tempo di risposta e interruzioni: né impostazioni né percorso di controllo.

## Limiti

Il controllo parte dallo stesso server su cui gira il sito. Rileva un'applicazione ferma, errori, un certificato scaduto e una configurazione rotta, ma non un guasto di rete dell'intero server: per questo serve un servizio di monitoraggio esterno.
