import { Component, input } from '@angular/core';
import { ExtractionFlag } from '../api';
import { FieldReview } from './review-fields';

export interface FieldNotesContent {
  hint?: string;
  review?: FieldReview;
  correction?: string;
  error?: string;
}

/** Ids of the notes FieldNotes renders for `id`, for the input's aria-describedby. */
export function describedBy(id: string, notes: FieldNotesContent): string | null {
  const ids = [
    notes.hint && `${id}-hint`,
    notes.review?.flags.length && `${id}-flag`,
    notes.review?.evidence.length && `${id}-evidence`,
    notes.correction && `${id}-correction`,
    notes.error && `${id}-error`,
  ].filter(Boolean);
  return ids.length ? ids.join(' ') : null;
}

const REASON_LABEL: Record<string, string> = {
  MISSING: 'Not found in the document',
  APPROXIMATE: 'Approximate',
  AMBIGUOUS: 'Ambiguous',
  INVALID: "Read, but didn't fit the format, so it was left out",
  SUSPECTED_INSTRUCTION: 'Possible instruction text in the document',
};

/** The reviewer-facing label for a flag; unknown reasons still need review. */
export function reasonLabel(flag: ExtractionFlag): string {
  return REASON_LABEL[flag.reason] ?? 'Needs review';
}

/** The review information shown under one field. */
@Component({
  selector: 'app-field-notes',
  template: `
    @let n = notes();
    @if (n.hint) {
      <p class="hint" [id]="id() + '-hint'">{{ n.hint }}</p>
    }
    @if (n.review?.flags?.length) {
      <ul class="flags" [id]="id() + '-flag'">
        @for (flag of n.review!.flags; track $index) {
          <li [class.missing]="flag.reason === 'MISSING'">
            <strong>{{ label(flag) }}</strong>
            @if (flag.note) {
              : {{ flag.note }}
            }
          </li>
        }
      </ul>
    }
    @if (n.review?.evidence?.length) {
      <ul class="evidence" [id]="id() + '-evidence'">
        @for (e of n.review!.evidence; track $index) {
          <li>
            From the document: <q>{{ e.sourceText }}</q>
            <span class="confidence">Confidence: {{ e.confidence }}</span>
          </li>
        }
      </ul>
    }
    @if (n.correction) {
      <p class="correction" [id]="id() + '-correction'">{{ n.correction }}</p>
    }
    @if (n.error) {
      <p class="error" [id]="id() + '-error'">{{ n.error }}</p>
    }
  `,
  styles: `
    p, ul { margin: 0.25rem 0 0; font-size: 0.9rem; }
    ul { padding-left: 1.1rem; }
    .hint, .evidence { color: #555; }
    .flags { color: #7a4b00; }
    .flags .missing { color: #b00020; }
    .confidence { margin-left: 0.5em; font-size: 0.8rem; }
    .correction { color: #1a56db; }
    .error { color: #b00020; font-weight: 600; }
  `,
})
export class FieldNotes {
  readonly id = input.required<string>();
  readonly notes = input.required<FieldNotesContent>();

  protected readonly label = reasonLabel;
}
