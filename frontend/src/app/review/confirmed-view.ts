import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Extraction, Submission, SubmissionsService } from '../api';
import { problemOf } from '../problem';
import { SubmissionSummary } from '../submission/submission-summary';
import { FieldNotesContent } from './field-notes';
import { FieldDef, corrections, reviewInfo } from './review-fields';
import { ReviewNotices } from './review-notices';

/** A confirmed extraction, read-only: the submission that was approved, against the AI draft. */
@Component({
  selector: 'app-confirmed-view',
  imports: [DatePipe, RouterLink, ReviewNotices, SubmissionSummary],
  templateUrl: './confirmed-view.html',
  styles: `
    .submission-id { font-family: monospace; }
    .error { color: #b00020; }
  `,
})
export class ConfirmedView {
  private readonly submissions = inject(SubmissionsService);

  readonly extraction = input.required<Extraction>();
  /** The submission just created by confirming, which saves fetching it again. */
  readonly submission = input<Submission>();

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

  // An arrow function, so the summary can call it without losing `this`.
  protected readonly notes = (field: FieldDef): FieldNotesContent => ({
    review: this.info().byField.get(field.key),
    correction: this.correctionNotes().get(field.key),
  });
}
