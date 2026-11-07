import {
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FieldError, Problem, Quote, QuotesService, SubmissionsService } from '../api';
import { problemOf } from '../problem';
import { QuoteResult } from './quote-result';
import { SubmissionSummary } from './submission-summary';

interface QuoteError {
  title: string;
  detail?: string;
  errors: FieldError[];
  immutableNote: boolean;
}

/** Outcomes after which the same request may be retried with the same Idempotency-Key. */
function retryable(error: unknown): boolean {
  if (!(error instanceof HttpErrorResponse)) {
    return false;
  }
  return error.status === 0 || error.status === 409 || error.status >= 500;
}

/** /submissions/:submissionId — the submission, read-only, and its quote. */
@Component({
  selector: 'app-submission-page',
  imports: [DatePipe, QuoteResult, SubmissionSummary],
  templateUrl: './submission-page.html',
  styleUrl: './submission-page.scss',
})
export class SubmissionPage {
  private readonly submissions = inject(SubmissionsService);
  private readonly quotes = inject(QuotesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);

  // Bound from the :submissionId route parameter.
  readonly submissionId = input.required<string>();

  protected readonly submission = rxResource({
    params: () => this.submissionId(),
    stream: ({ params: id }) => this.submissions.getSubmission(id),
  });

  protected readonly problem = computed<Problem | undefined>(() => problemOf(this.submission.error()));

  // Quote state resets when the route moves to another submission.
  protected readonly quote = linkedSignal<string, Quote | undefined>({
    source: this.submissionId,
    computation: () => undefined,
  });
  protected readonly quoteError = linkedSignal<string, QuoteError | undefined>({
    source: this.submissionId,
    computation: () => undefined,
  });
  protected readonly quoting = signal(false);

  private readonly resultHeading = viewChild<ElementRef<HTMLElement>>('resultHeading');

  // One key per logical quote request, reused when retrying it. The server doesn't honour
  // Idempotency-Key yet (see README "Deferred"), so a retry can still create a duplicate quote
  // today; it will be deduplicated once idempotency enforcement ships.
  private idempotencyKey: string | undefined;

  protected requestQuote(event: Event): void {
    event.preventDefault();
    if (this.quoting()) {
      return;
    }
    const key = (this.idempotencyKey ??= crypto.randomUUID());
    this.quoting.set(true);
    this.quoteError.set(undefined);
    this.quotes
      .createQuote(this.submissionId(), key)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (quote) => {
          this.idempotencyKey = undefined;
          this.quoting.set(false);
          this.quote.set(quote);
          this.focusResult();
        },
        error: (error: unknown) => {
          if (!retryable(error)) {
            this.idempotencyKey = undefined;
          }
          this.quoting.set(false);
          this.quoteError.set(this.toQuoteError(error));
        },
      });
  }

  private focusResult(): void {
    afterNextRender(() => this.resultHeading()?.nativeElement.focus(), { injector: this.injector });
  }

  private toQuoteError(error: unknown): QuoteError {
    const problem = problemOf(error);
    return {
      title: problem?.title ?? 'The quote could not be requested.',
      detail: problem?.detail ?? (problem ? undefined : 'Check your connection and try again.'),
      errors: problem?.errors ?? [],
      immutableNote: problem?.type.endsWith('/business-rule-violation') ?? false,
    };
  }
}
