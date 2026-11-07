import { Component, input } from '@angular/core';
import { reasonLabel } from './field-notes';
import { ReviewInfo } from './review-fields';

/** Review information that belongs to no single field: shown above the form or summary. */
@Component({
  selector: 'app-review-notices',
  template: `
    @let r = info();
    @if (r.documentFlags.length) {
      <div class="banner" role="alert">
        <p>
          <strong>The document contains text aimed at automated systems.</strong>
          It changed nothing in the draft, but please read the document yourself before confirming.
        </p>
        <ul>
          @for (flag of r.documentFlags; track $index) {
            <li>{{ label(flag) }}@if (flag.note) {: {{ flag.note }}}</li>
          }
        </ul>
      </div>
    }
    @if (r.unplaced.length || r.countryNote || r.limitNote) {
      <div class="also-check">
        <p><strong>Also check</strong></p>
        <ul>
          @for (flag of r.unplaced; track $index) {
            <li><code>{{ flag.field }}</code>: {{ label(flag) }}@if (flag.note) {. {{ flag.note }}}</li>
          }
          @if (r.countryNote) {
            <li>{{ r.countryNote }}</li>
          }
          @if (r.limitNote) {
            <li>{{ r.limitNote }}</li>
          }
        </ul>
      </div>
    }
  `,
  styles: `
    .banner, .also-check { margin-bottom: 1rem; padding: 0.75rem 1rem; border-radius: 6px; }
    .banner { border: 2px solid #b00020; background: #fdecee; }
    .also-check { border: 1px solid #c58a00; background: #fff7e6; }
    p, ul { margin: 0.25rem 0; }
  `,
})
export class ReviewNotices {
  readonly info = input.required<ReviewInfo>();

  protected readonly label = reasonLabel;
}
