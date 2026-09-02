/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Writes the demo corpus: newsletter mails carrying invented project offers.
 *
 * Why a generator rather than a folder of hand-written files. The corpus has to hit
 * every stage of the pipeline hard enough to be worth looking at — one project
 * advertised by three portals so deduplication has something to collapse, offers
 * abroad and offers out of reach so each filter stage has a non-zero count, a share of
 * offers with no agency so the emoji-prefix rule is exercised, and enough survivors
 * that the shortlist fills a screen. Those proportions are a specification, and a
 * specification belongs in code where it can be changed and re-run.
 *
 * Nothing here is real. Every portal, agency, city, title and sentence is invented,
 * and that is the point: the screenshots in the README are published, and the ads this
 * tool actually reads are not ours to publish.
 *
 *     bun demo/generate-corpus.ts            # dated so the newest mail is today
 *     bun demo/generate-corpus.ts --seed 7   # a different draw, still reproducible
 *
 * The output goes through the same `html-blocks` extraction the shipped
 * `sample-newsletter` source declares, so the demo exercises the real path rather than
 * a fixture written to match it.
 */

import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// ---------------------------------------------------------------------------
// A seeded generator, because "regenerate the corpus" must not mean "every
// screenshot in the README is now stale".
// ---------------------------------------------------------------------------

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const args = process.argv.slice(2);
const seed = Number(valueOf('--seed') ?? 20260902);
const mailCount = Number(valueOf('--mails') ?? 5);
const outputDir = valueOf('--out') ?? join(import.meta.dir, 'corpus');
const random = mulberry32(seed);

function valueOf(flag: string): string | undefined {
  const index = args.indexOf(flag);
  return index >= 0 ? args[index + 1] : undefined;
}

function pick<T>(items: readonly T[]): T {
  return items[Math.floor(random() * items.length)] as T;
}

function chance(probability: number): boolean {
  return random() < probability;
}

function shuffle<T>(items: T[]): T[] {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [copy[i], copy[j]] = [copy[j] as T, copy[i] as T];
  }
  return copy;
}

// ---------------------------------------------------------------------------
// The invented market.
// ---------------------------------------------------------------------------

/** Portals, as a slug — that is how the source states them and how they are grouped. */
const PORTALS = ['portal-a', 'portal-b', 'portal-c', 'portal-d', 'portal-e', 'portal-f'] as const;

/** Agencies. Deliberately unmistakable placeholders, not plausible company names. */
const AGENCIES = [
  'Acme Consulting GmbH',
  'Initech Personal AG',
  'Globex Solutions GmbH',
  'Contoso Services GmbH',
  'Umbrella IT Partner GmbH',
  'Soylent Digital GmbH',
  'Hooli Engineering AG',
  'Vandelay Industries GmbH',
] as const;

/** Reachable from the demo profile's base, so the OUT_OF_REACH stage lets these through. */
const NEAR_CITIES = ['Köln', 'Düsseldorf', 'Bonn', 'Aachen', 'Leverkusen', 'Neuss'] as const;
/** Domestic but far away — the reason the reach rule exists. */
const FAR_CITIES = ['München', 'Hamburg', 'Berlin', 'Dresden', 'Leipzig', 'Stuttgart'] as const;
/** Abroad. The first filter stage, and the cheapest rejection there is. */
const ABROAD_CITIES = ['Zürich', 'Wien', 'Basel', 'Amsterdam', 'Warschau', 'Madrid'] as const;

/**
 * The tag groups a newsletter sorts its offers into. The header is the search that
 * found them, which is why it becomes the offer's tags and why it is split on "+".
 */
const TAG_GROUPS = [
  'Java + Spring Boot',
  'Angular + TypeScript',
  'Kubernetes + DevOps',
  'Microservices + Kafka',
  'SAP + ABAP',
  'Salesforce + CRM',
  'Embedded + C',
  'Data Engineering + Python',
] as const;

type Shape = {
  /** Which tag group this kind of offer is advertised under. */
  group: (typeof TAG_GROUPS)[number];
  titles: readonly string[];
  /**
   * The stack a portal appends after a dash. A third axis beside title and domain, and
   * it is here for a measured reason: with two axes, 172 offers drew only 111 distinct
   * titles, so a third of the corpus collapsed into duplicate clusters it was never
   * meant to be in — deduplication fingerprints the normalized title and nothing else.
   */
  tails: readonly string[];
  /** The paragraphs and bullet points the ad is built from. */
  intro: readonly string[];
  tasks: readonly string[];
  skills: readonly string[];
};

const SHAPES: readonly Shape[] = [
  {
    group: 'Java + Spring Boot',
    tails: ['Spring Boot / Kafka', 'REST-API', 'Microservices', 'Spring Cloud', 'PostgreSQL'],
    titles: [
      'Senior Java Entwickler',
      'Java Backend Entwickler',
      'Softwarearchitekt Java',
      'Senior Backend Engineer Java',
      'Java Entwickler Spring Boot',
    ],
    intro: [
      'Für den Ausbau einer bestehenden Handelsplattform suchen wir Verstärkung im Backend-Team.',
      'Unser Kunde löst einen über Jahre gewachsenen Monolithen schrittweise in Services auf.',
      'Im Rahmen einer Produktneuentwicklung entsteht eine API-Landschaft auf der grünen Wiese.',
    ],
    tasks: [
      'Entwurf und Umsetzung von REST-Schnittstellen',
      'Ablösung bestehender Module in eigenständige Services',
      'Code-Reviews und fachliche Abstimmung mit den Produktteams',
      'Aufbau und Pflege der automatisierten Testabdeckung',
    ],
    skills: [
      'Java 21, Spring Boot, Spring Data',
      'PostgreSQL und saubere Datenmodellierung',
      'Erfahrung mit Kafka oder einem vergleichbaren Broker',
      'Docker, Gradle, Git',
    ],
  },
  {
    group: 'Angular + TypeScript',
    tails: ['Angular 18', 'TypeScript / RxJS', 'Designsystem', 'PWA'],
    titles: [
      'Angular Entwickler',
      'Frontend Entwickler Angular',
      'Senior Frontend Engineer',
      'Fullstack Entwickler Angular / Java',
    ],
    intro: [
      'Für ein internes Verwaltungsportal wird die Oberfläche von Grund auf neu gebaut.',
      'Eine bestehende Angular-Anwendung wird auf die aktuelle Major-Version gehoben.',
      'Das Produktteam sucht Unterstützung bei einer datenintensiven Auswertungsoberfläche.',
    ],
    tasks: [
      'Umsetzung neuer Oberflächen nach bestehendem Designsystem',
      'Migration auf Standalone-Komponenten und Signals',
      'Abstimmung der Schnittstellen mit dem Backend-Team',
      'Barrierefreiheit und Testabdeckung im Frontend',
    ],
    skills: [
      'Angular ab Version 17, TypeScript',
      'RxJS und ein Verständnis für State Management',
      'CSS ohne Framework-Abhängigkeit',
      'Erfahrung mit Vitest oder Jest',
    ],
  },
  {
    // The one shape that names every core skill of the demo profile at once. Without it
    // nothing reaches the shortlist threshold: core-skill overlap is 45 of the 100 points
    // and it is proportional, so an ad naming three of four core skills tops out in the
    // fifties once rate and project setup are missing — which they are, because both come
    // from enrichment and the invented URLs do not resolve.
    group: 'Java + Spring Boot',
    tails: ['Java / Angular', 'Fullstack', 'Spring Boot / Angular', 'Java / Angular / Kubernetes'],
    titles: [
      'Senior Fullstack Entwickler Java und Angular',
      'Fullstack Engineer Java / Angular',
      'Senior Softwareentwickler Fullstack',
      'Fullstack Entwickler Spring Boot und Angular',
    ],
    intro: [
      'Ein Bestandssystem wird vom Backend bis zur Oberfläche modernisiert; gesucht wird jemand, der beide Enden übernimmt.',
      'Für eine Neuentwicklung wird ein Team aufgebaut, das Service und Oberfläche gemeinsam verantwortet.',
      'Die Plattform wird um mehrere Module erweitert, jeweils vom REST-Endpunkt bis zur fertigen Maske.',
    ],
    tasks: [
      'Umsetzung neuer Module von der Datenbank bis zur Oberfläche',
      'Entwurf und Umsetzung der REST-Schnittstellen',
      'Betrieb der Services im Cluster mitverantworten',
      'Code-Reviews über beide Ebenen',
    ],
    skills: [
      'Java 21 und Spring Boot, sicher im Umgang mit Spring Data',
      'Angular ab Version 17 und TypeScript',
      'Kubernetes und Helm, mindestens von der Anwendungsseite',
      'PostgreSQL, Docker, Kafka von Vorteil',
    ],
  },
  {
    group: 'Kubernetes + DevOps',
    tails: ['Kubernetes / Helm', 'GitOps', 'Observability', 'Cluster-Migration'],
    titles: [
      'DevOps Engineer Kubernetes',
      'Platform Engineer',
      'Cloud Engineer Kubernetes',
      'Site Reliability Engineer',
    ],
    intro: [
      'Der Betrieb einer wachsenden Serviceplattform soll auf eine tragfähige Basis gestellt werden.',
      'Für die Migration von virtuellen Maschinen nach Kubernetes wird Verstärkung gesucht.',
      'Eine bestehende Cluster-Landschaft soll um Observability und GitOps ergänzt werden.',
    ],
    tasks: [
      'Aufbau und Pflege von Helm-Charts',
      'Einführung eines GitOps-Workflows',
      'Monitoring, Alerting und Logging',
      'Härtung der Cluster und Rechteverwaltung',
    ],
    skills: [
      'Kubernetes im produktiven Betrieb',
      'Helm, ArgoCD oder Flux',
      'Terraform oder ein vergleichbares Werkzeug',
      'Grundlagen Java, um Anwendungen sinnvoll betreiben zu können',
    ],
  },
  {
    group: 'Microservices + Kafka',
    tails: ['Kafka', 'Event Sourcing', 'Integrationsschicht', 'Spring Boot'],
    titles: [
      'Senior Entwickler Microservices',
      'Backend Entwickler Event Streaming',
      'Softwareentwickler Kafka',
      'Integrationsarchitekt',
    ],
    intro: [
      'Die Auftragsverarbeitung eines Handelsunternehmens wird auf ereignisgetriebene Verarbeitung umgestellt.',
      'Zwischen zwei bestehenden Systemen entsteht eine ereignisbasierte Integrationsschicht.',
    ],
    tasks: [
      'Schnitt und Umsetzung neuer Services',
      'Entwurf der Ereignisformate und ihrer Versionierung',
      'Lastverhalten messen und nachschärfen',
    ],
    skills: ['Java und Spring Boot', 'Kafka, Schema Registry', 'PostgreSQL', 'Kubernetes von der Anwendungsseite'],
  },
  {
    group: 'SAP + ABAP',
    tails: ['S/4HANA', 'FI/CO', 'ABAP OO'],
    titles: ['SAP ABAP Entwickler', 'SAP Berater FI/CO', 'SAP S/4HANA Entwickler'],
    intro: ['Im Rahmen einer S/4HANA-Umstellung wird das Entwicklungsteam verstärkt.'],
    tasks: ['Entwicklung kundeneigener Erweiterungen', 'Anpassung bestehender Reports'],
    skills: ['ABAP OO', 'Erfahrung mit S/4HANA'],
  },
  {
    group: 'Salesforce + CRM',
    tails: ['Sales Cloud', 'Apex', 'Lightning'],
    titles: ['Salesforce Entwickler', 'Salesforce Consultant', 'CRM Berater Salesforce'],
    intro: ['Für den Ausbau der Vertriebsprozesse wird eine bestehende Salesforce-Instanz erweitert.'],
    tasks: ['Umsetzung von Flows und Apex-Klassen', 'Abstimmung mit den Fachbereichen'],
    skills: ['Apex, Lightning Web Components', 'Erfahrung mit Sales Cloud'],
  },
  {
    group: 'Embedded + C',
    tails: ['Embedded Linux', 'RTOS', 'Firmware'],
    titles: ['Embedded Softwareentwickler C', 'Entwickler Embedded Linux', 'Firmware Entwickler'],
    intro: ['Für ein Steuergerät im industriellen Umfeld wird die Firmware weiterentwickelt.'],
    tasks: ['Entwicklung hardwarenaher Module', 'Inbetriebnahme am Prüfstand'],
    skills: ['C, C++', 'Erfahrung mit Echtzeitbetriebssystemen'],
  },
  {
    group: 'Data Engineering + Python',
    tails: ['dbt', 'Airflow', 'Snowflake'],
    titles: ['Data Engineer Python', 'Data Scientist', 'Machine Learning Engineer'],
    intro: ['Ein bestehendes Data Warehouse wird um automatisierte Strecken ergänzt.'],
    tasks: ['Aufbau von Datenstrecken', 'Qualitätssicherung der gelieferten Daten'],
    skills: ['Python, dbt', 'SQL auf Analyseniveau'],
  },
];

/**
 * The domain an ad names, which lands in the title and in the opening sentence.
 *
 * <p>Two jobs, and the second one is the reason this list exists at all. The obvious one
 * is variety: deduplication fingerprints the normalized title and nothing else, so a
 * generator drawing from five bare titles produces a corpus that is 80 % "duplicates" and
 * a shortlist of a dozen. The second is that `industry_fit` is scored by matching the
 * profile's industries against the ad's own text — with no domain named anywhere, that
 * factor is structurally zero and ten points of the scale are dead.
 */
const DOMAINS = [
  'E-Commerce',
  'Logistik',
  'Versicherung',
  'Handel',
  'Energieversorgung',
  'Automotive',
  'Gesundheitswesen',
  'Öffentlicher Dienst',
  'Telekommunikation',
  'Finanzdienstleistung',
  'Industrie',
  'Medien',
] as const;

/** Suffixes an ad carries. `TitleNormalizer` strips them before any comparison. */
const GENDER_SUFFIXES = ['(m/w/d)', '(w/m/d)', '(m/f/d)', ''] as const;

/** Contract wording that ends the assessment on the title alone. */
const REJECTED_CONTRACT = ['Festanstellung', 'Arbeitnehmerüberlassung', 'ANÜ'] as const;

// ---------------------------------------------------------------------------
// One offer.
// ---------------------------------------------------------------------------

type Offer = {
  title: string;
  agency: string | null;
  location: string;
  portal: string;
  publishedAt: Date;
  descriptionHtml: string;
  url: string;
  group: string;
};

/**
 * The reach rule reads free text, so the location is written the way a portal writes
 * it — sometimes a bare city, sometimes a city with a remote share beside it, and
 * sometimes nothing but "Remote". All three occur in a real corpus and each takes a
 * different path through the filter.
 */
function locationText(kind: 'near' | 'far' | 'abroad' | 'remote'): string {
  if (kind === 'remote') {
    return pick(['Remote', 'Remote (deutschlandweit)', 'Homeoffice']);
  }
  const city = kind === 'near' ? pick(NEAR_CITIES) : kind === 'far' ? pick(FAR_CITIES) : pick(ABROAD_CITIES);
  if (chance(0.45)) {
    return `Remote und ${city}`;
  }
  return chance(0.2) ? `${city} und Umgebung` : city;
}

function slug(title: string): string {
  return title
    .toLowerCase()
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/ß/g, 'ss')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

/**
 * The prose. Written as real markup — headings, paragraphs, a bullet list, some
 * emphasis — because `HtmlToMarkdown` is what turns it into the offer's description,
 * and a wall of one-line text would prove nothing about that step.
 */
function description(shape: Shape, domain: string, remotePercent: number | null, rateStated: boolean): string {
  const tasks = shuffle([...shape.tasks]).slice(0, 3);
  // All of them, not a sample: an ad naming one core skill scores a fraction of the
  // overlap weight, and a corpus of those produces a shortlist nothing reaches.
  const skills = shuffle([...shape.skills]);
  const parts: string[] = [];

  parts.push(`<p>Branche: ${domain}. ${pick(shape.intro)}</p>`);
  parts.push('<p><strong>Ihre Aufgaben</strong></p>');
  parts.push(`<ul>${tasks.map((task) => `<li>${task}</li>`).join('')}</ul>`);
  parts.push('<p><strong>Ihr Profil</strong></p>');
  parts.push(`<ul>${skills.map((skill) => `<li>${skill}</li>`).join('')}</ul>`);

  const conditions: string[] = [];
  conditions.push(`Start: ${pick(['ab sofort', 'kurzfristig', 'zum Monatsbeginn', 'nach Absprache'])}`);
  conditions.push(`Laufzeit: ${pick(['6 Monate', '9 Monate', '12 Monate'])} mit Option auf Verlängerung`);
  conditions.push(`Auslastung: ${pick(['Vollzeit', '4 Tage pro Woche', '80 %'])}`);
  if (remotePercent !== null) {
    conditions.push(`Remote-Anteil: ${remotePercent} %`);
  }
  if (rateStated) {
    conditions.push(`Stundensatz: ${65 + Math.floor(random() * 35)} EUR`);
  }
  parts.push(`<p>${conditions.join('<br>')}</p>`);
  return parts.join('\n      ');
}

function makeOffer(publishedAt: Date, portal: string): Offer {
  const shape = pick(SHAPES);
  const domain = pick(DOMAINS);
  const tail = chance(0.75) ? ` \u2013 ${pick(shape.tails)}` : '';
  let title = `${pick(shape.titles)} ${domain}${tail} ${pick(GENDER_SUFFIXES)}`.trim();

  // A share of ads name a contract form the profile does not take. Written into the
  // title on purpose: that is where the CONTRACT_FORM stage looks.
  if (chance(0.06)) {
    title = `${title} – ${pick(REJECTED_CONTRACT)}`;
  }

  const draw = random();
  const kind = draw < 0.34 ? 'near' : draw < 0.68 ? 'remote' : draw < 0.9 ? 'far' : 'abroad';
  const location = locationText(kind);

  // Measured on a real newsletter: a remote share is stated in well under a tenth of
  // offers, and an hourly rate in none at all. The demo keeps both rare, because the
  // enrichment stage exists precisely because they are missing here.
  const remotePercent = chance(0.09) ? pick([40, 60, 80, 100]) : null;
  const rateStated = chance(0.02);

  return {
    title,
    agency: chance(0.09) ? null : pick(AGENCIES),
    location,
    portal,
    publishedAt,
    descriptionHtml: description(shape, domain, remotePercent, rateStated),
    url: `https://${portal}.example/project/${slug(title)}-${Math.floor(random() * 900000 + 100000)}`,
    group: shape.group,
  };
}

// ---------------------------------------------------------------------------
// The mail around them.
// ---------------------------------------------------------------------------

const TWO_DIGITS = (value: number) => String(value).padStart(2, '0');

/** The format the source declares: `dd.MM.yyyy – HH:mm 'Uhr'`, en dash included. */
function publishedText(date: Date): string {
  const day = `${TWO_DIGITS(date.getDate())}.${TWO_DIGITS(date.getMonth() + 1)}.${date.getFullYear()}`;
  return `${day} &#8211; ${TWO_DIGITS(date.getHours())}:${TWO_DIGITS(date.getMinutes())} Uhr`;
}

function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * Every link in a newsletter is a tracking proxy carrying the subscriber's address.
 * The demo keeps that shape rather than linking straight through, because
 * `ProxyLink.unwrap` discarding the whole query is a privacy boundary and a demo that
 * never exercises it would suggest there is nothing to exercise.
 */
function proxyLink(url: string): string {
  return `https://tracker.example/proxy?target=${encodeURIComponent(url)}&amp;email=demo%2540example.org`;
}

function card(offer: Offer, highlight: string | null): string {
  const meta: string[] = [];
  if (offer.agency) {
    meta.push(`<span>&#127970; ${escapeHtml(offer.agency)}</span>`);
  }
  meta.push(`<span>&#128205; ${escapeHtml(offer.location)}</span>`);
  meta.push(`<span>&#128197; ${publishedText(offer.publishedAt)}</span>`);
  meta.push(`<span>&#128279; ${offer.portal}</span>`);

  // Some portals wrap the search term in the title. `text()` strips the element, but
  // a corpus without one would leave that claim untested.
  const title = highlight
    ? escapeHtml(offer.title).replace(highlight, `<mark>${highlight}</mark>`)
    : escapeHtml(offer.title);

  return `  <div class="job-card">
    <h3 class="job-title">${title}</h3>
    <div class="job-meta">
      ${meta.join('\n      ')}
    </div>
    <div class="job-description">
      ${offer.descriptionHtml}
    </div>
    <a href="${proxyLink(offer.url)}" class="job-link">Zum Projekt</a>
  </div>`;
}

const RFC_DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const RFC_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function rfc2822(date: Date): string {
  return (
    `${RFC_DAYS[date.getDay()]}, ${TWO_DIGITS(date.getDate())} ${RFC_MONTHS[date.getMonth()]} ` +
    `${date.getFullYear()} ${TWO_DIGITS(date.getHours())}:${TWO_DIGITS(date.getMinutes())}:00 +0200`
  );
}

function mail(offers: Offer[], sentAt: Date): string {
  const byGroup = new Map<string, Offer[]>();
  for (const offer of offers) {
    const bucket = byGroup.get(offer.group) ?? [];
    bucket.push(offer);
    byGroup.set(offer.group, bucket);
  }

  const groups = [...byGroup.entries()]
    .map(
      ([group, members]) => `<div class="tag-group">
  <h2 class="tag-header">${group.replace(/\+/g, '&#43;')}</h2>
${members.map((offer) => card(offer, offer.title.includes('Java') ? 'Java' : null)).join('\n')}
</div>`,
    )
    .join('\n');

  // The subject states the count, and `expect_count_from_subject` reads it back. That
  // is the one check nothing else can make: a selector that stops matching loses
  // offers, and fewer offers looks exactly like a quiet day on the market.
  return `From: newsletter@example.org
To: demo@example.org
Subject: ${offers.length} neue Projekte sind da!
Date: ${rfc2822(sentAt)}
MIME-Version: 1.0
Content-Type: multipart/alternative; boundary="demo"

--demo
Content-Type: text/plain; charset="utf-8"

Die Textfassung dieses Newsletters. Sie trägt keine der Auszeichnungen, an denen die
Extraktion arbeitet — ein Connector, der den ersten Teil statt des HTML-Teils nimmt,
findet hier null Angebote in einer vollkommen intakten Mail.

--demo
Content-Type: text/html; charset="utf-8"

<html><body>
${groups}
</body></html>

--demo--
`;
}

// ---------------------------------------------------------------------------
// The corpus.
// ---------------------------------------------------------------------------

/** Newest mail today, one every second day going back. */
function sentAtFor(index: number): Date {
  const date = new Date();
  date.setHours(7, 0, 0, 0);
  date.setDate(date.getDate() - (mailCount - 1 - index) * 2);
  return date;
}

rmSync(outputDir, { recursive: true, force: true });
mkdirSync(outputDir, { recursive: true });

let total = 0;
let duplicated = 0;
const written: string[] = [];
/** Offers eligible to reappear under another portal, which is what dedupe collapses. */
const republishable: Offer[] = [];

for (let index = 0; index < mailCount; index++) {
  const sentAt = sentAtFor(index);
  const count = 26 + Math.floor(random() * 20);
  const offers: Offer[] = [];

  for (let i = 0; i < count; i++) {
    // Roughly one offer in eight is the same project reaching the pipeline through a
    // second portal. That is the number this stage exists for; without it the
    // deduplication panel is an empty box with a good explanation next to it.
    if (republishable.length > 0 && chance(0.12)) {
      const original = pick(republishable);
      const portal = pick(PORTALS.filter((p) => p !== original.portal));
      const published = new Date(sentAt.getTime() - Math.floor(random() * 20) * 60000);
      offers.push({
        ...original,
        portal,
        agency: chance(0.09) ? null : pick(AGENCIES),
        publishedAt: published,
        url: `https://${portal}.example/project/${slug(original.title)}-${Math.floor(random() * 900000 + 100000)}`,
      });
      duplicated++;
      continue;
    }

    const published = new Date(sentAt.getTime() - Math.floor(random() * 20 * 60) * 60000);
    const offer = makeOffer(published, pick(PORTALS));
    offers.push(offer);
    if (republishable.length < 60) {
      republishable.push(offer);
    }
  }

  const name = `${sentAt.toISOString().slice(0, 10)}-${offers.length}-neue-projekte.eml`;
  writeFileSync(join(outputDir, name), mail(shuffle(offers), sentAt), 'utf8');
  written.push(`${name} (${offers.length} offers)`);
  total += offers.length;
}

console.log(`seed ${seed} → ${outputDir}`);
for (const line of written) {
  console.log(`  ${line}`);
}
console.log(`${total} offers, ${duplicated} of them a republication (${((duplicated / total) * 100).toFixed(1)} %)`);
