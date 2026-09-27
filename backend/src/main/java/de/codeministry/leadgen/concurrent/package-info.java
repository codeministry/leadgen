/**
 * Running a stage's per-advert work several at a time.
 *
 * <p>CONTENT, FIELDS and the synchronous SCORE spend nearly all of their time waiting on a model
 * that answers one advert at a time. {@link de.codeministry.leadgen.concurrent.BoundedWork} lets
 * such a loop keep the stage's configured width of requests in flight instead of one, on virtual
 * threads, and at a width nobody set it is the same loop on the same thread as before. The stages
 * decide what an item is and what a result means; this package decides only how many run at once.
 */
package de.codeministry.leadgen.concurrent;
