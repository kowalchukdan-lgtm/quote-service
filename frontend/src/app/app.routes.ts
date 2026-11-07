import { Routes } from '@angular/router';
import { Review } from './review/review';
import { SubmissionPage } from './submission/submission-page';
import { Upload } from './upload/upload';

export const routes: Routes = [
  { path: '', component: Upload },
  { path: 'extractions/:extractionId', component: Review },
  { path: 'submissions/:submissionId', component: SubmissionPage },
];
