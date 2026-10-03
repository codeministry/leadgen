import {Component, signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {trackUnsavedWork, UnsavedWork} from './unsaved-work';

describe('UnsavedWork', () => {
    it('is dirty while any tracked source is, and follows the sources as they change', () => {
        const work = TestBed.inject(UnsavedWork);
        const letter = signal(false);
        const question = signal(false);
        work.track(letter);
        const untrack = work.track(question);

        expect(work.any()).toBe(false);
        question.set(true);
        expect(work.any()).toBe(true);
        untrack();
        expect(work.any()).toBe(false);
        letter.set(true);
        expect(work.any()).toBe(true);
    });

    it('lets a component register for as long as it lives', () => {
        const dirty = signal(true);
        @Component({selector: 'lg-probe', template: ''})
        class Probe {
            constructor() {
                trackUnsavedWork(dirty);
            }
        }
        const work = TestBed.inject(UnsavedWork);

        const fixture = TestBed.createComponent(Probe);
        expect(work.any()).toBe(true);
        fixture.destroy();
        expect(work.any()).toBe(false);
    });
});
