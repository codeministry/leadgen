Die App macht aus einem Strom von Projektangeboten eine kurze Liste, die sich zu lesen lohnt, und, wenn du dich bewerben willst, ein fertiges Bewerbungspaket. Bewerben tut sie sich nie für dich: Sie liest, sortiert, bewertet und bereitet vor. Jeder Schritt nach draußen bleibt deiner.

## Ein Lauf, fünf Phasen

Ein Lauf beginnt, wenn du **Quellen einlesen** drückst, oder zeitgesteuert. Er geht jedes Mal dieselben Stufen in derselben Reihenfolge durch, gebündelt in fünf Phasen.

<!-- diagram: run-phases -->

**Lesen.** Jede aktive Quelle wird einmal gelesen: ein Newsletter-Postfach, ein Ordner mit Markdown-Dateien oder was sonst konfiguriert ist. Jedes Dokument wird in einzelne Angebote zerlegt, und jedes Angebot bekommt Titel, Link, Ort, Satz und alles Weitere, was die Quelle nennt. Die App merkt sich, was sie schon gesehen hat, ein zweiter Lauf legt also keine zweite Kopie an.

**Sortieren.** Zuerst die Deduplizierung: Dasselbe Projekt, von zwei Agenturen oder auf zwei Portalen ausgeschrieben, wird zu einem Eintrag, der alle Fundstellen nennt. Exakt gleiche Angebote werden sofort zusammengelegt, nur ähnlich klingende werden nach ihrer Bedeutung verglichen und als mögliche Dublette markiert. Danach läuft der harte Filter. Seine Stufen sind feste Regeln – Ausland, Remote-Anteil, außer Reichweite, Rolle oder Stack, keine Kernkompetenz, Vertragsform –, und ein Angebot bleibt an der ersten hängen, die es nicht besteht. Das ist das Prinzip **Regeln vor Modell**: Der Filter ist deterministisch und kostet nichts, er sortiert den Großteil aus, und nur was übrig bleibt, beschäftigt überhaupt ein Modell. Zum Schluss wandern Angebote, deren Anzeige älter als das Frische-Fenster ist, ins Archiv. Von dort holst du sie jederzeit zurück.

**Verstehen.** Nur die Überlebenden werden genauer angeschaut. Die App holt die vollständige Anzeige vom Portal – die einzige Stufe, die deinen Rechner verlässt –, denn ein Newsletter enthält nur einen Anriss, nicht die Anzeige. Dann trennt sie die eigentliche Anzeige vom Drumherum des Portals (Navigation, Formulare, Rechtliches, Agentur-Signaturen), damit wirklich die Anzeige bewertet wird, und liest Starttermin, Dauer und Bewerbungsfrist aus dem Text.

**Bewerten.** Jedes Angebot bekommt einen Score bis 100. Regel-Faktoren erledigen alles, was Regeln allein entscheiden können: Überschneidung mit deinen Skills, Satz, Seniorität, wie viel die Anzeige über den Einsatz verrät, Branche. Ein Sprachmodell wird nur gefragt, was Regeln nicht beantworten können: ob die Rolle wirklich zu dir passt, dazu ein paar Abzüge. Die Summe ist ein Anteil dessen, was die Anzeige überhaupt erreichbar macht – eine Anzeige wird also nach dem bewertet, was sie sagt, und nicht für das bestraft, was sie weglässt. Der Score ordnet das Angebot in einen der Bereiche ein, die du aus der Übersicht kennst: **Shortlist**, **zu prüfen** oder **verworfen**. Ohne Modell läuft der Lauf trotzdem durch; die Angebote behalten ihre Regel-Faktoren und gelten als **unbewertet**. Nach der Bewertung wird jede Anzeige nach ihrer Bedeutung indiziert, und genau das durchsucht **Ähnliche finden**.

**Übergeben.** Was es in die Auswahl geschafft hat, eröffnet auf dem Board eine Bewerbung im Status **New**. Außerdem schreibt der Lauf die Tageszusammenfassung, eine lesbare Übersicht über das, was hereinkam.

## Wo ein Modell mitarbeitet

An einer Handvoll Stellen hilft ein Modell, und unter Regeln ist jede davon als **KI-Schritt** markiert: Angebote nach Bedeutung vergleichen, für die Deduplizierung und die Ähnlichkeitssuche; die Anzeige vom Drumherum des Portals trennen; Termine und Dauer aus freiem Text lesen; die Passung der Rolle beurteilen; und das Anschreiben entwerfen. All das läuft auf einem lokalen Modell auf deinem eigenen Rechner – kostenlos, und nichts geht an Dritte. Ein gehostetes Modell kommt nur zum Einsatz, wenn du es für einen bestimmten Lauf ausdrücklich wählst; von sich aus weicht die App nie darauf aus. Und weil die Regeln zuerst kommen, funktioniert die App auch ganz ohne Modell, nur weniger scharf: Es fällt nichts weg, die Angebote werden nur nicht gereiht.

## Wie die Teile zusammenspielen

<!-- diagram: parts -->

Die Quellen liefern das Rohmaterial. Die Pipeline liest, sortiert, versteht und bewertet und fragt das lokale Modell, wo sie eines braucht. Alles, was sie herausfindet, landet in einer Datenbank: die Angebote, ihre Scores und Begründungen, die Bewerbungen und ihr Verlauf. Die Bildschirme lesen aus dieser Datenbank, und zurückschreiben tust du nur deine eigenen Entscheidungen: ein Archivieren, einen Status, eine Notiz, ein Anschreiben. Die Konfiguration – Quellen, Regeln, Profil, Texte fürs Anschreiben – liest die Pipeline, und die Bildschirme Quellen und Regeln zeigen sie an. Geändert wird sie aber nie aus der App heraus.

## Der Weg einer Bewerbung

<!-- diagram: application-states -->

Eine Bewerbung entsteht im Status **New**, sobald ihr Angebot in die Auswahl kommt; mit **Shortlisted** markierst du eine, die du verfolgen willst. Setzt du sie auf **Packaged**, wird das Paket gebaut: ein Ordner mit dem Anschreiben, dem festen Lebenslauf in der Sprache der Anzeige und den passenden Referenzprojekten. Das ist der eine Schritt, den das Board dich nicht überspringen lässt, denn eine gesendete Bewerbung ohne Paket stünde für ein Dokument, das nie jemand erstellt hat.

Ab da ist alles dein eigenes Protokoll. Du sendest die Bewerbung aus deinem eigenen Mailprogramm und setzt sie auf **Sent**, dann weiter über **Replied**, **Interview** und **Offer** bis zu **Won**, **Lost**, **Rejected** oder **Expired**. Die App versendet nie etwas, deshalb weiß sie davon nur, was du einträgst – und deshalb wird nach dem Paket auch kein Wechsel abgelehnt, ein Fehler ist also immer mit einem Klick korrigiert.

Archivierst du ein Angebot, wird sein Paket verworfen, außer die Bewerbung wurde je gesendet; stellst du es wieder her, beginnt die Bewerbung wieder bei **New**. So bedeuten „Packaged“ und „es gibt einen Ordner“ immer dasselbe.
