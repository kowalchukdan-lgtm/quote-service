import { Component, computed, inject, input, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ExtractionsService, Problem, Submission } from '../api';
import { problemOf } from '../problem';
import { ConfirmedView } from './confirmed-view';
import { ReviewForm } from './review-form';

/** /extractions/:extractionId — review a draft, or see what was confirmed. */
@Component({
  selector: 'app-review',
  imports: [ConfirmedView, ReviewForm],
  templateUrl: './review.html',
  styles: `
    .error { color: #b00020; }
    .document { color: #555; }
  `,
})
export class Review {
  private readonly extractions = inject(ExtractionsService);

  // Bound from the :extractionId route parameter.
  readonly extractionId = input.required<string>();

  protected readonly extraction = rxResource({
    params: () => this.extractionId(),
    stream: ({ params: id }) => this.extractions.getExtraction(id),
  });

  protected readonly problem = computed<Problem | undefined>(() => problemOf(this.extraction.error()));

  // Both reset when the route moves to another extraction.
  protected readonly justConfirmed = linkedSignal<string, Submission | undefined>({
    source: this.extractionId,
    computation: () => undefined,
  });
  protected readonly notice = linkedSignal<string, string | undefined>({
    source: this.extractionId,
    computation: () => undefined,
  });

  protected onAlreadyConfirmed(): void {
    this.notice.set('This extraction was already confirmed. Here is the submission it created.');
    this.extraction.reload();
  }
}
