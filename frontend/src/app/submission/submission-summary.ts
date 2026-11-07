import { Component, input } from '@angular/core';
import { Submission } from '../api';
import { FieldNotes, FieldNotesContent, describedBy } from '../review/field-notes';
import { FIELDS, FieldDef, display } from '../review/review-fields';

/** A submission's values, read-only; optionally with review notes per field. */
@Component({
  selector: 'app-submission-summary',
  imports: [FieldNotes],
  template: `
    @let s = submission();
    <dl>
      @for (field of fields; track field.key) {
        @let n = notes()?.(field);
        <dt>{{ field.label }}</dt>
        <dd>
          <span [id]="'value-' + field.key" [attr.aria-describedby]="n ? described(field, n) : null">{{
            value(field, s)
          }}</span>
          @if (n) {
            <app-field-notes [id]="'value-' + field.key" [notes]="n" />
          }
        </dd>
      }
    </dl>
  `,
  styles: `
    dl { max-width: 40rem; }
    dt { margin-top: 0.75rem; font-weight: 600; }
    dd { margin-left: 0; }
  `,
})
export class SubmissionSummary {
  readonly submission = input.required<Submission>();
  readonly notes = input<(field: FieldDef) => FieldNotesContent>();

  protected readonly fields = FIELDS;

  protected value(field: FieldDef, submission: Submission): string {
    const shown = display(field, submission) ?? '—';
    return field.key === 'naicsCode' ? `${shown} (${submission.classOfBusiness.description})` : shown;
  }

  protected described(field: FieldDef, notes: FieldNotesContent): string | null {
    return describedBy(`value-${field.key}`, notes);
  }
}
