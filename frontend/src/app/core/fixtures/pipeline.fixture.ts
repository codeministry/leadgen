// FIXTURE — replace with the real endpoint (order of work, steps 5-9).
import { Application } from '@core/model/application';

export const APPLICATIONS_FIXTURE: readonly Application[] = [
  { id: 'a1', offerId: '1', title: 'Senior Java Entwickler Spring Boot (m/w/d)', agency: 'Etengo AG', status: 'PACKAGED', scoreValue: 88, rateEur: 95, sentOn: null, followUpOn: null },
  { id: 'a2', offerId: '9', title: 'Java Entwickler E-Commerce Plattform (m/w/d)', agency: null, status: 'SENT', scoreValue: 84, rateEur: 90, sentOn: '2026-08-28', followUpOn: '2026-09-04' },
  { id: 'a3', offerId: '12', title: 'Angular Entwickler PWA (m/w/d)', agency: 'Computer Futures', status: 'REPLIED', scoreValue: 83, rateEur: 89, sentOn: '2026-08-27', followUpOn: null },
  { id: 'a4', offerId: '2', title: 'Angular Frontend Entwickler für Versicherungsportal', agency: 'SOLCOM GmbH', status: 'INTERVIEW', scoreValue: 81, rateEur: 88, sentOn: '2026-08-24', followUpOn: '2026-09-02' },
  { id: 'a5', offerId: '11', title: 'Senior Softwarearchitekt (m/w/d) Java Landschaft', agency: 'Etengo AG', status: 'SHORTLISTED', scoreValue: 77, rateEur: 105, sentOn: null, followUpOn: null },
  { id: 'a6', offerId: '3', title: 'Java Backend Engineer, Microservices (m/w/d)', agency: null, status: 'NEW', scoreValue: 79, rateEur: null, sentOn: null, followUpOn: null },
  { id: 'a7', offerId: '13', title: 'Java Entwickler Schnittstellen (m/w/d)', agency: 'Hays AG', status: 'OFFER', scoreValue: 73, rateEur: 80, sentOn: '2026-08-18', followUpOn: null },
  { id: 'a8', offerId: '10', title: 'Entwickler Java mit Elasticsearch Erfahrung (m/w/d)', agency: 'SOLCOM GmbH', status: 'REJECTED', scoreValue: 69, rateEur: 86, sentOn: '2026-08-15', followUpOn: null },
  { id: 'a9', offerId: '6', title: 'Softwareentwickler Java (m/w/d) im Bankenumfeld', agency: 'Etengo AG', status: 'EXPIRED', scoreValue: 64, rateEur: 85, sentOn: '2026-08-11', followUpOn: null },
  { id: 'a10', offerId: '5', title: 'Kubernetes Platform Engineer (m/w/d)', agency: 'Computer Futures', status: 'WON', scoreValue: 72, rateEur: 92, sentOn: '2026-08-04', followUpOn: null },
];
