import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Quote } from '../api';
import { formatUsd } from '../review/review-fields';

// Long form with the time of day and zone; with no timezone argument DatePipe uses the viewer's.
export const EXPIRY_FORMAT = 'EEEE, MMMM d, y, h:mm a zzzz';

/** One quote's outcome. Only QUOTED is bindable; unknown statuses are not, as the contract says. */
@Component({
  selector: 'app-quote-result',
  imports: [DatePipe],
  template: `
    @let q = quote();
    @switch (q.status) {
      @case ('QUOTED') {
        <p class="outcome bindable" data-testid="outcome">Quoted</p>
        <p class="premium">Annual premium: <strong data-testid="premium">{{ usd(q.premium?.amount) }}</strong></p>
        @if (q.expiresAt) {
          <p>
            Bindable until
            <time data-testid="expires" [attr.datetime]="q.expiresAt">{{ q.expiresAt | date: expiryFormat }}</time>
          </p>
        }
      }
      @case ('EXPIRED') {
        <p class="outcome" data-testid="outcome">Expired: not bindable</p>
        @if (q.expiresAt) {
          <p>
            Expired on
            <time data-testid="expires" [attr.datetime]="q.expiresAt">{{ q.expiresAt | date: expiryFormat }}</time>
          </p>
        }
        @if (q.premium) {
          <p>Premium was <span data-testid="premium">{{ usd(q.premium.amount) }}</span></p>
        }
      }
      @case ('REFERRED') {
        <p class="outcome" data-testid="outcome">Referred: needs underwriter review</p>
      }
      @case ('DECLINED') {
        <p class="outcome" data-testid="outcome">Declined</p>
      }
      @default {
        <p class="outcome" data-testid="outcome">Status {{ q.status }}: not bindable</p>
      }
    }

    @if (q.statusReasons.length) {
      <ul class="reasons" aria-label="Reasons">
        @for (reason of q.statusReasons; track $index) {
          <li>{{ reason.message }}</li>
        }
      </ul>
    }

    @if (showFactors() && q.ratingFactors.length) {
      <table>
        <caption>Rating factors, in the order applied</caption>
        <thead>
          <tr>
            <th scope="col">Description</th>
            <th scope="col">Input</th>
            <th scope="col">Factor</th>
          </tr>
        </thead>
        <tbody>
          @for (factor of q.ratingFactors; track $index) {
            <tr>
              <td>{{ factor.description }}</td>
              <td>{{ factor.inputValue }}</td>
              <td class="number">{{ factor.factor }}</td>
            </tr>
          }
        </tbody>
      </table>
    }

    <p class="quote-id">Quote <span>{{ q.id }}</span></p>
  `,
  styles: `
    .outcome { font-size: 1.15rem; font-weight: 600; }
    .bindable { color: #0a6b2d; }
    .premium strong { font-size: 1.15rem; }
    table { border-collapse: collapse; margin: 0.75rem 0; }
    caption { text-align: left; font-weight: 600; padding-bottom: 0.25rem; }
    th, td { padding: 0.25rem 0.75rem 0.25rem 0; text-align: left; border-bottom: 1px solid #ddd; }
    .number { font-variant-numeric: tabular-nums; }
    .quote-id { color: #555; font-size: 0.9rem; }
    .quote-id span { font-family: monospace; }
  `,
})
export class QuoteResult {
  readonly quote = input.required<Quote>();

  protected readonly expiryFormat = EXPIRY_FORMAT;
  // Factors explain a price, so they're shown only where the contract has one.
  protected readonly showFactors = computed(() => ['QUOTED', 'EXPIRED'].includes(this.quote().status));

  protected usd(amount: string | undefined): string {
    return amount === undefined ? '—' : formatUsd(amount);
  }
}
