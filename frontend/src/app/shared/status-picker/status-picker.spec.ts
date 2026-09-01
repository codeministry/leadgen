import { TestBed } from '@angular/core/testing';
import { StatusPicker } from './status-picker';

describe('StatusPicker', () => {
  it('emits the value the operator picked', () => {
    const fixture = TestBed.createComponent(StatusPicker);
    fixture.componentRef.setInput('value', 'SENT');
    fixture.componentRef.setInput('options', [
      { value: 'SENT', label: 'Sent' },
      { value: 'LOST', label: 'Lost' },
    ]);
    fixture.detectChanges();

    const picked: string[] = [];
    fixture.componentInstance.picked.subscribe((value) => picked.push(value));

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
    select.value = 'LOST';
    select.dispatchEvent(new Event('change'));

    expect(picked).toEqual(['LOST']);
  });

  it('shows the state the application is actually in', () => {
    // `[value]` on the select is written before `@for` has produced any options, so the
    // browser falls back to the first one — every card reads "New" and the mismatch with
    // the badge beside it is the only sign.
    const fixture = TestBed.createComponent(StatusPicker);
    fixture.componentRef.setInput('value', 'REPLIED');
    fixture.componentRef.setInput('options', [
      { value: 'NEW', label: 'New' },
      { value: 'REPLIED', label: 'Replied' },
    ]);
    fixture.detectChanges();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
    expect(select.value).toBe('REPLIED');
  });

  it('labels the control for a screen reader, because the card has no visible label', () => {
    const fixture = TestBed.createComponent(StatusPicker);
    fixture.componentRef.setInput('value', 'NEW');
    fixture.componentRef.setInput('options', [{ value: 'NEW', label: 'New' }]);
    fixture.componentRef.setInput('label', 'Application status');
    fixture.detectChanges();

    const label: HTMLLabelElement = fixture.nativeElement.querySelector('label');
    const select: HTMLSelectElement = fixture.nativeElement.querySelector('select');
    expect(label.textContent?.trim()).toBe('Application status');
    expect(label.getAttribute('for')).toBe(select.id);
  });
});
