import { TestBed } from '@angular/core/testing';
import { StatTile } from './stat-tile';

describe('StatTile', () => {
  it('marks only the emphasised figure with the signal colour', () => {
    const fixture = TestBed.createComponent(StatTile);
    fixture.componentRef.setInput('label', 'Survived');
    fixture.componentRef.setInput('value', 239);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.text-signal')).toBeNull();

    fixture.componentRef.setInput('emphasis', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.text-signal')).not.toBeNull();
  });
});
