---
title: DNS proprio: name server e record
category: dns
description: Come spostare un dominio su ns.vladinc.ru e ns2.vladinc.ru e gestire i record nel pannello.
---
## A cosa serve

Se il dominio è gestito dai nostri name server, i record del sito e della posta si modificano nel pannello e si applicano subito, e per la posta compare il pulsante di configurazione automatica.

## Come collegarlo

1. Nella sezione [DNS](/dns) premi «+» accanto al titolo «Zone», inserisci il tuo dominio e premi «Crea la zona». Il dominio può essere qualsiasi: se è già collegato al tuo sito o presso il registrar sono già indicati `ns.vladinc.ru` e `ns2.vladinc.ru`, la zona funziona subito (poteva delegarci il dominio solo il proprietario, quindi il record TXT non serve), altrimenti il pannello mostra un record TXT `_vladhost-verify` con un codice. Crealo presso l'attuale provider DNS del dominio e premi «Verifica»: dopo la conferma della proprietà la zona inizia a essere servita. Compaiono subito i record del sito: indirizzo del server e `www`.
2. Presso il registrar del dominio sostituisci i name server (NS) con i due nomi indicati nella sezione DNS: `ns.vladinc.ru` e `ns2.vladinc.ru`. Entrambi puntano allo stesso server.
3. Attendi l'aggiornamento (da pochi minuti a un giorno) e premi «Controlla la delega».

Risultato del controllo:

- **il dominio è sui nostri name server** — tutto pronto;
- **è indicato solo uno dei nostri** — funziona, ma è meglio indicarli entrambi;
- **insieme ai nostri ce ne sono di altri** — parte delle richieste andrà altrove, lascia solo i nostri;
- **non ancora sui nostri** — il registrar non ha ancora aggiornato gli NS.

## Record

Sono supportati A, AAAA, CNAME, MX, TXT, SRV e CAA. Il nome `@` è il dominio stesso, `*.` all'inizio indica qualsiasi sottodominio. Le regole del DNS le controlla il pannello: ad esempio un CNAME non si può mettere sul dominio stesso né accanto ad altri record con lo stesso nome.

> Finché il dominio non è spostato sui nostri name server, i record di questa sezione non influiscono sul suo funzionamento: il DNS continua a essere gestito dal provider precedente.

## Limiti

Zone: fino a cinque per account, record: fino a cento per zona. I due name server si trovano sullo stesso IP; DNSSEC non è supportato.
