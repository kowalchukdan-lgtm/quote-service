import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { Extraction, Submission, SubmissionsService } from '../api';
import { problemOf } from '../problem';
import { FieldNotes, FieldNotesContent, describedBy } from './field-notes';
import { FIELDS, FieldDef, corrections, display, reviewInfo } from './review-fields';
import { ReviewNotices } from './review-notices';

/** A confirmed extraction, read-only: the submission that was approved, against the AI draft. */
@Component({
  selector: 'app-confirmed-view',
  imports: [DatePipe, FieldNotes, ReviewNotices],
  templateUrl: './confirmed-view.html',
  styles: `
    .submission-id { font-family: monospace; user-select: all; }
    dl { max-width: 40rem; }
    dt { margin-top: 0.75rem; font-weight: 600; }
    dd { margin-left: 0; }
    .error { color: #b00020; }
  `,
})
export class ConfirmedView {
  private readonly submissions = inject(SubmissionsService);

  readonly extraction = input.required<Extraction>();
  /** The submission just created by confirming, which saves fetching it again. */
  readonly submission = input<Submission>();

  protected readonly fields = FIELDS;
  protected readonly info = computed(() => reviewInfo(this.extraction()));
  protected readonly submissionId = computed(() => this.submission()?.id ?? this.extraction().submissionId);

  private readonly fetched = rxResource({
    params: () => (this.submission() ? undefined : this.extraction().submissionId),
    stream: ({ params: id }) => this.submissions.getSubmission(id),
  });

  protected readonly current = computed(() => this.submission() ?? this.fetched.value());
  protected readonly loading = computed(() => !this.submission() && this.fetched.isLoading());
  protected readonly loadError = computed(() => {
    const error = this.fetched.error();
    if (!error) {
      return undefined;
    }
    const problem = problemOf(error);
    return problem ? `${problem.status} ${problem.title}` : 'The submission could not be loaded.';
  });

  private readonly correctionNotes = computed(() => {
    const submitted = this.current();
    return submitted ? corrections(this.extraction().draft, submitted) : new Map();
  });

  protected readonly canCopy = typeof navigator !== 'undefined' && !!navigator.clipboard;
  protected readonly copied = signal(false);

  protected copy(id: string): void {
    navigator.clipboard.writeText(id).then(
      () => this.copied.set(true),
      () => this.copied.set(false),
    );
  }

  protected value(field: FieldDef, submission: Submission): string {
    const shown = display(field, submission) ?? '—';
    return field.key === 'naicsCode' ? `${shown} (${submission.classOfBusiness.description})` : shown;
  }

  protected notes(field: FieldDef): FieldNotesContent {
    return {
      review: this.info().byField.get(field.key),
      correction: this.correctionNotes().get(field.key),
    };
  }

  protected describedBy(field: FieldDef): string | null {
    return describedBy(`value-${field.key}`, this.notes(field));
  }
}
