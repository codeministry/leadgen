import {computed, DestroyRef, inject, Injectable, signal} from '@angular/core';

/**
 * Whether anything on the page holds work a navigation away would lose.
 *
 * <p>A set of sources, each a function that reads signals: the cover letter's edited draft, the chat
 * composer's question, a chat turn still streaming. `any` is a `computed` over them, so it follows
 * every source as it changes without anybody polling. The one reader so far is `AuthService`,
 * which holds the redirect to the identity provider while `any` is true and lets the person take
 * the sign-in when they are ready; it is a general mechanism because the next thing that leaves
 * the page — a reload offer, a logout — has exactly the same question to ask.
 */
@Injectable({providedIn: 'root'})
export class UnsavedWork {
    private readonly sources = signal<ReadonlySet<() => boolean>>(new Set());

    readonly any = computed(() => [...this.sources()].some((dirty) => dirty()));

    /** Adds a source; the function returned removes it again. */
    track(dirty: () => boolean): () => void {
        this.sources.update((sources) => new Set(sources).add(dirty));
        return () =>
            this.sources.update((sources) => {
                const rest = new Set(sources);
                rest.delete(dirty);
                return rest;
            });
    }
}

/** `UnsavedWork.track` for as long as the calling component lives. Call it in an injection context. */
export function trackUnsavedWork(dirty: () => boolean): void {
    const untrack = inject(UnsavedWork).track(dirty);
    inject(DestroyRef).onDestroy(untrack);
}
