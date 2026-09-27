---
title: Posta sul proprio dominio
category: mail
description: Caselle, inoltro, risposta automatica, configurazione del programma di posta e webmail.
---
## Cosa serve

La posta funziona solo per i **domini personali**: il dominio si aggiunge direttamente nella sezione «Posta», non serve collegarlo a un sito, ma bisogna confermarne la proprietà. Gli indirizzi su `vladinc.ru` non vengono creati.

## Configurazione

1. Nella sezione [Posta](/mail) premi «+» accanto al titolo «Domini», inserisci il dominio e premi «Attiva la posta». Se il dominio è collegato al tuo sito, la sua zona è già nella sezione «DNS» o presso il registrar sono già indicati `ns.vladinc.ru` e `ns2.vladinc.ru`, è tutto pronto subito. Altrimenti crea il record TXT `_vladhost-verify` mostrato dal pannello presso l'attuale provider DNS e premi «Verifica»: fino alla conferma la posta del dominio non funziona.
2. Configura i record DNS del dominio: MX (dove arriva la posta), SPF (chi può inviare), DKIM (firma dei messaggi) e DMARC (politica). Il pannello mostra i valori esatti e li verifica con il pulsante «Controlla il DNS».
3. Se il dominio è sui nostri name server (vedi «DNS proprio»), i record si impostano con un solo pulsante «Configura il DNS automaticamente».
4. Crea una casella. La password può essere scelta da te o generata: viene mostrata una sola volta.

> Senza MX, SPF e DKIM corretti i messaggi non arrivano o finiscono nello spam dei destinatari.

## Lettura e invio

- **Webmail:** pulsante «Apri il webmail» nella sezione «Posta»; accesso con l'indirizzo completo e la password della casella.
- **Programma di posta:** nome utente — indirizzo completo; in entrata IMAP (porta 993, SSL/TLS) o POP3 (995); in uscita SMTP (porta 465 SSL/TLS o 587 STARTTLS) con autenticazione. La cifratura è obbligatoria.
- **Mittente.** Da una casella si può inviare solo dall'indirizzo del proprio dominio, al massimo 300 messaggi all'ora.

## Inoltro e risposta automatica

Nella finestra «Modifica» della casella ci sono le schede «Risposta automatica» (oggetto, testo, date, al massimo una risposta allo stesso indirizzo nel numero di giorni impostato) e «Inoltro» (fino a cinque indirizzi, con copia nella casella o senza). La casella generale del dominio si crea con un alias di nome `*`.

## Spam e virus

Lo spam finisce nella cartella «Spam». Se sposti un messaggio in «Spam» il filtro lo impara come spam; se lo togli da lì, come normale. Lo spam molto evidente viene rifiutato già alla ricezione.

## Registro e dimensione

Nella scheda del dominio ci sono il registro di consegna e la coda di invio. Quando la casella è piena al 90 % arriva un avviso via email. La dimensione della casella (50–2000 MB) si cambia nella finestra «Modifica».

## Limiti

Fino a cinque domini, dieci caselle e venti alias per account. Con l'inoltro tramite alias il dominio di origine con una politica DMARC rigida può rifiutare il messaggio.
